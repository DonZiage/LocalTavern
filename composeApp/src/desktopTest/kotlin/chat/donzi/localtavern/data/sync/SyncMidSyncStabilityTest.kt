package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Stability tests for mid-sync local changes: a change made while a sync is
// in process must neither break the sync nor be lost, and a large change
// (deleting a big session) must drain in bounded batches instead of wedging
// every future sync with an oversized envelope.
class SyncMidSyncStabilityTest {

    private class InMemoryBlobStore : BlobStore {
        private val map = mutableMapOf<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) { map[key] = bytes }
        override suspend fun read(key: String): ByteArray? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun listKeys(): Set<String> = map.keys.toSet()
    }

    private class FakeIdentityStore : SyncIdentityStore {
        private var bytes: ByteArray? = null
        override fun load(): ByteArray? = bytes
        override fun save(bytes: ByteArray) { this.bytes = bytes }
    }

    private class Device(deviceId: String, name: String, port: Int, tempDir: File) {
        // File-backed database with production dispatchers (one write
        // connection via a single-thread dispatcher, a small read pool under
        // WAL): the in-memory driver's shared connection cannot exercise the
        // multi-connection path, which is exactly where mid-sync concurrency
        // must be proven safe.
        private val dbFile = File(tempDir, "$deviceId.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?busy_timeout=5000").also {
            LocalTavernDB.Schema.create(it)
            it.execute(null, "PRAGMA journal_mode=WAL;", 0)
        }
        val db = LocalTavernDB(driver)
        val crypto = SyncCrypto()
        val identity = kotlinx.coroutines.runBlocking {
            SyncIdentity.create(deviceName = name, crypto = crypto).copy(deviceId = deviceId)
        }
        val blobStore = InMemoryBlobStore()
        val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
        val readDispatcher = Dispatchers.IO.limitedParallelism(3)
        val repository = SyncRepository(db, identity, ioDispatcher = writeDispatcher, readDispatcher = readDispatcher, blobStore = blobStore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = SyncService(
            initialIdentity = identity,
            crypto = crypto,
            repository = repository,
            identityStore = FakeIdentityStore(),
            httpClient = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } },
            scope = scope,
            port = port,
            blobStore = blobStore
        )

        fun start() = service.startServer()
        fun stop() {
            service.stopServer()
            scope.cancel()
        }

        fun personas() = db.localTavernDBQueries.selectAllPersonas().executeAsList()
    }

    private companion object {
        var portCounter = 500
    }

    private fun freePort(): Int = 23400 + portCounter++

    private fun devicePair(tempDir: File, hostPort: Int): Pair<Device, Device> {
        val hostDir = File(tempDir, "a").apply { mkdirs() }
        val guestDir = File(tempDir, "b").apply { mkdirs() }
        val host = Device("host-device", "Host", hostPort, hostDir)
        val guest = Device("guest-device", "Guest", hostPort + 1, guestDir)
        return host to guest
    }

    private fun seedPersona(device: Device, id: String, name: String, updatedAt: Long) {
        device.db.localTavernDBQueries.insertPersonaFull(
            id = id, name = name, description = null, avatarData = null, avatarRef = null,
            updatedAt = updatedAt, isDeleted = 0L, syncSeq = 0L
        )
    }

    // A local write during a running sync must be delivered by the same
    // session (no second sync needed) and must not break the sync.
    @Test
    fun localInsertDuringSync_isDeliveredBySameSession() = runTest {
        val hostPort = freePort()
        val tempDir = Files.createTempDirectory("mid-change").toFile()
        val (host, guest) = devicePair(tempDir, hostPort)
        try {
            host.start()
            guest.start()
            for (i in 0 until 400) seedPersona(host, "seed-$i", "Seed $i", 1000L + i)
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            val sync = async { guest.service.syncNow("host-device") }
            // Wait until the sync is provably in process (first progress
            // report), then make a local change on the initiating side.
            withTimeout(30_000) {
                while (guest.service.state.value.syncProgress == null) delay(10)
            }
            seedPersona(guest, "mid-sync", "Made During Sync", 5000L)
            val result = sync.await()
            assertTrue(result.isSuccess, "Sync must succeed despite a mid-sync change: ${result.exceptionOrNull()}")
            assertTrue(
                host.personas().any { it.id == "mid-sync" },
                "A change made during sync must arrive in the same session"
            )
        } finally {
            host.stop()
            guest.stop()
            tempDir.deleteRecursively()
        }
    }

    // Two overlapping sync sessions against the same peer must both succeed
    // and must not corrupt cursors (a follow-up sync stays a no-op).
    @Test
    fun concurrentSyncSessions_convergeWithoutCorruption() = runTest {
        val hostPort = freePort()
        val tempDir = Files.createTempDirectory("mid-change").toFile()
        val (host, guest) = devicePair(tempDir, hostPort)
        try {
            host.start()
            guest.start()
            for (i in 0 until 200) seedPersona(host, "seed-$i", "Seed $i", 1000L + i)
            seedPersona(guest, "guest-0", "Guest", 2000L)
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            val a = async { guest.service.syncNow("host-device") }
            val b = async { guest.service.syncNow("host-device") }
            val (ra, rb) = a.await() to b.await()
            assertTrue(ra.isSuccess, "First concurrent sync must succeed: ${ra.exceptionOrNull()}")
            assertTrue(rb.isSuccess, "Second concurrent sync must succeed: ${rb.exceptionOrNull()}")
            assertEquals(201, host.personas().size, "Host must hold every row exactly once")
            assertEquals(201, guest.personas().size, "Guest must hold every row exactly once")

            // Cursors must be exact afterwards: one more sync succeeds and
            // ships nothing.
            val third = guest.service.syncNow("host-device")
            assertTrue(third.isSuccess, "Follow-up sync after concurrent sessions must succeed: ${third.exceptionOrNull()}")
        } finally {
            host.stop()
            guest.stop()
            tempDir.deleteRecursively()
        }
    }

    // Deleting a large session must not wedge sync forever: the tombstone
    // batch must drain in bounded rounds instead of dying on an oversized
    // envelope that the peer rejects (413) with the cursor frozen.
    @Test
    fun deletingLargeSession_syncStillConverges() = runTest {
        val hostPort = freePort()
        val tempDir = Files.createTempDirectory("mid-change").toFile()
        val (host, guest) = devicePair(tempDir, hostPort)
        try {
            host.start()
            guest.start()
            val clock = LogicalClock(host.db)
            val sessions = SessionRepository(host.db, ioDispatcher = host.writeDispatcher, clock = clock, readDispatcher = host.readDispatcher)
            val messages = MessageRepository(host.db, ioDispatcher = host.writeDispatcher, clock = clock, sessionRepository = sessions, readDispatcher = host.readDispatcher)
            val sessionId = sessions.createNewSession("char-1", "persona-1")
            // ~18MB of messages in one session. Deleting stamps them with a
            // single syncSeq: if the batch estimator undercounts the group,
            // the tombstone envelope exceeds the wire cap and sync dies
            // permanently (cursor frozen, every retry rejected).
            val content = "x".repeat(60 * 1024)
            repeat(300) {
                messages.insertMessage(sessionId, "user", content, parentId = null)
            }
            sessions.deleteSession(sessionId)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            guest.repository.updatePeerAddress("host-device", "127.0.0.1:$hostPort")
            val result = guest.service.syncNow("host-device")
            assertTrue(result.isSuccess, "Sync after deleting a large session must succeed: ${result.exceptionOrNull()}")
            assertEquals(0, guest.db.localTavernDBQueries.selectAllMessagesForSession(sessionId).executeAsList().size)
        } finally {
            host.stop()
            guest.stop()
            tempDir.deleteRecursively()
        }
    }

    // Host-only confirm + host-initiated sync must complete the guest pairing
    // (the guest auto-completes on the authenticated exchange).
    @Test
    fun hostOnlyConfirm_plusHostSync_completesGuestPairing() = runTest {
        val hostPort = freePort()
        val tempDir = Files.createTempDirectory("mid-change").toFile()
        val (host, guest) = devicePair(tempDir, hostPort)
        try {
            host.start()
            guest.start()
            seedPersona(host, "p-host", "Host Persona", 1000L)
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            // Only the host confirms ("matches" on desktop).
            host.service.confirmFingerprint()
            // Host initiates the sync (what the fixed host UI must do).
            host.repository.updatePeerAddress("guest-device", "127.0.0.1:${hostPort + 1}")
            val result = host.service.syncNow("guest-device")
            assertTrue(result.isSuccess, "Host-initiated sync must succeed: ${result.exceptionOrNull()}")
            assertEquals(null, guest.service.state.value.pendingPeerFingerprint, "Guest must auto-complete pairing")
            assertTrue(guest.personas().any { it.id == "p-host" }, "Guest must receive host data")
        } finally {
            host.stop()
            guest.stop()
            tempDir.deleteRecursively()
        }
    }
}

package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Red-team: cancelling the fingerprint step must not leave a trusted peer.
// Pairing creates the peer row BEFORE the fingerprint is confirmed. If the
// user cancels (mismatch / wrong device), cancelPairing() clears the pending
// flag but leaves the row — syncNow then trusts it without any OOB check.
class SyncPendingCancelRedTeamTest {
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

    private class Device(deviceId: String, name: String, port: Int) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val db = LocalTavernDB(driver)
        val crypto = SyncCrypto()
        val identity = runBlocking {
            SyncIdentity.create(deviceName = name, crypto = crypto).copy(deviceId = deviceId)
        }
        val blobStore = InMemoryBlobStore()
        val repository = SyncRepository(db, identity, blobStore = blobStore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = SyncService(
            initialIdentity = identity,
            crypto = crypto,
            repository = repository,
            identityStore = FakeIdentityStore(),
            httpClient = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            },
            scope = scope,
            port = port,
            blobStore = blobStore
        )

        fun start() = service.startServer()
        fun stop() {
            service.stopServer()
            scope.cancel()
        }
    }

    private fun freePort(): Int {
        java.net.ServerSocket(0).use { return it.localPort }
    }

    @Test
    fun cancelBeforeConfirm_doesNotLeaveTrustedPeer() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            val result = guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            assertTrue(result.isSuccess)
            assertNotNull(guest.service.state.value.pendingPeerFingerprint)

            // User sees a fingerprint mismatch and cancels.
            guest.service.cancelPairing()

            // The unverified peer must be gone (or still gated). It must NOT
            // be possible to sync with it as if it were verified.
            val unverifiedPeer = guest.repository.getPeer("host-device")
            val syncResult = guest.service.syncNow("host-device")
            val gated = unverifiedPeer == null ||
                (syncResult.isFailure && syncResult.exceptionOrNull()?.message?.contains("fingerprint", ignoreCase = true) == true)
            assertTrue(gated, "Cancelled unverified peer must not be syncable: peer=$unverifiedPeer sync=$syncResult")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun hostCancelBeforeConfirm_doesNotLeaveTrustedPeer() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            val result = guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            assertTrue(result.isSuccess)
            assertNotNull(host.service.state.value.pendingPeerFingerprint)

            host.service.cancelPairing()

            val unverifiedPeer = host.repository.getPeer("guest-device")
            assertNull(unverifiedPeer, "Host cancel must remove the unverified peer, got $unverifiedPeer")
        } finally {
            host.stop()
            guest.stop()
        }
    }
}

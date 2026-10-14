package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.SyncPeer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Stability red team: big synchronizations must never crash the app, must
// report progress, must be cancellable, and must never corrupt the database.
// Every test below must pass on a mobile-class heap; none of them may throw.
class SyncStabilityRedTeamTest {

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    private fun newIdentity(deviceId: String) = SyncIdentity(
        deviceId = deviceId,
        deviceName = deviceId,
        privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
        publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
    )

    private fun newRepository(db: LocalTavernDB, deviceId: String): SyncRepository {
        val identity = newIdentity(deviceId)
        return SyncRepository(db, identity, clock = LogicalClock(db))
    }

    private fun syncPersona(id: String, n: Int) = SyncPersona(
        id = id, name = "persona-$n", description = null, avatarData = null,
        updatedAt = 1000L + n, isDeleted = 0L
    )

    // ---------- Apply progress: a big batch must report progress in chunks ----------

    @Test
    fun applyChanges_reportsChunkedProgress_andLandsEveryRow() = runTest {
        val db = newDb()
        val repo = newRepository(db, "device-a")
        val changes = SyncChanges(personas = (0 until 500).map { syncPersona("p$it", it) })

        val callbacks = mutableListOf<Int>()
        repo.applyChanges(changes, peerDeviceId = "device-b") { applied, _ ->
            callbacks.add(applied)
        }

        assertEquals(500, db.localTavernDBQueries.selectAllPersonas().executeAsList().size)
        assertTrue(callbacks.isNotEmpty(), "A 500-row batch must report progress at least once")
        assertTrue(callbacks.zipWithNext().all { (a, b) -> b >= a }, "Progress must be monotone")
        assertEquals(500, callbacks.last(), "Final progress must equal the total applied rows")
    }

    // ---------- Absurd envelopes must be rejected BEFORE any allocation ----------

    @Test
    fun absurdEnvelopeRowCount_isRejectedWithoutApplying() = runTest {
        val db = newDb()
        val repo = newRepository(db, "device-a")
        // 30k empty personas would otherwise queue ~120k statements and pin
        // the whole batch in memory on a phone.
        val changes = SyncChanges(personas = (0 until 30_000).map { syncPersona("p$it", it) })

        val result = runCatching {
            repo.applyChanges(changes, peerDeviceId = "device-b")
        }
        assertTrue(result.isFailure, "An absurd 30k-row envelope must be rejected, not applied")
        // Nothing partial may survive: either all rows land or none do.
        val stored = db.localTavernDBQueries.selectAllPersonas().executeAsList().size
        assertTrue(stored == 0, "A rejected envelope must leave zero rows behind, got $stored")
    }

    // ---------- Oversized encrypted payloads must be refused before decode ----------

    @Test
    fun oversizedEncryptedPayload_isRejectedWithoutDecrypt() = runTest {
        val db = newDb()
        val repo = newRepository(db, "device-a")
        val crypto = SyncCrypto()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val channelKeys = SyncChannelKeys(crypto) { newIdentity("device-a") }
            val client = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val exchange = SyncExchange(
                crypto = crypto,
                repository = repo,
                identityProvider = { newIdentity("device-a") },
                httpClient = client,
                scope = scope,
                blobStore = null,
                channelKeys = channelKeys,
                blobTransfer = SyncBlobTransfer(
                    crypto = crypto,
                    repository = repo,
                    state = kotlinx.coroutines.flow.MutableStateFlow(SyncUiState()),
                    identityProvider = { newIdentity("device-a") },
                    httpClient = client,
                    blobStore = null,
                    channelKeys = channelKeys,
                    noteActivity = {}
                ),
                advertisedFetchAddress = { null },
                noteActivity = {}
            )
            // Pair the peer so the request reaches the payload gate.
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            repo.upsertPeer(
                SyncPeer(
                    deviceId = "device-b", name = "b",
                    publicKey = ByteArray(32) { 7 },
                    lastKnownAddress = null, receivedCursor = 0L,
                    peerReceivedCursor = 0L, lastSyncAt = now, updatedAt = now, isDeleted = 0L
                )
            )
            // 26 MB of valid base64 (over the 24 MB gate): decoding +
            // decrypting it would transiently hold tens of MB on a phone. It
            // must be refused by length alone, before any allocation.
            val hugeB64 = "A".repeat(26 * 1024 * 1024)
            val response = exchange.handleExchange("device-b", hugeB64, encodeBase64(ByteArray(32)), freshExchangeId())
            assertFalse(response.ok, "An oversized payload must be refused")
            client.close()
        } finally {
            scope.cancel()
        }
    }

    // ---------- Malformed exchange inputs must never throw ----------

    @Test
    fun malformedExchangeInputs_neverThrow() = runTest {
        val db = newDb()
        val repo = newRepository(db, "device-a")
        val crypto = SyncCrypto()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val channelKeys = SyncChannelKeys(crypto) { newIdentity("device-a") }
            val client = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val state = kotlinx.coroutines.flow.MutableStateFlow(SyncUiState())
            val exchange = SyncExchange(
                crypto = crypto,
                repository = repo,
                identityProvider = { newIdentity("device-a") },
                httpClient = client,
                scope = scope,
                blobStore = null,
                channelKeys = channelKeys,
                blobTransfer = SyncBlobTransfer(
                    crypto = crypto, repository = repo, state = state,
                    identityProvider = { newIdentity("device-a") },
                    httpClient = client, blobStore = null,
                    channelKeys = channelKeys, noteActivity = {}
                ),
                advertisedFetchAddress = { null },
                noteActivity = {}
            )
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            repo.upsertPeer(
                SyncPeer(
                    deviceId = "device-b", name = "b",
                    publicKey = ByteArray(32) { 7 },
                    lastKnownAddress = null, receivedCursor = 0L,
                    peerReceivedCursor = 0L, lastSyncAt = now, updatedAt = now, isDeleted = 0L
                )
            )
            listOf("", "!!!not-base64!!!", "AAAA", "A".repeat(1_000_000)).forEach { payload ->
                val response = exchange.handleExchange("device-b", payload, "!!!", freshExchangeId())
                assertFalse(response.ok, "Garbage payload must be a clean refusal: ${payload.take(20)}")
            }
            // The database must still be healthy and empty afterwards.
            assertEquals(0, db.localTavernDBQueries.selectAllPersonas().executeAsList().size)
            client.close()
        } finally {
            scope.cancel()
        }
    }

    // ---------- SyncService: progress is exposed and cancellation clears it ----------

    @Test
    fun cancelSync_withoutActiveSync_clearsStateAndNeverThrows() = runTest {
        val db = newDb()
        val repo = newRepository(db, "device-a")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val client = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val service = SyncService(
                initialIdentity = newIdentity("device-a"),
                crypto = SyncCrypto(),
                repository = repo,
                identityStore = object : SyncIdentityStore {
                    override fun load(): ByteArray? = null
                    override fun save(bytes: ByteArray) {}
                },
                httpClient = client,
                scope = scope
            )
            // No sync running: cancel must be a safe no-op that leaves the
            // state clean (never stuck "syncing" with a phantom progress).
            service.cancelSync()
            val state = service.state.first()
            assertFalse(state.isSyncing, "cancelSync with no active sync must not leave isSyncing=true")
            assertEquals(null, state.syncProgress, "cancelSync must clear any progress")
            client.close()
        } finally {
            scope.cancel()
        }
    }
}

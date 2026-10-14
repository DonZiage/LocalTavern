package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.utils.Hashing
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Red team: applying an envelope whose avatars ride out of band must not pin
// the whole envelope's avatar bytes before its first write.
//
// Avatars above the inline threshold travel as content-addressed refs; the
// receiver resolves them from its blob store while applying. The exchange
// resolved EVERY ref of the envelope into one map before applyChanges ran,
// and applyChanges preloaded EVERY existing avatar into a second map before
// its first commit. A ref-heavy envelope (thousands of refs are only ~150
// wire bytes each, so a whole library fits one batch) therefore pins the
// whole library's bytes on a mobile heap on top of the envelope decode — an
// OOM kill mid-sync. The file stays structurally valid (so openHealthyDriver
// never quarantines it) and auto-sync replays the same envelope at launch:
// a crash-at-launch loop only a data wipe clears.
class SyncApplyAvatarsRedTeamTest {

    private class TrackingBlobStore(
        private val db: LocalTavernDB,
        val blobs: Map<String, ByteArray>
    ) : BlobStore {
        // Rows already holding avatar bytes at the moment of each blob read.
        // Interleaved (bounded) resolution shows this number climbing WHILE
        // reads are still happening; buffer-everything-first shows zero on
        // every read.
        val appliedAtRead = ArrayList<Int>()

        override suspend fun write(key: String, bytes: ByteArray) = Unit
        override suspend fun read(key: String): ByteArray? {
            appliedAtRead.add(
                db.localTavernDBQueries.selectAllCharacters().executeAsList().count { it.avatarData != null }
            )
            return blobs[key]
        }
        override suspend fun delete(key: String) = Unit
        override suspend fun listKeys(): Set<String> = blobs.keys.toSet()
    }

    private fun character(id: String, updatedAt: Long, sha: String, size: Long) = SyncCharacter(
        id = id, name = "Character $id", description = "description", personality = "personality",
        scenario = "scenario", firstMes = null, mesExample = null, creatorNotes = null,
        altGreetings = null, avatarData = null, avatarRef = SyncImageRef(sha, size),
        isAssistant = 0L, updatedAt = updatedAt, isDeleted = 0L, systemPrompt = null,
        postHistoryInstructions = null, creator = null, characterVersion = null,
        tags = null, extensions = null, characterBook = null
    )

    @Test
    fun apply_resolvesAvatarsInBoundedBatches() = runTest {
        val crypto = SyncCrypto()
        val sender = SyncIdentity.create("Sender", crypto, "sender-dev")
        val receiver = SyncIdentity.create("Receiver", crypto, "receiver-dev")

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val repo = SyncRepository(db, receiver, clock = LogicalClock(db))
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        repo.upsertPeer(
            SyncPeer(
                deviceId = sender.deviceId, name = "sender",
                publicKey = sender.publicKeyBytes,
                lastKnownAddress = null, receivedCursor = 0L,
                peerReceivedCursor = 0L, lastSyncAt = now, updatedAt = now, isDeleted = 0L
            )
        )

        // 500 ref-characters: several apply chunks (200 rows each), each ref
        // tiny on the wire but 32 KB resolved.
        val count = 500
        val blobs = LinkedHashMap<String, ByteArray>()
        val rows = (1..count).map { i ->
            val bytes = ByteArray(32 * 1024) { ((it + i) % 251).toByte() }
            val sha = Hashing.sha256Hex(bytes)
            blobs[sha] = bytes
            character("char-$i", i.toLong(), sha, bytes.size.toLong())
        }
        val store = TrackingBlobStore(db, blobs)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        try {
            val receiverKeys = SyncChannelKeys(crypto) { receiver }
            val senderKeys = SyncChannelKeys(crypto) { sender }
            val exchange = SyncExchange(
                crypto = crypto,
                repository = repo,
                identityProvider = { receiver },
                httpClient = client,
                scope = scope,
                blobStore = store,
                channelKeys = receiverKeys,
                blobTransfer = SyncBlobTransfer(
                    crypto = crypto,
                    repository = repo,
                    state = MutableStateFlow(SyncUiState()),
                    identityProvider = { receiver },
                    httpClient = client,
                    blobStore = store,
                    channelKeys = receiverKeys,
                    noteActivity = {}
                ),
                advertisedFetchAddress = { null },
                noteActivity = {}
            )

            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val envelopeBytes = json.encodeToString(
                SyncEnvelope.serializer(),
                SyncEnvelope(
                    fromDeviceId = sender.deviceId,
                    cursor = 0L,
                    changes = SyncChanges(characters = rows)
                )
            ).encodeToByteArray()
            val exchangeId = freshExchangeId()
            val senderOut = senderKeys.outboundChannelKey(receiver.publicKeyBytes)
            val payload = encodeBase64(
                crypto.encrypt(
                    senderOut.key,
                    aad(from = sender.deviceId, to = receiver.deviceId, exchangeId = exchangeId),
                    envelopeBytes
                )
            )

            val response = exchange.handleExchange(
                sender.deviceId, payload, encodeBase64(senderOut.ephemeralPublicKey), exchangeId
            )
            assertTrue(response.ok, "The ref-heavy envelope must apply, got: ${response.message}")

            val landed = db.localTavernDBQueries.selectAllCharacters().executeAsList()
            assertEquals(count, landed.size, "Every character must land")
            assertTrue(
                landed.all { it.avatarData != null },
                "Every avatar ref must resolve when the bytes are in the store"
            )

            // Bounded buffering: by the time later blobs are read, earlier
            // chunks must already be committed — resolution must never hold
            // all 500 blobs at once before its first write.
            val maxApplied = store.appliedAtRead.maxOrNull() ?: 0
            assertTrue(
                maxApplied >= 200,
                "Avatar bytes must be resolved incrementally (bounded batches), " +
                    "but every read still saw $maxApplied applied rows: the whole " +
                    "envelope is buffered before the first write"
            )
        } finally {
            client.close()
            scope.cancel()
        }
    }
}

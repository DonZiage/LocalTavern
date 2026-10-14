package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.utils.Hashing
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
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

// Red team: out-of-band blob fetching must address one ref per request and
// bound each call, no matter how many refs are missing.
//
// Every chunk request carried the FULL pending ref list
// (BlobFetchPayload.refs = pending.map { sha }, rebuilt for every chunk of
// every ref). With N missing refs the traffic is O(N x chunks) — a 20k-ref
// backlog sends ~1.3 MB of ref list per request, thousands of times —
// stalling sync for hours and pinning the list on a mobile heap. A dropped
// connection then restarts the same quadratic transfer on the next round,
// and a kill mid-transfer loses everything: sync never converges on a big
// library. Requests must address exactly the ref they serve, and one call
// must not try to drain an unbounded backlog.
class SyncBlobWindowRedTeamTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class InMemoryBlobStore : BlobStore {
        val map = LinkedHashMap<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) { map[key] = bytes }
        override suspend fun read(key: String): ByteArray? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun listKeys(): Set<String> = map.keys.toSet()
    }

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    // Loopback harness: the MockEngine poses as the peer holding [holderBlobs],
    // serving real protocol chunks; every request's ref-list size is captured.
    private suspend fun newTransfer(
        fetcher: SyncIdentity,
        holder: SyncIdentity,
        holderBlobs: Map<String, ByteArray>,
        fetcherStore: BlobStore,
        seenRefCounts: MutableList<Int>
    ): Triple<SyncBlobTransfer, HttpClient, CoroutineScope> {
        val crypto = SyncCrypto()
        val holderKeys = SyncChannelKeys(crypto) { holder }
        val client = HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val fetchRequest = json.decodeFromString(BlobFetchRequest.serializer(), body)
            val peerEphemeral = decodeBase64(fetchRequest.ephemeralPublicKey)
            val inbound = holderKeys.inboundChannelKey(fetcher.publicKeyBytes, peerEphemeral)
            val payloadBytes = crypto.decrypt(
                inbound,
                aad(from = fetchRequest.fromDeviceId, to = holder.deviceId, exchangeId = fetchRequest.exchangeId),
                decodeBase64(fetchRequest.payload)
            )
            val payload = json.decodeFromString(BlobFetchPayload.serializer(), payloadBytes.decodeToString())
            seenRefCounts.add(payload.refs.size)
            val hash = payload.refs.getOrNull(payload.refIndex)
            val bytes = hash?.let { holderBlobs[it] }
            val result = if (bytes == null) {
                BlobFetchResult(
                    refIndex = payload.refIndex, offset = payload.offset, total = 0,
                    data = "", hasMore = false,
                    missing = hash?.let { listOf(it) } ?: emptyList()
                )
            } else {
                val chunk = if (payload.offset < bytes.size) {
                    bytes.copyOfRange(payload.offset, minOf(payload.offset + CHUNK_BYTES, bytes.size))
                } else {
                    ByteArray(0)
                }
                BlobFetchResult(
                    refIndex = payload.refIndex, offset = payload.offset, total = bytes.size,
                    data = encodeBase64(chunk), hasMore = payload.offset + chunk.size < bytes.size
                )
            }
            val outbound = holderKeys.outboundChannelKey(fetcher.publicKeyBytes)
            val responsePayload = encodeBase64(
                crypto.encrypt(
                    outbound.key,
                    aad(from = holder.deviceId, to = fetchRequest.fromDeviceId, exchangeId = fetchRequest.exchangeId),
                    json.encodeToString(BlobFetchResult.serializer(), result).encodeToByteArray()
                )
            )
            respond(
                content = ByteReadChannel(
                    json.encodeToString(
                        BlobFetchResponse.serializer(),
                        BlobFetchResponse(
                            ok = true, exchangeId = fetchRequest.exchangeId,
                            payload = responsePayload,
                            ephemeralPublicKey = encodeBase64(outbound.ephemeralPublicKey)
                        )
                    )
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = newDb()
        val transfer = SyncBlobTransfer(
            crypto = crypto,
            repository = SyncRepository(db, fetcher, clock = LogicalClock(db)),
            state = MutableStateFlow(SyncUiState()),
            identityProvider = { fetcher },
            httpClient = client,
            blobStore = fetcherStore,
            channelKeys = SyncChannelKeys(crypto) { fetcher },
            noteActivity = {}
        )
        return Triple(transfer, client, scope)
    }

    @Test
    fun fetch_addressesOneRefPerRequest() = runTest {
        val crypto = SyncCrypto()
        val fetcher = SyncIdentity.create("Fetcher", crypto, "fetcher-dev")
        val holder = SyncIdentity.create("Holder", crypto, "holder-dev")
        val blobs = LinkedHashMap<String, ByteArray>()
        val refs = (1..50).map { i ->
            val bytes = ByteArray(32 * 1024) { ((it + i) % 251).toByte() }
            val sha = Hashing.sha256Hex(bytes)
            blobs[sha] = bytes
            SyncImageRef(sha, bytes.size.toLong())
        }
        val store = InMemoryBlobStore()
        val seenRefCounts = ArrayList<Int>()
        val (transfer, client, scope) = newTransfer(fetcher, holder, blobs, store, seenRefCounts)
        try {
            transfer.fetchMissingBlobs(holder.deviceId, "127.0.0.1:9999", refs, holder.publicKeyBytes)
            assertEquals(50, store.map.size, "Every missing blob must arrive")
            assertTrue(seenRefCounts.isNotEmpty(), "The transfer must issue requests")
            assertTrue(
                seenRefCounts.all { it <= 1 },
                "Each chunk request must address exactly its own ref, but requests carried up to ${seenRefCounts.maxOrNull()} refs"
            )
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun fetch_boundsOneCallToATractablePrefix() = runTest {
        val crypto = SyncCrypto()
        val fetcher = SyncIdentity.create("Fetcher", crypto, "fetcher-dev")
        val holder = SyncIdentity.create("Holder", crypto, "holder-dev")
        val blobs = LinkedHashMap<String, ByteArray>()
        val refs = (1..250).map { i ->
            val bytes = ByteArray(1024) { ((it + i) % 251).toByte() }
            val sha = Hashing.sha256Hex(bytes)
            blobs[sha] = bytes
            SyncImageRef(sha, bytes.size.toLong())
        }
        val store = InMemoryBlobStore()
        val seenRefCounts = ArrayList<Int>()
        val (transfer, client, scope) = newTransfer(fetcher, holder, blobs, store, seenRefCounts)
        try {
            transfer.fetchMissingBlobs(holder.deviceId, "127.0.0.1:9999", refs, holder.publicKeyBytes)
            assertTrue(
                store.map.size <= 100,
                "One call must not drain an unbounded backlog, but it fetched ${store.map.size} refs"
            )
            transfer.fetchMissingBlobs(holder.deviceId, "127.0.0.1:9999", refs, holder.publicKeyBytes)
            assertTrue(
                store.map.size > 100,
                "Repeated calls must make progress toward the full set, but still hold ${store.map.size}"
            )
        } finally {
            client.close()
            scope.cancel()
        }
    }
}

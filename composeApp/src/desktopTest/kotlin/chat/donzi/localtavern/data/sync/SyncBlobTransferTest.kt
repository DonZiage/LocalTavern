package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.utils.Hashing
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

// Hostile-responder hardening for the /blob/fetch loop: a paired peer that
// answers every chunk request with EMPTY data while claiming hasMore=true
// used to drive fetchMissingBlobs forever (no chunk ever advanced the size or
// count guards). The fetch must now terminate after the first violation.
class SyncBlobTransferTest {

    private class InMemoryBlobStore : BlobStore {
        private val map = mutableMapOf<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) {
            map[key] = bytes
        }

        override suspend fun read(key: String): ByteArray? = map[key]

        override suspend fun delete(key: String) {
            map.remove(key)
        }

        override suspend fun listKeys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun emptyChunkWithHasMore_abortsInsteadOfLooping() = runTest {
        val crypto = SyncCrypto()
        val (clientPrivate, clientPublic) = crypto.generateKeyPair()
        val (responderPrivate, responderPublic) = crypto.generateKeyPair()

        val clientIdentity = SyncIdentity(
            deviceId = "client-device", deviceName = "Client",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(clientPrivate),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(clientPublic)
        )
        val responderIdentity = SyncIdentity(
            deviceId = "responder-device", deviceName = "Responder",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(responderPrivate),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(responderPublic)
        )
        val responderChannelKeys = SyncChannelKeys(crypto) { responderIdentity }

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        var requestCount = 0

        // The lying responder: authentic, but every chunk is empty with
        // hasMore=true (a protocol violation a real server never produces —
        // offset past the end yields hasMore=false).
        val engine = MockEngine { request ->
            requestCount++
            val requestBody = request.body.toByteArray().decodeToString()
            val wireRequest = json.decodeFromString(BlobFetchRequest.serializer(), requestBody)
            val peerEphemeral = decodeBase64(wireRequest.ephemeralPublicKey)
            val requestKey = responderChannelKeys.inboundChannelKey(clientPublic, peerEphemeral)
            val payload = json.decodeFromString(
                BlobFetchPayload.serializer(),
                crypto.decrypt(
                    requestKey,
                    aad(from = wireRequest.fromDeviceId, to = responderIdentity.deviceId, exchangeId = wireRequest.exchangeId),
                    decodeBase64(wireRequest.payload)
                ).decodeToString()
            )
            val lyingResult = BlobFetchResult(
                refIndex = payload.refIndex,
                offset = payload.offset,
                total = 0,
                data = "",
                hasMore = true,
                missing = emptyList()
            )
            val outbound = responderChannelKeys.outboundChannelKey(clientPublic)
            val responsePayload = kotlin.io.encoding.Base64.encode(
                crypto.encrypt(
                    outbound.key,
                    aad(from = responderIdentity.deviceId, to = wireRequest.fromDeviceId, exchangeId = wireRequest.exchangeId),
                    json.encodeToString(BlobFetchResult.serializer(), lyingResult).encodeToByteArray()
                )
            )
            respond(
                content = json.encodeToString(BlobFetchResponse.serializer(), BlobFetchResponse(
                    ok = true,
                    exchangeId = wireRequest.exchangeId,
                    payload = responsePayload,
                    ephemeralPublicKey = kotlin.io.encoding.Base64.encode(outbound.ephemeralPublicKey)
                )),
                status = HttpStatusCode.OK,
                headers = headersOf(io.ktor.http.HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val db = LocalTavernDB(driver)
        val repository = SyncRepository(db, clientIdentity)
        val clientChannelKeys = SyncChannelKeys(crypto) { clientIdentity }

        val transfer = SyncBlobTransfer(
            crypto = crypto,
            repository = repository,
            state = MutableStateFlow(SyncUiState()),
            identityProvider = { clientIdentity },
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            },
            blobStore = InMemoryBlobStore(),
            channelKeys = clientChannelKeys,
            noteActivity = {}
        )

        val refHash = Hashing.sha256Hex(ByteArray(1) { 7 })
        transfer.fetchMissingBlobs(
            peerId = responderIdentity.deviceId,
            address = "127.0.0.1:1",
            refs = listOf(SyncImageRef(refHash, size = 100L)),
            peerPublicKey = responderPublic
        )

        // One request, then the violation is detected and the remaining refs
        // are abandoned. The old code looped here indefinitely.
        assertEquals(1, requestCount, "An empty chunk with hasMore=true must abort after the first request")
    }
}

package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import dev.whyoleg.cryptography.random.CryptographyRandom

// Red-team: pairing must reject deviceIds that exchange/blob-fetch would reject.
class SyncPairingDeviceIdRedTeamTest {

    private class MemBlob : BlobStore {
        private val m = mutableMapOf<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) { m[key] = bytes }
        override suspend fun read(key: String): ByteArray? = m[key]
        override suspend fun delete(key: String) { m.remove(key) }
        override suspend fun listKeys(): Set<String> = m.keys.toSet()
    }

    private class MemIdStore : SyncIdentityStore {
        private var b: ByteArray? = null
        override fun load(): ByteArray? = b
        override fun save(bytes: ByteArray) { b = bytes }
    }

    @Test
    fun invalidDeviceId_isRejected() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val crypto = SyncCrypto()
        val identity = SyncIdentity.create(deviceName = "Host", crypto = crypto)
        val repo = SyncRepository(db, identity, blobStore = MemBlob())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = 23981
        val service = SyncService(
            initialIdentity = identity, crypto = crypto, repository = repo,
            identityStore = MemIdStore(),
            httpClient = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            },
            scope = scope, port = port, blobStore = MemBlob()
        )
        try {
            service.startPairing()
            val pin = service.state.value.pairingPin ?: error("no pin")
            // Attacker device with an id that isValidDeviceId rejects.
            val badId = "ABC/DEF|UPPER SPACE"
            val attackerCrypto = SyncCrypto()
            val attackerIdentity = SyncIdentity.create(deviceName = "Evil", crypto = attackerCrypto).copy(deviceId = badId)
            val nonce = ByteArray(16).also { CryptographyRandom.Default.nextBytes(it) }
            val proof = attackerCrypto.pairingProof(pin, badId, attackerIdentity.publicKeyBytes, nonce)
            val client = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val response: PairResponse = client.post("http://127.0.0.1:$port/pair") {
                contentType(ContentType.Application.Json)
                setBody(
                    PairRequest(
                        deviceId = badId,
                        deviceName = "Evil",
                        publicKey = encodeBase64(attackerIdentity.publicKeyBytes),
                        nonce = encodeBase64(nonce),
                        pinProof = encodeBase64(proof)
                    )
                )
            }.body()
            assertFalse(response.ok, "pairing must reject invalid device id '$badId', got: $response")
        } finally {
            service.stopServer()
            scope.cancel()
        }
    }
}

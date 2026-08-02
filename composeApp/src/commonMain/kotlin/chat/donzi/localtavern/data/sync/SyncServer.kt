package chat.donzi.localtavern.data.sync

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Embedded HTTP server each device runs while sync is enabled. Endpoints:
//   GET  /hello     -> device identity (for discovery)
//   POST /pair      -> PIN-authenticated pairing, returns both keys
//   POST /exchange  -> encrypted bidirectional sync exchange
class SyncServer(
    private val port: Int,
    private val hello: () -> HelloResponse,
    private val onPair: suspend (PairRequest, remoteHost: String?) -> PairResponse,
    private val onExchange: suspend (fromDeviceId: String, payload: String, ephemeralPublicKey: String) -> ExchangeResponse
) {
    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            routing {
                get("/hello") { call.respond(hello()) }
                post("/pair") {
                    val request = call.receive<PairRequest>()
                    val remoteHost = call.request.origin.remoteHost
                    call.respond(onPair(request, remoteHost))
                }
                post("/exchange") {
                    val request = call.receive<ExchangeRequest>()
                    val response = onExchange(request.fromDeviceId, request.payload, request.ephemeralPublicKey)
                    if (response.ok) {
                        call.respond(response)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, response)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        server = null
    }
}

// Wire body of the /exchange endpoint: an encrypted envelope plus the
// sender's per-exchange ephemeral X25519 public key. The ephemeral key rides
// OUTSIDE the ciphertext (the recipient needs it before it can derive the
// channel key); it is public, and the ciphertext's AEAD tag still proves the
// sender holds the paired static secret, so a swapped key cannot be used to
// forge an exchange.
@Serializable
data class ExchangeRequest(
    val fromDeviceId: String,
    val payload: String,
    val ephemeralPublicKey: String = ""
)

@Serializable
data class ExchangeResponse(
    val ok: Boolean,
    val message: String = "",
    val payload: String? = null,
    val ephemeralPublicKey: String = ""
)

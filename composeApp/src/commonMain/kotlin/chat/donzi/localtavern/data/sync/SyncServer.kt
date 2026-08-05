package chat.donzi.localtavern.data.sync

import io.ktor.http.HttpHeaders
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
//   GET  /hello     -> device identity (for discovery); a receiver entering
//                      the PIN step calls it with ?announce=<name> so the
//                      host's screen can switch from QR to PIN as soon as a
//                      receiver has connected
//   POST /pair      -> PIN-authenticated pairing, returns both keys
//   POST /exchange  -> encrypted bidirectional sync exchange
//   POST /blob/fetch-> encrypted chunked pull of message-image blobs
class SyncServer(
    private val port: Int,
    private val hello: (announcedName: String?) -> HelloResponse,
    private val onPair: suspend (PairRequest, remoteHost: String?) -> PairResponse,
    private val onExchange: suspend (fromDeviceId: String, payload: String, ephemeralPublicKey: String, exchangeId: String) -> ExchangeResponse,
    private val onBlobFetch: suspend (fromDeviceId: String, payload: String, ephemeralPublicKey: String, exchangeId: String) -> BlobFetchResponse
) {
    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    // Upper bound on a request body. Modern peers exchange deltas in small
    // bounded batches (see DELTA_BUDGET_BYTES), so nothing legitimate needs
    // more than this; the bound exists for legacy peers that still ship a
    // whole library in one envelope. Without the cap a paired peer could
    // stream a body whose decode (JSON -> base64 -> decrypt -> rows) would
    // hold several times its size in memory and exhaust the device — the
    // pre-batching crash. Oversized bodies are rejected up front (413)
    // instead.
    private companion object {
        const val MAX_REQUEST_BYTES = MAX_SYNC_BODY_BYTES
    }

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
                get("/hello") { call.respond(hello(call.request.queryParameters["announce"])) }
                post("/pair") {
                    if (!acceptsBodySize(call)) {
                        call.respond(HttpStatusCode.PayloadTooLarge, PairResponse(ok = false, message = "Request too large."))
                        return@post
                    }
                    val request = call.receive<PairRequest>()
                    val remoteHost = call.request.origin.remoteHost
                    call.respond(onPair(request, remoteHost))
                }
                post("/exchange") {
                    if (!acceptsBodySize(call)) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ExchangeResponse(ok = false, message = "Request too large."))
                        return@post
                    }
                    val request = call.receive<ExchangeRequest>()
                    val response = onExchange(request.fromDeviceId, request.payload, request.ephemeralPublicKey, request.exchangeId)
                    if (response.ok) {
                        call.respond(response)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, response)
                    }
                }
                post("/blob/fetch") {
                    if (!acceptsBodySize(call)) {
                        call.respond(HttpStatusCode.PayloadTooLarge, BlobFetchResponse(ok = false, message = "Request too large."))
                        return@post
                    }
                    val request = call.receive<BlobFetchRequest>()
                    val response = onBlobFetch(request.fromDeviceId, request.payload, request.ephemeralPublicKey, request.exchangeId)
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

    // Early rejection of oversized bodies via Content-Length (the Ktor client
    // always sends it for JSON bodies). A body WITHOUT a declared length
    // (chunked transfer encoding, raw streaming) cannot be size-checked up
    // front: call.receive() would buffer it in full before the handler runs,
    // so accepting it would let any LAN peer stream an unbounded body past
    // the cap and exhaust the device. Such requests are rejected outright.
    private fun acceptsBodySize(call: io.ktor.server.application.ApplicationCall): Boolean {
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return false
        return length in 0..MAX_REQUEST_BYTES
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
    val exchangeId: String = "",
    val payload: String,
    val ephemeralPublicKey: String = ""
)

@Serializable
data class ExchangeResponse(
    val ok: Boolean,
    val message: String = "",
    val exchangeId: String = "",
    val payload: String? = null,
    val ephemeralPublicKey: String = ""
)

package chat.donzi.localtavern.data.sync

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Red-team test: the sync server's oversized-body cap (MAX_SYNC_BODY_BYTES)
// is enforced ONLY via the Content-Length header (SyncServer.acceptsBodySize).
// A LAN attacker does not need Content-Length: HTTP/1.1 chunked transfer
// encoding delivers a body of any size without declaring one, and
// call.receive<ExchangeRequest>() then buffers the WHOLE body in memory
// before the handler sees a byte. An endless chunked stream therefore grows
// the server process's memory without bound — the same "stream a body whose
// decode would exhaust the device" crash the cap was written to prevent.
class SyncServerChunkedBodyBypassRedTeamTest {

    private companion object {
        var portCounter = 0
    }

    private fun freePort(): Int {
        // Deterministic per-test ports (matching SyncProtocolTest).
        val base = 24100 + portCounter++
        return base
    }

    @Test
    fun chunkedBody_over16MiB_isRejectedBeforeTheHandlerRuns() = runBlocking {
        val port = freePort()
        var handlerInvokedWithBody = false

        val server = SyncServer(
            port = port,
            hello = { HelloResponse(deviceId = "me", deviceName = "me") },
            onPair = { _, _ -> PairResponse(ok = false, message = "n/a") },
            onExchange = { _, payload, _, _ ->
                handlerInvokedWithBody = true
                ExchangeResponse(ok = false, message = "body accepted")
            },
            onBlobFetch = { _, _, _, _ -> BlobFetchResponse(ok = false, message = "n/a") }
        )
        server.start()
        try {
            // A valid ExchangeRequest whose payload field alone exceeds the
            // 16 MiB cap, streamed with chunked transfer encoding so no
            // Content-Length header exists for acceptsBodySize to reject.
            val body = buildString {
                append("{\"fromDeviceId\":\"attacker\",\"exchangeId\":\"e1\",\"payload\":\"")
                repeat(MAX_SYNC_BODY_BYTES.toInt() + 1024 * 1024) { append("A") }
                append("\"}")
            }
            val response = sendChunkedPost(port, "/exchange", body)

            if (handlerInvokedWithBody) {
                // The cap was bypassed: the server buffered and parsed a body
                // far above MAX_SYNC_BODY_BYTES. An attacker streaming an
                // endless body would grow this buffer without bound (OOM).
                assertEquals(
                    HttpStatusCode.PayloadTooLarge.value,
                    response.first,
                    "Size cap bypassed: chunked body of ${body.length / 1024 / 1024} MiB " +
                        "was buffered and delivered to the exchange handler instead of " +
                        "being rejected with 413."
                )
            }
            assertFalse(
                handlerInvokedWithBody,
                "handler must not be invoked for a body exceeding MAX_SYNC_BODY_BYTES"
            )
        } finally {
            server.stop()
        }
    }

    private fun sendChunkedPost(port: Int, path: String, body: String): Pair<Int, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 10_000
        try {
            val out = socket.getOutputStream()
            val request = StringBuilder()
            request.append("POST $path HTTP/1.1\r\n")
            request.append("Host: 127.0.0.1:$port\r\n")
            request.append("Content-Type: application/json\r\n")
            request.append("Transfer-Encoding: chunked\r\n")
            request.append("Connection: close\r\n")
            request.append("\r\n")
            out.write(request.toString().encodeToByteArray())

            // 1 MiB chunks until the whole body is streamed.
            val chunkSize = 1024 * 1024
            val bytes = body.encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                val header = "${len.toString(16)}\r\n".encodeToByteArray()
                out.write(header)
                out.write(bytes, offset, len)
                out.write("\r\n".encodeToByteArray())
                offset += len
            }
            out.write("0\r\n\r\n".encodeToByteArray())
            out.flush()

            val response = StringBuilder()
            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                response.append(String(buffer, 0, read))
            }
            val raw = response.toString()
            val statusLine = raw.substringBefore("\r\n")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            return code to raw
        } finally {
            socket.close()
        }
    }
}

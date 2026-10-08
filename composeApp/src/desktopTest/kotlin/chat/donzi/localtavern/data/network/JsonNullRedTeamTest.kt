package chat.donzi.localtavern.data.network

import chat.donzi.localtavern.utils.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Red-team: explicit JSON null in deltas must not become the literal "null" string.
class JsonNullRedTeamTest {

    private fun clientReturning(body: String, contentType: String = "text/event-stream"): ChatClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                addHandler {
                    respond(
                        content = ByteReadChannel(body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, contentType)
                    )
                }
            }
        )
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return ChatClient(http)
    }

    @Test
    fun streamExplicitNullContent_emitsNoToken() = runTest {
        val client = clientReturning(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\ndata: [DONE]\n\n"
        )
        val chunks = client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(ChatMessage("user", "hi"))
        ).toList()
        assertTrue(chunks.none { it.content == "null" }, "explicit null must not emit literal 'null', got: $chunks")
        assertTrue(chunks.none { it.content != null }, "role-only chunk must emit no content tokens")
    }

    @Test
    fun streamExplicitNullReasoning_emitsNoToken() = runTest {
        val client = clientReturning(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":null}}]}\n\ndata: [DONE]\n\n"
        )
        val chunks = client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(ChatMessage("user", "hi"))
        ).toList()
        assertTrue(chunks.none { it.reasoning == "null" }, "explicit null reasoning must not emit literal 'null'")
    }

    @Test
    fun nonStreamNullContent_doesNotReturnNullString() = runTest {
        val client = clientReturning(
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            contentType = "application/json"
        )
        val response = client.sendChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(ChatMessage("user", "hi"))
        )
        assertTrue(response.text != "null", "null content must not become literal 'null', got: ${response.text}")
    }
}

package chat.donzi.localtavern.network

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class ChatClient(private val httpClient: HttpClient) {
    suspend fun sendChatRequest(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val response = httpClient.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            })
        }
        return response.bodyAsText()
    }
}
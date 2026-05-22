package chat.donzi.localtavern.data.network

import chat.donzi.localtavern.utils.ChatMessage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ModelListResponse(
    val data: List<ModelData>
)

@Serializable
data class ModelData(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String
)

class ChatClient(private val httpClient: HttpClient) {
    private fun JsonObjectBuilder.putChatMessages(isChatCompletion: Boolean, messages: List<ChatMessage>) {
        if (isChatCompletion) {
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
        } else {
            val promptBuilder = StringBuilder()
            messages.forEach { msg ->
                when (msg.role) {
                    "system" -> promptBuilder.append(msg.content).append("\n\n")
                    "user" -> promptBuilder.append("User: ").append(msg.content).append("\n")
                    "assistant", "character" -> promptBuilder.append("Character: ").append(msg.content).append("\n")
                    else -> promptBuilder.append(msg.content).append("\n")
                }
            }
            promptBuilder.append("Character:")
            put("prompt", promptBuilder.toString())
        }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelInfo> {
        return try {
            val response = httpClient.get("$baseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (response.status == HttpStatusCode.OK) {
                val body: ModelListResponse = response.body()
                body.data.map {
                    val parts = it.id.split("/")
                    if (parts.size > 1) {
                        ModelInfo(
                            id = it.id,
                            provider = parts[0],
                            displayName = parts.subList(1, parts.size).joinToString("/")
                        )
                    } else {
                        ModelInfo(
                            id = it.id,
                            provider = it.ownedBy ?: "Unknown",
                            displayName = it.id
                        )
                    }
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun checkStatus(baseUrl: String, apiKey: String): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            response.status == HttpStatusCode.OK
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("unused")
    suspend fun sendChatRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        isChatCompletion: Boolean = true
    ): String {
        val endpoint = if (isChatCompletion) "$baseUrl/chat/completions" else "$baseUrl/completions"
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                putChatMessages(isChatCompletion, messages)
            })
        }

        if (response.status != HttpStatusCode.OK) {
            return "Error: ${response.status.description}"
        }

        val json = Json { ignoreUnknownKeys = true }
        val bodyText = response.bodyAsText()
        return try {
            val element = json.parseToJsonElement(bodyText)
            if (isChatCompletion) {
                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: bodyText
            } else {
                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: bodyText
            }
        } catch (_: Exception) {
            bodyText
        }
    }

    fun streamChatRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        isChatCompletion: Boolean = true
    ): Flow<String> = flow {
        val endpoint = if (isChatCompletion) "$baseUrl/chat/completions" else "$baseUrl/completions"

        try {
            httpClient.preparePost(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    putChatMessages(isChatCompletion, messages)
                })
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    emit("Error: ${response.status.description}")
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    @Suppress("DEPRECATION")
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        if (data == "[DONE]") break

                        try {
                            val json = Json { ignoreUnknownKeys = true }
                            val element = json.parseToJsonElement(data)
                            val content = if (isChatCompletion) {
                                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
                            } else {
                                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
                            }
                            if (content != null) {
                                emit(content)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
}
package chat.donzi.localtavern.data.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ModelListResponse(
    val data: List<ModelData>
)

@Serializable
data class ModelData(
    val id: String,
    val owned_by: String? = null
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String
)

class ChatClient(private val httpClient: HttpClient) {
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
                            provider = it.owned_by ?: "Unknown",
                            displayName = it.id
                        )
                    }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun checkStatus(baseUrl: String, apiKey: String): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendChatRequest(baseUrl: String, apiKey: String, model: String, prompt: String, isChatCompletion: Boolean = true): String {
        val endpoint = if (isChatCompletion) "$baseUrl/chat/completions" else "$baseUrl/completions"
        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                if (isChatCompletion) {
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                } else {
                    put("prompt", prompt)
                }
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
        } catch (e: Exception) {
            bodyText
        }
    }

    fun streamChatRequest(baseUrl: String, apiKey: String, model: String, prompt: String, isChatCompletion: Boolean = true): Flow<String> = flow {
        val endpoint = if (isChatCompletion) "$baseUrl/chat/completions" else "$baseUrl/completions"
        
        try {
            httpClient.preparePost(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    if (isChatCompletion) {
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", prompt)
                            })
                        })
                    } else {
                        put("prompt", prompt)
                    }
                })
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    emit("Error: ${response.status.description}")
                    return@execute
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
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
                        } catch (e: Exception) {
                            // Skip invalid lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
}

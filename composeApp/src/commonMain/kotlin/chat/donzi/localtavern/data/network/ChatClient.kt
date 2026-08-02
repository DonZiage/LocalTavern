package chat.donzi.localtavern.data.network

import chat.donzi.localtavern.utils.ChatMessage
import chat.donzi.localtavern.utils.ImageAttachment
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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

data class GenerationParams(
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val topK: Long = 0,
    val presencePenalty: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val maxTokens: Long = 0
)

open class ApiRequestException(message: String) : Exception(message)

// The API answered (200) but delivered no content at all. Unlike a transport
// failure it must not be retried through the non-streaming fallback, because
// the same empty result would come back again and mask the error.
private class EmptyResponseException : ApiRequestException("Empty response from API.")

enum class ApiStyle { OpenAI, Anthropic }

fun apiStyleForProvider(provider: String?): ApiStyle =
    if (provider?.trim().equals("Anthropic", ignoreCase = true)) ApiStyle.Anthropic else ApiStyle.OpenAI

class ChatClient(private val httpClient: HttpClient) {

    private fun HttpRequestBuilder.putAuthHeaders(apiStyle: ApiStyle, apiKey: String) {
        if (apiStyle == ApiStyle.Anthropic) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
        } else {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    private fun HttpRequestBuilder.applySocketTimeout(timeoutSeconds: Long) {
        // Tie the socket idle timeout to the connection's response timeout so
        // long silent "thinking" phases (reasoning models) are not killed by a
        // fixed idle timeout. <= 0 means no timeout, matching the unlimited
        // response deadline semantics in ChatController.
        timeout {
            socketTimeoutMillis = if (timeoutSeconds <= 0) {
                HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            } else {
                timeoutSeconds * 1000
            }
        }
    }

    private fun endpointFor(baseUrl: String, apiStyle: ApiStyle, isChatCompletion: Boolean): String = when {
        apiStyle == ApiStyle.Anthropic -> "${normalizeBaseUrl(baseUrl)}/messages"
        isChatCompletion -> "${normalizeBaseUrl(baseUrl)}/chat/completions"
        else -> "${normalizeBaseUrl(baseUrl)}/completions"
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trimEnd('/')

    private data class AnthropicTurn(
        val role: String,
        val text: String,
        val images: List<ImageAttachment>
    )

    private fun JsonObjectBuilder.putAnthropicMessages(messages: List<ChatMessage>) {
        val systemPrompt = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)

        put("messages", buildJsonArray {
            val turns = mutableListOf<AnthropicTurn>()
            messages.filter { it.role != "system" }.forEach { msg ->
                val role = if (msg.role == "assistant" || msg.role == "character") "assistant" else "user"
                val last = turns.lastOrNull()
                if (last != null && last.role == role) {
                    turns[turns.size - 1] = AnthropicTurn(
                        role = role,
                        text = last.text + "\n\n" + msg.content,
                        images = last.images + msg.images
                    )
                } else {
                    turns.add(AnthropicTurn(role, msg.content, msg.images))
                }
            }

            // Anthropic requires the first message to have the "user" role, but
            // chats start with the character greeting (assistant). Prepend an
            // empty user turn so the request is accepted.
            if (turns.isNotEmpty() && turns.first().role == "assistant") {
                turns.add(0, AnthropicTurn("user", "", emptyList()))
            }

            turns.forEach { turn ->
                add(buildJsonObject {
                    put("role", turn.role)
                    if (turn.images.isEmpty()) {
                        put("content", turn.text)
                    } else {
                        put("content", buildJsonArray {
                            if (turn.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", turn.text)
                                })
                            }
                            turn.images.forEach { image ->
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", image.mimeType)
                                        put("data", image.base64)
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
    }

    private fun JsonObjectBuilder.putAnthropicParams(params: GenerationParams) {
        // Anthropic requires max_tokens. A responseLimit of 0 means
        // "unlimited" in the UI; use a generous ceiling instead of a tiny
        // default so long outputs are not silently truncated at 1024 tokens.
        put("max_tokens", params.maxTokens.takeIf { it > 0 } ?: 8192L)
        put("temperature", params.temperature)
        put("top_p", params.topP)
        if (params.topK > 0) put("top_k", params.topK)
    }

    private fun JsonObjectBuilder.putChatMessages(
        isChatCompletion: Boolean,
        messages: List<ChatMessage>,
        ignoreImages: Boolean = false
    ) {
        if (isChatCompletion) {
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)

                        if (!ignoreImages && msg.images.isNotEmpty()) {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                                msg.images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", "data:${img.mimeType};base64,${img.base64}")
                                        })
                                    })
                                }
                            })
                        } else {
                            put("content", msg.content)
                        }
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

    private fun JsonObjectBuilder.putGenerationParams(params: GenerationParams) {
        put("temperature", params.temperature)
        put("top_p", params.topP)
        if (params.topK > 0) put("top_k", params.topK)
        put("presence_penalty", params.presencePenalty)
        put("frequency_penalty", params.frequencyPenalty)
        if (params.maxTokens > 0) put("max_tokens", params.maxTokens)
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String, provider: String? = null): List<ModelInfo> {
        val apiStyle = apiStyleForProvider(provider)
        return try {
            val response = httpClient.get("${normalizeBaseUrl(baseUrl)}/models") {
                putAuthHeaders(apiStyle, apiKey)
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun checkStatus(baseUrl: String, apiKey: String, provider: String? = null): Boolean {
        val apiStyle = apiStyleForProvider(provider)
        return try {
            val response = httpClient.get("${normalizeBaseUrl(baseUrl)}/models") {
                putAuthHeaders(apiStyle, apiKey)
            }
            response.status == HttpStatusCode.OK
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendChatRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        isChatCompletion: Boolean = true,
        ignoreImages: Boolean = false,
        params: GenerationParams = GenerationParams(),
        provider: String? = null,
        timeoutSeconds: Long = 0
    ): String {
        val apiStyle = apiStyleForProvider(provider)
        val endpoint = endpointFor(baseUrl, apiStyle, isChatCompletion)
        val hasImages = messages.any { it.images.isNotEmpty() }

        val response = httpClient.post(endpoint) {
            putAuthHeaders(apiStyle, apiKey)
            contentType(ContentType.Application.Json)
            applySocketTimeout(timeoutSeconds)
            setBody(buildJsonObject {
                put("model", model)
                if (apiStyle == ApiStyle.Anthropic) {
                    putAnthropicMessages(messages)
                    putAnthropicParams(params)
                } else {
                    putChatMessages(isChatCompletion, messages, ignoreImages = ignoreImages)
                    putGenerationParams(params)
                }
            })
        }

        if (response.status != HttpStatusCode.OK) {
            if (hasImages && !ignoreImages && isChatCompletion && apiStyle != ApiStyle.Anthropic) {
                return sendChatRequest(baseUrl, apiKey, model, messages, isChatCompletion, ignoreImages = true, params = params, provider = provider, timeoutSeconds = timeoutSeconds)
            }
            throw ApiRequestException(extractErrorMessage(response))
        }

        val json = Json { ignoreUnknownKeys = true }
        val bodyText = response.bodyAsText()
        return try {
            val element = json.parseToJsonElement(bodyText)
            if (apiStyle == ApiStyle.Anthropic) {
                element.jsonObject["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: bodyText
            } else if (isChatCompletion) {
                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: bodyText
            } else {
                element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: bodyText
            }
        } catch (_: Exception) {
            bodyText
        }
    }

    private suspend fun extractErrorMessage(response: HttpResponse): String {
        return try {
            val text = response.bodyAsText()
            val element = Json { ignoreUnknownKeys = true }.parseToJsonElement(text)
            val apiMessage = element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            if (!apiMessage.isNullOrBlank()) {
                "API error: $apiMessage"
            } else if (text.isNotBlank()) {
                "API error (${response.status.description}): ${text.take(200)}"
            } else {
                "API error: ${response.status.description}"
            }
        } catch (_: Exception) {
            "API error: ${response.status.description}"
        }
    }

    fun streamChatRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        isChatCompletion: Boolean = true,
        params: GenerationParams = GenerationParams(),
        provider: String? = null,
        timeoutSeconds: Long = 0
    ): Flow<String> = flow {
        val apiStyle = apiStyleForProvider(provider)
        val endpoint = endpointFor(baseUrl, apiStyle, isChatCompletion)
        val hasImages = messages.any { it.images.isNotEmpty() }

        var streamingSuccess = false
        var lastUsedIgnoreImages = false
        var fallbackToNonStreaming = false

        try {
            var failedWithImages = false

            httpClient.preparePost(endpoint) {
                putAuthHeaders(apiStyle, apiKey)
                contentType(ContentType.Application.Json)
                applySocketTimeout(timeoutSeconds)
                setBody(buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    if (apiStyle == ApiStyle.Anthropic) {
                        putAnthropicMessages(messages)
                        putAnthropicParams(params)
                    } else {
                        putChatMessages(isChatCompletion, messages, ignoreImages = false)
                        putGenerationParams(params)
                    }
                })
            }.execute { response ->
                val contentType = response.contentType()
                if (response.status != HttpStatusCode.OK) {
                    if (hasImages && isChatCompletion && apiStyle != ApiStyle.Anthropic) {
                        failedWithImages = true
                    } else {
                        fallbackToNonStreaming = true
                    }
                    return@execute
                }

                if (contentType?.match(ContentType.Application.Json) == true) {
                    fallbackToNonStreaming = true
                    return@execute
                }

                streamingSuccess = processResponseStream(response, apiStyle, isChatCompletion)
            }

            if (failedWithImages && !streamingSuccess) {
                lastUsedIgnoreImages = true
                httpClient.preparePost(endpoint) {
                    putAuthHeaders(apiStyle, apiKey)
                    contentType(ContentType.Application.Json)
                    applySocketTimeout(timeoutSeconds)
                    setBody(buildJsonObject {
                        put("model", model)
                        put("stream", true)
                        putChatMessages(isChatCompletion, messages, ignoreImages = true)
                        putGenerationParams(params)
                    })
                }.execute { response ->
                    val contentType = response.contentType()
                    if (response.status != HttpStatusCode.OK || contentType?.match(ContentType.Application.Json) == true) {
                        fallbackToNonStreaming = true
                        return@execute
                    }

                    streamingSuccess = processResponseStream(response, apiStyle, isChatCompletion)
                }
            }

            if (fallbackToNonStreaming && !streamingSuccess) {
                val nonStreamedResponse = sendChatRequest(
                    baseUrl = baseUrl, apiKey = apiKey, model = model,
                    messages = messages, isChatCompletion = isChatCompletion,
                    ignoreImages = lastUsedIgnoreImages, params = params, provider = provider,
                    timeoutSeconds = timeoutSeconds
                )
                emit(nonStreamedResponse)
                streamingSuccess = true
            }

            // A 200 whose body carried no tokens and no [DONE]/message_stop
            // (empty body, plain-text proxy response, truncated stream) would
            // otherwise complete silently and the controller would delete the
            // response placeholder with no error surfaced to the user.
            if (!streamingSuccess) {
                throw EmptyResponseException()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: EmptyResponseException) {
            // No retry: the request was answered, it just carried no content.
            throw e
        } catch (e: Exception) {
            if (!streamingSuccess) {
                try {
                    val nonStreamedResponse = sendChatRequest(
                        baseUrl = baseUrl, apiKey = apiKey, model = model,
                        messages = messages, isChatCompletion = isChatCompletion,
                        ignoreImages = lastUsedIgnoreImages, params = params, provider = provider,
                        timeoutSeconds = timeoutSeconds
                    )
                    emit(nonStreamedResponse)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private suspend fun FlowCollector<String>.processResponseStream(
        response: HttpResponse,
        apiStyle: ApiStyle,
        isChatCompletion: Boolean
    ): Boolean {
        var success = false
        var done = false
        val channel: ByteReadChannel = response.bodyAsChannel()
        while (!channel.isClosedForRead && !done) {
            currentCoroutineContext().ensureActive()
            @Suppress("DEPRECATION")
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.substring(6)
                if (data == "[DONE]") {
                    success = true
                    break
                }

                var errorMessage: String? = null
                try {
                    val json = Json { ignoreUnknownKeys = true }
                    val element = json.parseToJsonElement(data)

                    if (apiStyle == ApiStyle.Anthropic) {
                        val eventType = element.jsonObject["type"]?.jsonPrimitive?.content
                        when (eventType) {
                            "content_block_delta" -> {
                                val text = element.jsonObject["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                                if (text != null) {
                                    success = true
                                    emit(text)
                                }
                            }
                            "message_stop" -> {
                                success = true
                                done = true
                            }
                            "error" -> {
                                errorMessage = element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                throw ApiRequestException("API error: ${errorMessage ?: "Unknown error"}")
                            }
                        }
                    } else {
                        errorMessage = element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        if (!errorMessage.isNullOrBlank()) {
                            throw ApiRequestException("API error: $errorMessage")
                        }
                        val content = if (isChatCompletion) {
                            element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
                        } else {
                            element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
                        }
                        if (content != null) {
                            success = true
                            emit(content)
                        }
                    }
                } catch (e: ApiRequestException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
        return success
    }
}
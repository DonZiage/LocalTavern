package chat.donzi.localtavern.data.network

import chat.donzi.localtavern.utils.ChatMessage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

class ChatClient(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun fetchModels(baseUrl: String, apiKey: String, provider: String? = null): List<ModelInfo> {
        val apiStyle = apiStyleForProvider(provider)
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/models") {
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

    suspend fun checkStatus(baseUrl: String, apiKey: String, provider: String? = null): Boolean =
        probeConnection(baseUrl, apiKey, provider).outcome == ConnectionProbe.Ok

    suspend fun probeConnection(baseUrl: String, apiKey: String, provider: String? = null): ProbeResult {
        val apiStyle = apiStyleForProvider(provider)
        return try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/models") {
                putAuthHeaders(apiStyle, apiKey)
            }
            when {
                response.status.value in 200..299 -> ProbeResult(ConnectionProbe.Ok)
                response.status.value == 401 || response.status.value == 403 ->
                    ProbeResult(ConnectionProbe.AuthFailed, detail = "HTTP ${response.status.value} ${response.status.description}")
                else -> ProbeResult(ConnectionProbe.Unreachable, detail = "HTTP ${response.status.value} ${response.status.description}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Surface the transport reason (DNS failure, connection refused,
            // timeout, ...) so a misconfigured URL is diagnosable at a glance.
            ProbeResult(ConnectionProbe.Unreachable, detail = e.message?.take(160))
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
    ): ChatResponse {
        val apiStyle = apiStyleForProvider(provider)
        val endpoint = endpointFor(baseUrl, apiStyle, isChatCompletion)
        val hasImages = messages.any { it.images.isNotEmpty() }
        val thinkingEnabled = params.thinkingBudgetTokens != null

        val response = httpClient.post(endpoint) {
            putAuthHeaders(apiStyle, apiKey, thinkingEnabled = thinkingEnabled, model = model)
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

        val bodyText = response.bodyAsText()
        return try {
            val element = json.parseToJsonElement(bodyText)
            if (apiStyle == ApiStyle.Anthropic) {
                // Content blocks may interleave "thinking" and "text" blocks.
                val blocks = element.jsonObject["content"]?.jsonArray
                val text = blocks?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("")
                    ?.takeIf { it.isNotBlank() }
                    ?: bodyText
                val reasoning = blocks
                    ?.mapNotNull { it.jsonObject["thinking"]?.jsonPrimitive?.content }
                    ?.joinToString("")
                    ?.takeIf { it.isNotBlank() }
                ChatResponse(text, reasoning)
            } else if (isChatCompletion) {
                val message = element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject
                val text = message?.get("content")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: bodyText
                // DeepSeek-R1 and OpenAI-compatible reasoning endpoints report
                // the chain of thought in reasoning_content.
                val reasoning = message?.get("reasoning_content")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ChatResponse(text, reasoning)
            } else {
                ChatResponse(
                    element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: bodyText
                )
            }
        } catch (_: Exception) {
            ChatResponse(bodyText)
        }
    }

    private suspend fun extractErrorMessage(response: HttpResponse): String {
        return try {
            val text = response.bodyAsText()
            val element = json.parseToJsonElement(text)
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
    ): Flow<StreamChunk> = flow {
        val apiStyle = apiStyleForProvider(provider)
        val endpoint = endpointFor(baseUrl, apiStyle, isChatCompletion)
        val hasImages = messages.any { it.images.isNotEmpty() }
        val thinkingEnabled = params.thinkingBudgetTokens != null

        var streamResult = StreamResult.Empty
        var lastUsedIgnoreImages = false
        var fallbackToNonStreaming = false

        try {
            var failedWithImages = false

            httpClient.preparePost(endpoint) {
                putAuthHeaders(apiStyle, apiKey, thinkingEnabled = thinkingEnabled, model = model)
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

                streamResult = processResponseStream(response, apiStyle, isChatCompletion)
            }

            if (failedWithImages && streamResult != StreamResult.Completed) {
                lastUsedIgnoreImages = true
                httpClient.preparePost(endpoint) {
                    putAuthHeaders(apiStyle, apiKey, thinkingEnabled = thinkingEnabled, model = model)
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

                    streamResult = processResponseStream(response, apiStyle, isChatCompletion)
                }
            }

            if (fallbackToNonStreaming && streamResult != StreamResult.Completed) {
                val nonStreamedResponse = sendChatRequest(
                    baseUrl = baseUrl, apiKey = apiKey, model = model,
                    messages = messages, isChatCompletion = isChatCompletion,
                    ignoreImages = lastUsedIgnoreImages, params = params, provider = provider,
                    timeoutSeconds = timeoutSeconds
                )
                emit(StreamChunk(content = nonStreamedResponse.text))
                if (!nonStreamedResponse.reasoningText.isNullOrBlank()) {
                    emit(StreamChunk(reasoning = nonStreamedResponse.reasoningText))
                }
                streamResult = StreamResult.Completed
            }

            // A 200 whose body carried no tokens and no [DONE]/message_stop
            // (empty body, plain-text proxy response) would otherwise complete
            // silently and the controller would delete the response
            // placeholder with no error surfaced to the user.
            if (streamResult == StreamResult.Empty) {
                throw EmptyResponseException()
            }
            // A stream that delivered tokens but closed without a terminator
            // (server crash, proxy FIN, load shedding) was truncated. The
            // partial tokens were already emitted; the controller recovers the
            // full response non-streaming and replaces the partial content.
            if (streamResult == StreamResult.Truncated) {
                throw StreamTruncatedException()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: EmptyResponseException) {
            // No retry: the request was answered, it just carried no content.
            throw e
        } catch (e: StreamTruncatedException) {
            // No retry: a truncated stream was already recovered once; the
            // partial response is surfaced with the truncation error.
            throw e
        } catch (e: ApiRequestException) {
            // An API-level rejection (in-stream error event, non-200 from the
            // fallback) must not be retried: the same rejection would repeat.
            throw e
        } catch (e: Exception) {
            if (streamResult != StreamResult.Completed) {
                try {
                    val nonStreamedResponse = sendChatRequest(
                        baseUrl = baseUrl, apiKey = apiKey, model = model,
                        messages = messages, isChatCompletion = isChatCompletion,
                        ignoreImages = lastUsedIgnoreImages, params = params, provider = provider,
                        timeoutSeconds = timeoutSeconds
                    )
                    emit(StreamChunk(content = nonStreamedResponse.text))
                    if (!nonStreamedResponse.reasoningText.isNullOrBlank()) {
                        emit(StreamChunk(reasoning = nonStreamedResponse.reasoningText))
                    }
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
}

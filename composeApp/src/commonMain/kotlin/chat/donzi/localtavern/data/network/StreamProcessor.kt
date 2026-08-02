package chat.donzi.localtavern.data.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Parses the SSE body of a chat-completion response into StreamChunk events.
// OpenAI and Anthropic event shapes differ only in the deltas they carry; the
// framing (accumulate "data:" lines, flush on blank line/EOF) is identical.
internal suspend fun FlowCollector<StreamChunk>.processResponseStream(
    response: HttpResponse,
    apiStyle: ApiStyle,
    isChatCompletion: Boolean
): StreamResult {
    var sawTerminator = false
    var emittedAnyToken = false
    val channel: ByteReadChannel = response.bodyAsChannel()
    // SSE events may span multiple "data:" lines (and continuations
    // starting with a space); accumulate until a blank line or EOF.
    val pendingEvent = StringBuilder()

    suspend fun flushPendingEvent() {
        if (pendingEvent.isEmpty()) return
        val data = pendingEvent.toString().trim()
        pendingEvent.clear()

        if (data == "[DONE]") {
            sawTerminator = true
            return
        }
        if (data.isBlank()) return

        try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(data)

            if (apiStyle == ApiStyle.Anthropic) {
                val eventType = element.jsonObject["type"]?.jsonPrimitive?.content
                when (eventType) {
                    "content_block_delta" -> {
                        val delta = element.jsonObject["delta"]?.jsonObject
                        val deltaType = delta?.get("type")?.jsonPrimitive?.content
                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta.get("text")?.jsonPrimitive?.content
                                if (text != null) {
                                    emittedAnyToken = true
                                    emit(StreamChunk(content = text))
                                }
                            }
                            "thinking_delta" -> {
                                // Anthropic extended thinking: the model's
                                // chain of thought arrives before the text.
                                val thinking = delta.get("thinking")?.jsonPrimitive?.content
                                if (thinking != null) {
                                    emittedAnyToken = true
                                    emit(StreamChunk(reasoning = thinking))
                                }
                            }
                        }
                    }
                    "message_stop" -> {
                        sawTerminator = true
                    }
                    "error" -> {
                        val errorMessage = element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        throw ApiRequestException("API error: ${errorMessage ?: "Unknown error"}")
                    }
                }
            } else {
                val errorMessage = element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                if (!errorMessage.isNullOrBlank()) {
                    throw ApiRequestException("API error: $errorMessage")
                }
                if (isChatCompletion) {
                    val delta = element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("delta")?.jsonObject
                    val content = delta?.get("content")?.jsonPrimitive?.content
                    if (content != null) {
                        emittedAnyToken = true
                        emit(StreamChunk(content = content))
                    }
                    // DeepSeek-R1 and reasoning-capable OpenAI-compatible
                    // endpoints stream the chain of thought in
                    // reasoning_content before the visible text.
                    val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.content
                    if (reasoning != null) {
                        emittedAnyToken = true
                        emit(StreamChunk(reasoning = reasoning))
                    }
                } else {
                    val text = element.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
                    if (text != null) {
                        emittedAnyToken = true
                        emit(StreamChunk(content = text))
                    }
                }
            }
        } catch (e: ApiRequestException) {
            throw e
        } catch (_: Exception) {
            // Malformed or fragmented event: drop it and keep scanning.
            // A single bad event must not abort the whole stream.
        }
    }

    while (!channel.isClosedForRead && !sawTerminator) {
        currentCoroutineContext().ensureActive()
        @Suppress("DEPRECATION")
        val line = channel.readUTF8Line() ?: break
        if (line.startsWith("data:")) {
            // Accept both "data: {...}" and the non-conformant "data:{...}".
            if (pendingEvent.isNotEmpty()) pendingEvent.append('\n')
            pendingEvent.append(line.removePrefix("data:").removePrefix(" "))
        } else if (line.startsWith(" ") && pendingEvent.isNotEmpty()) {
            // SSE continuation of the previous data field.
            pendingEvent.append('\n').append(line.trimStart(' '))
        } else {
            // A blank line (or any other line) ends the current event.
            flushPendingEvent()
        }
    }
    flushPendingEvent()

    return when {
        sawTerminator -> StreamResult.Completed
        emittedAnyToken -> StreamResult.Truncated
        else -> StreamResult.Empty
    }
}

internal enum class StreamResult {
    /** The stream ended with a proper terminator ([DONE] / message_stop). */
    Completed,

    /** Tokens were delivered but the stream closed without a terminator. */
    Truncated,

    /** The body carried no SSE data at all. */
    Empty
}

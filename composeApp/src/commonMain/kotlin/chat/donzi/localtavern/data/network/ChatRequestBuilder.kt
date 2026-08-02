package chat.donzi.localtavern.data.network

import chat.donzi.localtavern.utils.ChatMessage
import chat.donzi.localtavern.utils.ImageAttachment
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Builders shared by the streaming and non-streaming request paths, so the
// two payload shapes (OpenAI-style and Anthropic) can never drift apart.

internal fun HttpRequestBuilder.putAuthHeaders(
    apiStyle: ApiStyle,
    apiKey: String,
    thinkingEnabled: Boolean = false,
    model: String = ""
) {
    if (apiStyle == ApiStyle.Anthropic) {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        // Extended thinking for Claude 3.7 Sonnet is behind a beta header.
        // Newer models (Sonnet 4 / Opus 4.x) ship it GA and reject the
        // header, so it is only attached for the 3.7 family.
        if (thinkingEnabled && model.contains("3-7")) {
            header("anthropic-beta", "extended-thinking-2025-02-19")
        }
    } else {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
    }
}

internal fun endpointFor(baseUrl: String, apiStyle: ApiStyle, isChatCompletion: Boolean): String = when {
    apiStyle == ApiStyle.Anthropic -> "${normalizeBaseUrl(baseUrl)}/messages"
    isChatCompletion -> "${normalizeBaseUrl(baseUrl)}/chat/completions"
    else -> "${normalizeBaseUrl(baseUrl)}/completions"
}

private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trimEnd('/')

internal data class AnthropicTurn(
    val role: String,
    val text: String,
    val images: List<ImageAttachment>
)

internal fun JsonObjectBuilder.putAnthropicMessages(messages: List<ChatMessage>) {
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
        // empty user turn so the request is accepted; the content must be
        // non-empty (a blank string is rejected by the API as empty
        // content), so a single space is used.
        if (turns.isNotEmpty() && turns.first().role == "assistant") {
            turns.add(0, AnthropicTurn("user", " ", emptyList()))
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

internal fun JsonObjectBuilder.putAnthropicParams(params: GenerationParams) {
    // Anthropic requires max_tokens. A responseLimit of 0 means
    // "unlimited" in the UI; use a generous ceiling instead of a tiny
    // default so long outputs are not silently truncated at 1024 tokens.
    put("max_tokens", params.maxTokens.takeIf { it > 0 } ?: 8192L)
    put("temperature", params.temperature)
    put("top_p", params.topP)
    if (params.topK > 0) put("top_k", params.topK)
    params.thinkingBudgetTokens?.let { budget ->
        // The thinking budget must be between 1024 and max_tokens; clamp
        // so a small responseLimit cannot produce an invalid request.
        val maxTokens = params.maxTokens.takeIf { it > 0 } ?: 8192L
        put("thinking", buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", budget.coerceIn(1024, maxTokens.coerceAtLeast(1024)))
        })
    }
}

internal fun JsonObjectBuilder.putChatMessages(
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

internal fun JsonObjectBuilder.putGenerationParams(params: GenerationParams) {
    put("temperature", params.temperature)
    put("top_p", params.topP)
    if (params.topK > 0) put("top_k", params.topK)
    put("presence_penalty", params.presencePenalty)
    put("frequency_penalty", params.frequencyPenalty)
    if (params.maxTokens > 0) put("max_tokens", params.maxTokens)
    params.reasoningEffort?.let { put("reasoning_effort", it) }
}

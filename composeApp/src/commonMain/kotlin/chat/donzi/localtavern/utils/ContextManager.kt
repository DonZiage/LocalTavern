package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.PromptBlock

@kotlinx.serialization.Serializable
data class ImageAttachment(
    val base64: String,
    val mimeType: String
)

@kotlinx.serialization.Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<ImageAttachment> = emptyList()
)

object ContextManager {

    // Vision encoders charge a fixed base cost per image (plus per-tile cost),
    // e.g. ~1600 for Anthropic, ~85-170 for GPT-4o, ~258 for Gemini. A
    // conservative flat average keeps the context budget from overshooting.
    const val TOKENS_PER_IMAGE = 1500

    fun replaceSimpleMacros(text: String, charName: String, userName: String): String {
        return text
            .replace("{{char}}", charName)
            .replace("{{user}}", userName)
    }

    fun buildPayload(
        blocks: List<PromptBlock>,
        character: Character?,
        persona: Persona?,
        chatHistory: List<ChatMessage>,
        contextLimit: Long,
        responseLimit: Long,
        tokenizer: Tokenizer = DefaultTokenizer
    ): List<ChatMessage> {
        val activeBlocks = blocks.filter { it.isEnabled }
        val promptBuilder = StringBuilder()

        val parsedExamples = character?.mesExample?.joinToString("\n") ?: ""

        val charName = character?.name.orEmpty()
        val userName = persona?.name.orEmpty()
        val personaDesc = persona?.description.orEmpty()
        val charDesc = character?.description.orEmpty()
        val scenario = character?.scenario.orEmpty()

        fun replacePlaceholders(text: String, historyContext: List<ChatMessage>): String {
            val lastMsg = historyContext.lastOrNull()?.content.orEmpty()
            val lastUserMsg = historyContext.lastOrNull { it.role == "user" }?.content.orEmpty()
            val lastCharMsg = historyContext.lastOrNull { it.role == "assistant" || it.role == "char" || it.role == "character" }?.content.orEmpty()

            val baseReplaced = text
                .replace("{{user_persona}}", personaDesc)
                .replace("{{character_description}}", charDesc)
                .replace("{{personality}}", character?.personality.orEmpty())
                .replace("{{scenario}}", scenario)
                .replace("{{mes_example}}", parsedExamples)
                .replace("{{persona}}", personaDesc)
                .replace("{{description}}", charDesc)
                .replace("{{lastMessage}}", lastMsg)
                .replace("{{lastUserMessage}}", lastUserMsg)
                .replace("{{lastCharMessage}}", lastCharMsg)

            return replaceSimpleMacros(baseReplaced, charName, userName)
        }

        val processedHistory = mutableListOf<ChatMessage>()
        for (msg in chatHistory) {
            val processedContent = replacePlaceholders(msg.content, processedHistory)
            processedHistory.add(msg.copy(content = processedContent))
        }

        for (block in activeBlocks) {
            val content = replacePlaceholders(block.template, processedHistory)

            if (content.isNotBlank() && content.contains("{{chat_history}}")) {
                // {{chat_history}} is a structural marker: the history is sent
                // as separate messages after the system prompt, so the marker
                // itself is dropped inline. A block that MIXES the marker with
                // real text must keep its text (e.g. "Important: {{chat_history}}").
                val cleaned = content.replace("{{chat_history}}", "").trim()
                if (cleaned.isNotBlank()) {
                    promptBuilder.append(cleaned).append("\n\n")
                }
            } else if (content.isNotBlank()) {
                promptBuilder.append(content.trim()).append("\n\n")
            }
        }

        val systemPromptStr = promptBuilder.toString().trim()
        val systemPromptTokens = tokenizer.countTokens(systemPromptStr)

        val safeBuffer = 50
        // A contextLimit of 0 (or less) means "Unlimited". Cap at Int.MAX_VALUE
        // so the token budget arithmetic below cannot overflow or go negative.
        val effectiveContextLimit = if (contextLimit <= 0L) Int.MAX_VALUE.toLong() else contextLimit.coerceAtMost(Int.MAX_VALUE.toLong())
        val rawReservation = responseLimit.coerceAtLeast(0L).coerceAtMost(effectiveContextLimit)
        // A response limit at or above the context limit must not starve the
        // prompt and history: without this cap the reservation would eat the
        // whole budget, the prompt would truncate to "", history would drop,
        // and the payload would collapse to a generic assistant fallback.
        val responseReservation = if (rawReservation >= effectiveContextLimit) effectiveContextLimit / 2 else rawReservation
        val promptBudget = (effectiveContextLimit - responseReservation - safeBuffer).coerceAtLeast(0).toInt()

        // An oversized system prompt must not crowd out the conversation
        // either: truncate it to half the budget so the model still sees the
        // history (including the message it is replying to).
        var safeSystemPrompt = systemPromptStr
        if (systemPromptTokens > 0 && systemPromptTokens > promptBudget) {
            safeSystemPrompt = tokenizer.truncateByTokens(systemPromptStr, promptBudget / 2)
        }

        val finalMessages = mutableListOf<ChatMessage>()
        if (safeSystemPrompt.isNotBlank()) {
            finalMessages.add(ChatMessage(role = "system", content = safeSystemPrompt))
        }

        // Budget remaining after the (possibly truncated) system prompt.
        var availableTokens = promptBudget - tokenizer.countTokens(safeSystemPrompt)

        val selectedHistory = mutableListOf<ChatMessage>()
        for (msg in processedHistory.reversed()) {
            val msgTokens = tokenizer.countTokens(msg.content) + 4 + msg.images.size * TOKENS_PER_IMAGE

            if (availableTokens - msgTokens >= 0) {
                selectedHistory.add(msg)
                availableTokens -= msgTokens
            } else {
                break
            }
        }

        finalMessages.addAll(selectedHistory.reversed())

        // Blocks containing {{chat_history}} are structural placeholders and are
        // skipped above; when no other block contributes content and the chat is
        // empty, an empty messages array would be sent. Never send an empty payload.
        if (finalMessages.isEmpty()) {
            finalMessages.add(
                ChatMessage(
                    role = "system",
                    content = systemPromptStr.ifBlank { "You are a helpful assistant. Continue the conversation." }
                )
            )
        }
        return finalMessages
    }
}
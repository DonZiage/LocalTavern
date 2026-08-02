package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.LorebookEntry
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

    // Resolves the {{lorebook}} macro against the entries that match the chat
    // history. Constant (always-on) entries are injected first, then matched
    // entries ordered by their insertion order.
    fun buildLorebookText(character: Character?, history: List<ChatMessage>): String {
        if (character == null) return ""
        val book = LorebookParser.parse(character.characterBook)
        if (book.enabledEntries.isEmpty()) return ""

        val historyText = history.joinToString("\n") { "${it.role}: ${it.content}" }
        val matched = book.enabledEntries.filter { entry ->
            entryMatches(entry, historyText)
        }

        val ordered = matched.sortedWith(
            compareBy<LorebookEntry> { if (it.constant) 0 else 1 }
                .thenBy { it.insertionOrder ?: Long.MAX_VALUE }
                .thenBy { it.name }
        )
        if (ordered.isEmpty()) return ""
        return ordered.joinToString("\n\n") { entry ->
            val header = if (entry.name.isNotBlank()) "${entry.name}: " else ""
            header + entry.content
        }
    }

    private fun entryMatches(entry: LorebookEntry, historyText: String): Boolean {
        if (entry.constant) return true
        if (entry.keys.isEmpty()) return false
        val matchText = if (entry.caseSensitive) historyText else historyText.lowercase()
        val normalizedKeys = if (entry.caseSensitive) entry.keys else entry.keys.map { it.lowercase() }
        val primaryMatch = normalizedKeys.any { key -> key.isNotBlank() && matchText.contains(key) }
        if (!primaryMatch) return false
        if (!entry.selective) return true
        val secondary = if (entry.caseSensitive) entry.secondaryKeys else entry.secondaryKeys.map { it.lowercase() }
        return secondary.any { key -> key.isNotBlank() && matchText.contains(key) }
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

            // {{lorebook}} is a structural marker resolved by buildSystemPrompt
            // against the history actually sent; a bare macro in a history
            // message must not leak the macro text into the conversation.
            return replaceSimpleMacros(baseReplaced, charName, userName).replace("{{lorebook}}", "")
        }

        fun buildSystemPrompt(historyContext: List<ChatMessage>): String {
            val promptBuilder = StringBuilder()
            val lorebookText = buildLorebookText(character, historyContext)
            var anyBlockUsesLorebook = false

            for (block in activeBlocks) {
                if (block.template.contains("{{lorebook}}")) {
                    anyBlockUsesLorebook = true
                }
                // Resolve the lorebook macro BEFORE replacePlaceholders, which
                // strips any remaining {{lorebook}} marker (to keep history
                // messages clean); the template must see the substituted text.
                val content = replacePlaceholders(
                    block.template.replace("{{lorebook}}", lorebookText),
                    historyContext
                )

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

            // Auto-inject matched lorebook entries when no prompt block places
            // them explicitly, so imported character cards with world info work
            // without the user wiring up a {{lorebook}} block.
            if (lorebookText.isNotBlank() && !anyBlockUsesLorebook) {
                promptBuilder.append("Lorebook:\n").append(lorebookText).append("\n\n")
            }

            return promptBuilder.toString().trim()
        }

        val processedHistory = mutableListOf<ChatMessage>()
        for (msg in chatHistory) {
            val processedContent = replacePlaceholders(msg.content, processedHistory)
            processedHistory.add(msg.copy(content = processedContent))
        }

        // Pass 1: estimate the system prompt against the FULL history so the
        // budget arithmetic has a concrete token count. The {{lastMessage}}
        // family of macros is re-resolved against the actually-sent history
        // below; a macro referencing a message that got truncated away would
        // otherwise leak content the model cannot see into the prompt.
        val estimatedSystemPrompt = buildSystemPrompt(processedHistory)
        val systemPromptTokens = tokenizer.countTokens(estimatedSystemPrompt)

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
        var safeSystemPrompt = estimatedSystemPrompt
        if (systemPromptTokens > 0 && systemPromptTokens > promptBudget) {
            safeSystemPrompt = tokenizer.truncateByTokens(estimatedSystemPrompt, promptBudget / 2)
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

        // Pass 2: re-resolve the system prompt against the history that will
        // actually be sent, so {{lastMessage}} never references a message the
        // model cannot see. The re-resolved prompt may only REPLACE the pass-1
        // one when it is no larger: the budget arithmetic above reserved
        // exactly the pass-1 prompt's tokens, and a bigger prompt would push
        // the payload over the context limit.
        if (finalMessages.isNotEmpty() && finalMessages[0].role == "system") {
            val finalSystemPrompt = buildSystemPrompt(selectedHistory)
            if (finalSystemPrompt.isBlank()) {
                // Every macro resolved to empty (e.g. a bare {{lastMessage}}
                // block whose message was truncated out of the budget): shipping
                // the pass-1 fragment would leak content the model cannot see,
                // so drop the message instead of leaking it.
                finalMessages.removeAt(0)
            } else if (tokenizer.countTokens(finalSystemPrompt) <= tokenizer.countTokens(safeSystemPrompt)) {
                finalMessages[0] = ChatMessage(role = "system", content = finalSystemPrompt)
            }
        }

        // Blocks containing {{chat_history}} are structural placeholders and are
        // skipped above; when no other block contributes content and the chat is
        // empty, an empty messages array would be sent. Never send an empty payload.
        if (finalMessages.isEmpty()) {
            finalMessages.add(
                ChatMessage(
                    role = "system",
                    content = estimatedSystemPrompt.ifBlank { "You are a helpful assistant. Continue the conversation." }
                )
            )
        }
        return finalMessages
    }
}
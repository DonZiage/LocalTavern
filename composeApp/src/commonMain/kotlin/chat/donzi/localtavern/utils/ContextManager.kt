package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.PersonaEntity

@kotlinx.serialization.Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

object ContextManager {

    fun buildPayload(
        blocks: List<PromptBlock>,
        character: CharacterEntity?,
        persona: PersonaEntity?,
        chatHistory: List<ChatMessage>,
        contextLimit: Long,
        responseLimit: Long,
        tokenizer: Tokenizer = DefaultTokenizer
    ): List<ChatMessage> {
        val activeBlocks = blocks.filter { it.isEnabled }
        val promptBuilder = StringBuilder()

        val parsedExamples = character?.mesExample?.split("|||")?.joinToString("\n") ?: ""

        // Core extraction fields
        val charName = character?.name.orEmpty()
        val userName = persona?.name.orEmpty()
        val personaDesc = persona?.description.orEmpty()
        val charDesc = character?.description.orEmpty()
        val scenario = character?.scenario.orEmpty()

        // Shared helper function to resolve all dynamic and static placeholders
        fun replacePlaceholders(text: String, historyContext: List<ChatMessage>): String {
            val lastMsg = historyContext.lastOrNull()?.content.orEmpty()
            val lastUserMsg = historyContext.lastOrNull { it.role == "user" }?.content.orEmpty()
            val lastCharMsg = historyContext.lastOrNull { it.role == "assistant" || it.role == "char" || it.role == "character" }?.content.orEmpty()

            return text
                // Legacy / Internal Card Fields
                .replace("{{user_persona}}", personaDesc)
                .replace("{{character_description}}", charDesc)
                .replace("{{personality}}", character?.personality.orEmpty())
                .replace("{{scenario}}", scenario)
                .replace("{{mes_example}}", parsedExamples)
                // Standard Tavern Chat Macros
                .replace("{{char}}", charName)
                .replace("{{user}}", userName)
                .replace("{{persona}}", personaDesc)
                .replace("{{description}}", charDesc)
                .replace("{{scenario}}", scenario)
                .replace("{{lastMessage}}", lastMsg)
                .replace("{{lastUserMessage}}", lastUserMsg)
                .replace("{{lastCharMessage}}", lastCharMsg)
        }

        // 1. Process Chat History chronologically (oldest to newest)
        // This ensures identity fields are evaluated immediately, and context fields reference evaluated strings
        val processedHistory = mutableListOf<ChatMessage>()
        for (msg in chatHistory) {
            val processedContent = replacePlaceholders(msg.content, processedHistory)
            processedHistory.add(msg.copy(content = processedContent))
        }

        // 2. Process Prompt Blocks using the finalized, evaluated history
        for (block in activeBlocks) {
            val content = replacePlaceholders(block.template, processedHistory)

            if (content.isNotBlank() && !content.contains("{{chat_history}}")) {
                promptBuilder.append(content.trim()).append("\n\n")
            }
        }

        val systemPromptStr = promptBuilder.toString().trim()
        val systemPromptTokens = tokenizer.countTokens(systemPromptStr)

        val safeBuffer = 50
        var availableTokens = (contextLimit - responseLimit - systemPromptTokens - safeBuffer).toInt()

        val finalMessages = mutableListOf<ChatMessage>()

        if (systemPromptTokens > 0) {
            val safeSystemPrompt = if (availableTokens < 0) {
                tokenizer.truncateByTokens(systemPromptStr, (contextLimit - responseLimit - safeBuffer).toInt())
            } else {
                systemPromptStr
            }
            finalMessages.add(ChatMessage(role = "system", content = safeSystemPrompt))
        }

        // 3. Select fitting historical items backwards using the processed token counts
        val selectedHistory = mutableListOf<ChatMessage>()
        for (msg in processedHistory.reversed()) {
            val msgTokens = tokenizer.countTokens(msg.content) + 4

            if (availableTokens - msgTokens >= 0) {
                selectedHistory.add(msg)
                availableTokens -= msgTokens
            } else {
                break
            }
        }

        finalMessages.addAll(selectedHistory.reversed())
        return finalMessages
    }
}
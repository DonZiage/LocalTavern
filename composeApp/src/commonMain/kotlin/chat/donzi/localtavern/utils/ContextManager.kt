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

        for (block in activeBlocks) {
            val content = block.template
                .replace("{{user_persona}}", persona?.description.orEmpty())
                .replace("{{character_description}}", character?.description.orEmpty())
                .replace("{{personality}}", character?.personality.orEmpty())
                .replace("{{scenario}}", character?.scenario.orEmpty())
                .replace("{{mes_example}}", parsedExamples) // Injects clean string output formatting block

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

        val selectedHistory = mutableListOf<ChatMessage>()

        for (msg in chatHistory.reversed()) {
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
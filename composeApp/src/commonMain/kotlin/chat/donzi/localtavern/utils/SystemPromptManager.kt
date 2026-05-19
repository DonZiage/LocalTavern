package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.database.PromptBlockEntity
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.models.Persona

data class PromptBlock(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val template: String,
    val isCustom: Boolean = false
)

fun PromptBlockEntity.toDomain() = PromptBlock(
    id = this.id,
    name = this.name,
    template = this.template,
    isEnabled = this.isEnabled == 1L,
    isCustom = this.isCustom == 1L
)

object SystemPromptManager {

    fun buildFinalSystemPrompt(
        blocks: List<PromptBlock>,
        character: SillyTavernCardV2,
        persona: Persona?
    ): String {
        val activeBlocks = blocks.filter { it.isEnabled }
        val promptBuilder = StringBuilder()

        for (block in activeBlocks) {
            // Chained replacement expressions into an immutable 'val'
            val content = block.template
                .replace("{{system_prompt}}", character.system_prompt)
                .replace("{{user_persona}}", persona?.description.orEmpty())
                .replace("{{character_description}}", character.description)
                .replace("{{personality}}", character.personality)
                .replace("{{scenario}}", character.scenario)

            if (content.isNotBlank() && !content.contains("{{chat_history}}")) {
                promptBuilder.append(content.trim()).append("\n\n")
            }
        }

        return promptBuilder.toString().trim()
    }
}
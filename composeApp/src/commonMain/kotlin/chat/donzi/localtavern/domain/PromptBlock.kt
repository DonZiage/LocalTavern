package chat.donzi.localtavern.domain

data class PromptBlock(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val template: String,
    val isCustom: Boolean = false
)

package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.database.PromptBlockEntity

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
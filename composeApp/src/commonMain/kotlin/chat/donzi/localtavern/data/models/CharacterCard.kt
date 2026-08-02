package chat.donzi.localtavern.data.models

import kotlinx.serialization.Serializable

@Serializable
data class SillyTavernCardV2(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val first_mes: String = "",
    val mes_example: String = "",
    val creator_notes: String = "",
    val system_prompt: String = "",
    val alternate_greetings: List<String> = emptyList()
)

@Serializable
data class SillyTavernWrapper(
    val spec: String = "chara_card_v2",
    val spec_version: String = "2.0",
    val data: SillyTavernCardV2? = null
)
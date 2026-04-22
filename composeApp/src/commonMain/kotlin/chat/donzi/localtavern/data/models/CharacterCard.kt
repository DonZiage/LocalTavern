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

// This "Wrapper" allows us to catch V2 cards that put everything inside 'data'
@Serializable
data class SillyTavernWrapper(
    val data: SillyTavernCardV2? = null
)
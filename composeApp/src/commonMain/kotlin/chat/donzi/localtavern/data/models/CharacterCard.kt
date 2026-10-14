package chat.donzi.localtavern.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Model of the SillyTavern "chara_card_v2" spec. All v2 fields are preserved
// so importing a card and re-exporting it never silently strips metadata.
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
    val post_history_instructions: String = "",
    val alternate_greetings: List<String> = emptyList(),
    val creator: String = "",
    val character_version: String = "",
    val tags: List<String> = emptyList(),
    val extensions: JsonObject? = null,
    val character_book: JsonObject? = null
)

@Serializable
data class SillyTavernWrapper(
    val spec: String = "chara_card_v2",
    val spec_version: String = "2.0",
    val data: SillyTavernCardV2? = null
)

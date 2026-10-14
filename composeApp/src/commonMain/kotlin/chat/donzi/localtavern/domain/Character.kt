package chat.donzi.localtavern.domain

import kotlinx.serialization.json.JsonObject

data class Character(
    val id: String,
    val name: String,
    val description: String?,
    val personality: String,
    val scenario: String,
    val firstMes: String?,
    val mesExample: List<String> = emptyList(),
    val creatorNotes: String?,
    val altGreetings: List<String> = emptyList(),
    val avatarData: ByteArray?,
    val isAssistant: Boolean = false,
    // Round-trip-only fields: preserved on import/export so re-exporting a
    // character card does not silently strip SillyTavern v2 metadata.
    val systemPrompt: String? = null,
    val postHistoryInstructions: String? = null,
    val creator: String? = null,
    val characterVersion: String? = null,
    val tags: List<String> = emptyList(),
    val extensions: JsonObject? = null,
    val characterBook: JsonObject? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Character
        if (id != other.id) return false
        if (name != other.name) return false
        if (description != other.description) return false
        if (personality != other.personality) return false
        if (scenario != other.scenario) return false
        if (firstMes != other.firstMes) return false
        if (mesExample != other.mesExample) return false
        if (creatorNotes != other.creatorNotes) return false
        if (altGreetings != other.altGreetings) return false
        if (isAssistant != other.isAssistant) return false
        if (systemPrompt != other.systemPrompt) return false
        if (postHistoryInstructions != other.postHistoryInstructions) return false
        if (creator != other.creator) return false
        if (characterVersion != other.characterVersion) return false
        if (tags != other.tags) return false
        if (extensions != other.extensions) return false
        if (characterBook != other.characterBook) return false
        if (avatarData != null) {
            if (other.avatarData == null) return false
            if (!avatarData.contentEquals(other.avatarData)) return false
        } else if (other.avatarData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + personality.hashCode()
        result = 31 * result + scenario.hashCode()
        result = 31 * result + (firstMes?.hashCode() ?: 0)
        result = 31 * result + mesExample.hashCode()
        result = 31 * result + (creatorNotes?.hashCode() ?: 0)
        result = 31 * result + altGreetings.hashCode()
        result = 31 * result + isAssistant.hashCode()
        result = 31 * result + (systemPrompt?.hashCode() ?: 0)
        result = 31 * result + (postHistoryInstructions?.hashCode() ?: 0)
        result = 31 * result + (creator?.hashCode() ?: 0)
        result = 31 * result + (characterVersion?.hashCode() ?: 0)
        result = 31 * result + tags.hashCode()
        result = 31 * result + (extensions?.hashCode() ?: 0)
        result = 31 * result + (characterBook?.hashCode() ?: 0)
        result = 31 * result + (avatarData?.contentHashCode() ?: 0)
        return result
    }
}

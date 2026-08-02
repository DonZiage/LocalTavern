package chat.donzi.localtavern.domain

data class Persona(
    val id: String,
    val name: String,
    val description: String?,
    val avatarData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Persona
        if (id != other.id) return false
        if (name != other.name) return false
        if (description != other.description) return false
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
        result = 31 * result + (avatarData?.contentHashCode() ?: 0)
        return result
    }
}

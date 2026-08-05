package chat.donzi.localtavern.domain

data class Persona(
    val id: String,
    val name: String,
    val description: String?,
    val avatarData: ByteArray?
) {
    companion object {
        // The implicit persona used when the user has not defined any persona
        // yet: a blank profile named "User". It is never stored in the
        // database, so it can never show up in the persona cards, and it
        // keeps a stable id so sessions bound to it remain addressable.
        const val DEFAULT_ID = "localtavern-default-user"

        fun defaultUser(): Persona = Persona(DEFAULT_ID, "User", null, null)
    }

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

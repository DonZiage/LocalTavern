package chat.donzi.localtavern.domain

data class Message(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val parentId: String?,
    val isActivePath: Boolean,
    val images: List<ByteArray> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        other as Message
        if (id != other.id) return false
        if (sessionId != other.sessionId) return false
        if (role != other.role) return false
        if (content != other.content) return false
        if (timestamp != other.timestamp) return false
        if (parentId != other.parentId) return false
        if (isActivePath != other.isActivePath) return false
        if (images.size != other.images.size) return false
        for (i in images.indices) {
            if (!images[i].contentEquals(other.images[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (parentId?.hashCode() ?: 0)
        result = 31 * result + isActivePath.hashCode()
        result = 31 * result + images.sumOf { it.contentHashCode() }
        return result
    }
}

package chat.donzi.localtavern.domain

data class Session(
    val id: String,
    val characterId: String,
    val personaId: String,
    val title: String?,
    val lastTimestamp: Long,
    val currentMessageId: String?,
    val parentSessionId: String?
)

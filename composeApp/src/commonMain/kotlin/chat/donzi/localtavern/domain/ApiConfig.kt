package chat.donzi.localtavern.domain

data class ApiConfig(
    val id: String,
    val provider: String,
    val name: String,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    val isActive: Boolean,
    val isChatCompletion: Boolean,
    val lastUsed: Long?,
    val temperature: Double,
    val topP: Double,
    val topK: Long,
    val presencePenalty: Double,
    val frequencyPenalty: Double,
    val contextLimit: Long,
    val responseLimit: Long,
    val displayOrder: Long,
    val timeoutLimit: Long
)

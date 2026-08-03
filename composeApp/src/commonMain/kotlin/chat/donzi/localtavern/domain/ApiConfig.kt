package chat.donzi.localtavern.domain

data class ApiConfig(
    val id: String,
    val provider: String,
    val name: String,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    // OpenRouter-style routing: which upstream provider should serve the
    // model. Null means the endpoint's default provider is used.
    val inferenceProvider: String? = null,
    // OpenRouter quantization preference (int4, int8, fp8, fp16, bf16).
    // Null means the endpoint's default quantization.
    val quantization: String? = null,
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
    val timeoutLimit: Long,
    // 0 = auto-detect reasoning models by name, 1 = force on, 2 = force off.
    val reasoningOverride: Int = 0
)

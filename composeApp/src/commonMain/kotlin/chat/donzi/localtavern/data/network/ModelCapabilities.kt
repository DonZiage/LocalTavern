package chat.donzi.localtavern.data.network

// Names known to expose a reasoning/thinking trace (o-series, R1, ...).
// The override setting lets users force it on/off for anything else.
private val reasoningModelPatterns = listOf(
    Regex("(^|[^a-z])(r1|o1|o2|o3|o4)([^a-z]|$)", RegexOption.IGNORE_CASE),
    Regex("reasoner", RegexOption.IGNORE_CASE),
    Regex("(thinking|reasoning)", RegexOption.IGNORE_CASE)
)

fun isReasoningModel(model: String?): Boolean {
    if (model.isNullOrBlank()) return false
    return reasoningModelPatterns.any { it.containsMatchIn(model) }
}

// Whether the OpenAI-style request should include reasoning_effort. Only the
// o-series honors it; other models (R1, ...) ignore the field, and sending it
// to an endpoint that rejects unknown parameters would fail the request.
fun isOpenAIEffortModel(model: String?): Boolean {
    if (model.isNullOrBlank()) return false
    return Regex("(^|[^a-z])o[1-9]([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(model)
}

fun apiStyleForProvider(provider: String?): ApiStyle =
    if (provider?.trim().equals("Anthropic", ignoreCase = true)) ApiStyle.Anthropic else ApiStyle.OpenAI

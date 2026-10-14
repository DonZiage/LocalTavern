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

// The request format is chosen by the endpoint, not by a user-picked
// "provider": Anthropic's API speaks its own protocol while everything else
// is OpenAI-compatible. A stored provider (legacy connections) or an
// "anthropic" host in the base URL selects the Anthropic style.
fun apiStyleForProvider(provider: String?, baseUrl: String? = null): ApiStyle {
    if (provider?.trim().equals("Anthropic", ignoreCase = true)) return ApiStyle.Anthropic
    if (baseUrl?.contains("anthropic", ignoreCase = true) == true) return ApiStyle.Anthropic
    return ApiStyle.OpenAI
}

// Whether the OpenAI-style endpoint is the legacy /completions route. Modern
// models all speak /chat/completions; only OpenAI's old text-davinci
// generation and gpt-3.5-turbo-instruct require the older endpoint.
private val legacyCompletionModelPatterns = listOf(
    Regex("(^|/)text-(davinci|curie|babbage|ada)(-\\d+)?$", RegexOption.IGNORE_CASE),
    Regex("(^|/)(davinci|curie|babbage|ada)(-\\d+)?$", RegexOption.IGNORE_CASE),
    Regex("gpt-3\\.5-turbo-instruct", RegexOption.IGNORE_CASE)
)

fun isLegacyCompletionModel(model: String?): Boolean {
    if (model.isNullOrBlank()) return false
    return legacyCompletionModelPatterns.any { it.containsMatchIn(model) }
}

// Resolves a connection's stored chat-completion mode to the boolean used by
// the request layer. 0 = auto (decide from the model name and API style),
// 1 = force chat completions, 2 = force legacy completions. Anthropic's
// /messages route wins regardless (endpointFor short-circuits on the style).
fun effectiveChatCompletion(mode: Int, model: String?, provider: String?, baseUrl: String?): Boolean = when (mode) {
    1 -> true
    2 -> false
    else -> apiStyleForProvider(provider, baseUrl) != ApiStyle.Anthropic && !isLegacyCompletionModel(model)
}

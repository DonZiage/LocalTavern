package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing

// Bundled offline price list (USD per 1M tokens) for common cloud models,
// used as the fallback when live prices have not been fetched. Patterns are
// matched as anchored PREFIXES of model names (real ids carry date/version
// suffixes); the most specific (longest) pattern wins. Prices are approximate
// list prices and are meant for estimation only — cost estimates are derived
// automatically from this catalog plus the active connection's own limits.
object PricingCatalog {

    // All bundled prices are USD; a helper keeps the table above readable.
    private fun usd(provider: String, modelPattern: String, input: Double, output: Double) =
        ModelPricing(provider, modelPattern, input, output, "USD")

    private val entries: List<ModelPricing> = listOf(
        // OpenAI
        usd("OpenAI", "gpt-4o-mini", 0.15, 0.60),
        usd("OpenAI", "gpt-4o", 2.50, 10.00),
        usd("OpenAI", "gpt-4.1-mini", 0.40, 1.60),
        usd("OpenAI", "gpt-4.1-nano", 0.10, 0.40),
        usd("OpenAI", "gpt-4.1", 2.00, 8.00),
        usd("OpenAI", "o1-mini", 1.10, 4.40),
        usd("OpenAI", "o1", 15.00, 60.00),
        usd("OpenAI", "o3-mini", 1.10, 4.40),
        usd("OpenAI", "o3", 2.00, 8.00),
        usd("OpenAI", "o4-mini", 1.10, 4.40),
        usd("OpenAI", "o4", 10.00, 40.00),
        usd("OpenAI", "gpt-4-turbo", 10.00, 30.00),
        usd("OpenAI", "gpt-4", 30.00, 60.00),
        usd("OpenAI", "gpt-3.5-turbo", 0.50, 1.50),

        // Anthropic
        usd("Anthropic", "claude-3-5-haiku", 0.80, 4.00),
        usd("Anthropic", "claude-3-haiku", 0.25, 1.25),
        usd("Anthropic", "claude-3-5-sonnet", 3.00, 15.00),
        usd("Anthropic", "claude-3-7-sonnet", 3.00, 15.00),
        usd("Anthropic", "claude-3-opus", 15.00, 75.00),
        usd("Anthropic", "claude-haiku-4", 1.00, 5.00),
        usd("Anthropic", "claude-sonnet-4", 3.00, 15.00),
        usd("Anthropic", "claude-opus-4", 15.00, 75.00),

        // Google Gemini
        usd("Gemini", "gemini-2.5-flash", 0.30, 2.50),
        usd("Gemini", "gemini-2.5-pro", 1.25, 10.00),
        usd("Gemini", "gemini-2.0-flash", 0.10, 0.40),
        usd("Gemini", "gemini-1.5-flash", 0.075, 0.30),
        usd("Gemini", "gemini-1.5-pro", 1.25, 5.00),

        // DeepSeek
        usd("DeepSeek", "deepseek-chat", 0.27, 1.10),
        usd("DeepSeek", "deepseek-reasoner", 0.55, 2.19),

        // Mistral
        usd("Mistral", "mistral-large", 2.00, 6.00),
        usd("Mistral", "mistral-small", 0.20, 0.60),
        usd("Mistral", "mistral-medium", 2.70, 8.10),

        // xAI
        usd("xAI", "grok-4", 3.00, 15.00),
        usd("xAI", "grok-3-mini", 0.25, 1.00),
        usd("xAI", "grok-3", 3.00, 15.00),
        usd("xAI", "grok-2", 2.00, 10.00),

        // Cohere
        usd("Cohere", "command-r-plus", 2.50, 10.00),
        usd("Cohere", "command-r", 0.50, 1.50),

        // Perplexity
        usd("Perplexity", "sonar-pro", 1.00, 1.00),
        usd("Perplexity", "sonar-reasoning", 1.00, 5.00),

        // AI21
        usd("AI21", "jamba-1.5-large", 2.00, 8.00),
        usd("AI21", "jamba-1.5-mini", 0.20, 0.40),

        // TogetherAI
        usd("TogetherAI", "llama-3.1-405b", 3.00, 3.00),
        usd("TogetherAI", "llama-3.1-70b", 0.88, 0.88),
        usd("TogetherAI", "llama-3.1-8b", 0.18, 0.18),

        // Fireworks
        usd("Fireworks AI", "llama-3.1-405b", 3.00, 3.00),
        usd("Fireworks AI", "llama-3.1-70b", 0.90, 0.90),
        usd("Fireworks AI", "llama-3.1-8b", 0.20, 0.20),

        // OpenRouter passes through provider pricing; only a coarse default.
        usd("OpenRouter", "gpt-4o", 2.50, 10.00),
        usd("OpenRouter", "gpt-4o-mini", 0.15, 0.60),
        usd("OpenRouter", "claude-3-5-sonnet", 3.00, 15.00),
        usd("OpenRouter", "claude-3-7-sonnet", 3.00, 15.00),
        usd("OpenRouter", "deepseek-chat", 0.27, 1.10),
        usd("OpenRouter", "deepseek-reasoner", 0.55, 2.19)
    )

    // Local inference endpoints charge nothing at the API layer — and pricing
    // is irrelevant for them, so they resolve to NO price (null) and the UI
    // hides every cost readout instead of showing $0.00.
    private val localProviders = setOf(
        "LM Studio", "KoboldCPP", "TabbyAPI", "Oobabooga", "Ollama", "llama.cpp", "vLLM", "OAI-Compatible"
    )

    fun isLocalProvider(provider: String?): Boolean =
        provider != null && localProviders.contains(provider.trim())

    fun lookup(provider: String?, model: String?): ModelPricing? {
        val trimmedProvider = provider?.trim().orEmpty()
        val trimmedModel = model?.trim().orEmpty()
        if (trimmedProvider.isBlank() || trimmedModel.isBlank()) return null
        if (isLocalProvider(trimmedProvider)) return null

        // Longest matching pattern wins (most specific model first). Patterns
        // are anchored PREFIX matches: real model ids carry date/version
        // suffixes ("claude-3-5-sonnet-20241022", "gpt-4o-mini-2024-07-18"),
        // so a full-string match would never hit any catalog entry.
        val providerEntries = entries.filter { it.provider.equals(trimmedProvider, ignoreCase = true) }
        val matches = providerEntries
            .mapNotNull { entry ->
                val pattern = Regex(entry.modelPattern.replace(".", "\\."), RegexOption.IGNORE_CASE)
                if (pattern.matchAt(trimmedModel, 0) != null) entry else null
            }
            .sortedByDescending { it.modelPattern.length }
        return matches.firstOrNull()
    }
}

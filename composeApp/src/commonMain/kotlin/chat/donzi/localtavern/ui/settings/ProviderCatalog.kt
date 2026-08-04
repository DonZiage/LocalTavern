package chat.donzi.localtavern.ui.settings

object ProviderCatalog {
    val cloudInferenceProviders = listOf(
        "AI21", "Anthropic", "Cohere", "DeepSeek", "DreamGen", "Fireworks AI", "Gemini",
        "Mancer", "Mistral", "OpenAI", "OpenRouter", "Perplexity",
        "TogetherAI", "xAI"
    )

    val localInferenceProviders = listOf(
        "LM Studio", "KoboldCPP", "TabbyAPI", "Oobabooga", "Ollama", "llama.cpp", "vLLM", "OAI-Compatible"
    )

    val providerSections = listOf(
        "Cloud Inference" to cloudInferenceProviders,
        "Local Inference" to localInferenceProviders
    )

    val defaultUrls = mapOf(
        "OpenAI" to "https://api.openai.com/v1",
        "Anthropic" to "https://api.anthropic.com/v1",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "DeepSeek" to "https://api.deepseek.com",
        "TogetherAI" to "https://api.together.xyz/v1",
        "Mistral" to "https://api.mistral.ai/v1",
        "xAI" to "https://api.x.ai/v1",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
        "AI21" to "https://api.ai21.com/studio/v1",
        "Cohere" to "https://api.cohere.com/compatibility/v1",
        "Perplexity" to "https://api.perplexity.ai",
        "Fireworks AI" to "https://api.fireworks.ai/inference/v1",
        "Mancer" to "https://api.mancer.tech/v1",
        "DreamGen" to "https://dreamgen.com/api/v1",
        "LM Studio" to "http://localhost:1234/v1",
        "KoboldCPP" to "http://localhost:5001/v1",
        "Oobabooga" to "http://localhost:5000/v1",
        "Ollama" to "http://localhost:11434/v1",
        "TabbyAPI" to "http://localhost:5000/v1",
        "llama.cpp" to "http://localhost:8080/v1",
        "vLLM" to "http://localhost:8000/v1"
    )

    // Provider name matched against the host of a base URL. The dialog no
    // longer asks the user to pick the API provider; the stored provider is
    // derived from the endpoint URL so pricing and API-style detection keep
    // working without a dropdown.
    private val providerHostHints = listOf(
        "OpenRouter" to listOf("openrouter.ai"),
        "OpenAI" to listOf("api.openai.com"),
        "Anthropic" to listOf("api.anthropic.com"),
        "DeepSeek" to listOf("api.deepseek.com"),
        "TogetherAI" to listOf("api.together.xyz"),
        "Mistral" to listOf("api.mistral.ai"),
        "xAI" to listOf("api.x.ai"),
        "Gemini" to listOf("generativelanguage.googleapis.com", "gemini.googleapis.com"),
        "AI21" to listOf("api.ai21.com"),
        "Cohere" to listOf("api.cohere.com"),
        "Perplexity" to listOf("api.perplexity.ai"),
        "Fireworks AI" to listOf("api.fireworks.ai"),
        "Mancer" to listOf("api.mancer.tech"),
        "DreamGen" to listOf("dreamgen.com")
    )

    // True for endpoints that live on this machine or the local network:
    // they need no API key and are free at the API layer.
    fun isLocalEndpoint(baseUrl: String?): Boolean {
        val trimmed = baseUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        return trimmed.contains("localhost", ignoreCase = true) ||
            trimmed.contains("127.0.0.1") ||
            trimmed.contains("10.") ||
            trimmed.contains("192.168.") ||
            Regex("https?://172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(trimmed)
    }

    fun detectProviderFromBaseUrl(baseUrl: String?): String {
        val trimmed = baseUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return "OAI-Compatible"
        providerHostHints.forEach { (provider, hosts) ->
            if (hosts.any { trimmed.contains(it, ignoreCase = true) }) return provider
        }
        return "OAI-Compatible"
    }
}

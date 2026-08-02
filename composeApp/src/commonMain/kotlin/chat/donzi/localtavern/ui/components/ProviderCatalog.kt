package chat.donzi.localtavern.ui.components

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
}

package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory cache of cloud prices fetched live from OpenRouter's public
 * models endpoint (see LivePricingFetcher). Entries carry the OpenRouter id
 * ("openai/gpt-4o") as pattern, so lookups match both full ids and plain
 * model names prefixed with the connection's provider.
 *
 * The revision flow lets the UI recompute cost readouts once fresh prices
 * land. When nothing was fetched yet (offline, still loading) callers fall
 * back to the bundled catalog.
 */
object LivePricingCatalog {

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var entries: List<ModelPricing> = emptyList()

    fun update(newEntries: List<ModelPricing>) {
        entries = newEntries
        _revision.value += 1
    }

    fun isLoaded(): Boolean = entries.isNotEmpty()

    fun lookup(provider: String?, model: String?): ModelPricing? {
        val trimmedModel = model?.trim().orEmpty()
        if (trimmedModel.isBlank() || entries.isEmpty()) return null
        val providerPrefix = provider?.trim()?.lowercase().orEmpty()
        val modelLower = trimmedModel.lowercase()

        // Primary pass: anchor the pattern to the start of "provider/model" (or
        // the bare id when the model already carries its prefix). Longest
        // pattern wins, so "gpt-4o" never matches "gpt-4o-mini".
        val candidate = if (modelLower.contains("/")) {
            modelLower
        } else if (providerPrefix.isNotBlank()) {
            "$providerPrefix/$modelLower"
        } else {
            modelLower
        }
        val anchored = entries
            .filter { candidate.startsWith(it.modelPattern.lowercase()) }
            .sortedByDescending { it.modelPattern.length }
        if (anchored.isNotEmpty()) return anchored.first()

        // Fallback for vendor-prefix mismatches (provider "Gemini" vs id prefix
        // "google", "xAI" vs "x-ai", "Mistral" vs "mistralai"): match the model
        // part alone, still longest-pattern-first.
        val modelPart = modelLower.substringAfterLast('/')
        val modelOnly = entries
            .mapNotNull { entry ->
                val pattern = entry.modelPattern.lowercase()
                val entryModel = pattern.substringAfterLast('/')
                if (entryModel.isNotEmpty() && modelPart.startsWith(entryModel)) entry else null
            }
            .sortedByDescending { it.modelPattern.length }
        return modelOnly.firstOrNull()
    }
}

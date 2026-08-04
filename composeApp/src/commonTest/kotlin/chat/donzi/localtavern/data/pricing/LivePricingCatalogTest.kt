package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LivePricingCatalogTest {

    // Fixtures mirror the bundled catalog prices for shared models, so these
    // tests can never shift the outcome of the bundled-catalog tests no
    // matter the execution order (LivePricingCatalog is a shared singleton).
    private fun entries() = listOf(
        ModelPricing("openai", "openai/gpt-4o", 2.5, 10.0, "USD"),
        ModelPricing("openai", "openai/gpt-4o-mini", 0.15, 0.6, "USD"),
        ModelPricing("openai", "openai/gpt-5", 1.25, 10.0, "USD"),
        ModelPricing("deepseek", "deepseek/deepseek-chat", 0.27, 1.1, "USD"),
        ModelPricing("google", "google/gemini-2.5-flash", 0.3, 2.5, "USD")
    )

    @Test
    fun lookup_providerPrefixedModelMatch() {
        LivePricingCatalog.update(entries())
        // gpt-5 exists only in the live catalog.
        assertEquals(1.25, LivePricingCatalog.lookup("OpenAI", "gpt-5")?.inputPerMillion)
        assertEquals(10.0, LivePricingCatalog.lookup("OpenAI", "gpt-5")?.outputPerMillion)
    }

    @Test
    fun lookup_mostSpecificPatternWins() {
        LivePricingCatalog.update(entries())
        assertEquals(0.15, LivePricingCatalog.lookup("OpenAI", "gpt-4o-mini")?.inputPerMillion)
        assertEquals(2.5, LivePricingCatalog.lookup("OpenAI", "gpt-4o")?.inputPerMillion)
    }

    @Test
    fun lookup_fullIdModelMatchesDirectly() {
        LivePricingCatalog.update(entries())
        // OpenRouter-style connections carry the provider prefix in the model.
        assertEquals(1.1, LivePricingCatalog.lookup("OpenRouter", "deepseek/deepseek-chat")?.outputPerMillion)
    }

    @Test
    fun lookup_vendorPrefixMismatchFallsBackToModelPart() {
        LivePricingCatalog.update(entries())
        // Connection provider "Gemini" vs the live id prefix "google".
        assertEquals(0.3, LivePricingCatalog.lookup("Gemini", "gemini-2.5-flash")?.inputPerMillion)
    }

    @Test
    fun lookup_modelOnlyMatchWithoutProvider() {
        LivePricingCatalog.update(entries())
        assertEquals(2.5, LivePricingCatalog.lookup(null, "gpt-4o")?.inputPerMillion)
    }

    @Test
    fun lookup_unknownModelReturnsNull() {
        LivePricingCatalog.update(entries())
        assertNull(LivePricingCatalog.lookup("OpenAI", "not-a-real-model"))
    }

    @Test
    fun lookup_emptyCatalogReturnsNull() {
        LivePricingCatalog.update(emptyList())
        assertNull(LivePricingCatalog.lookup("OpenAI", "gpt-4o"))
    }
}

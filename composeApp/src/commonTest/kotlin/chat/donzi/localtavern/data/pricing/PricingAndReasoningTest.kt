package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.network.isOpenAIEffortModel
import chat.donzi.localtavern.data.network.isReasoningModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PricingCatalogTest {

    @Test
    fun lookup_exactModelMatch() {
        val price = PricingCatalog.lookup("OpenAI", "gpt-4o")
        assertEquals(2.5, price?.inputPerMillion)
        assertEquals(10.0, price?.outputPerMillion)
    }

    @Test
    fun lookup_mostSpecificPatternWins() {
        // "gpt-4o" must not match "gpt-4o-mini" (and vice versa).
        val mini = PricingCatalog.lookup("OpenAI", "gpt-4o-mini")
        assertEquals(0.15, mini?.inputPerMillion)
        val full = PricingCatalog.lookup("OpenAI", "gpt-4o")
        assertEquals(2.5, full?.inputPerMillion)
    }

    @Test
    fun lookup_unknownModelReturnsNull() {
        assertNull(PricingCatalog.lookup("OpenAI", "definitely-not-a-model"))
        assertNull(PricingCatalog.lookup("UnknownProvider", "anything"))
    }

    @Test
    fun lookup_localProviderIsZeroCost() {
        val price = PricingCatalog.lookup("Ollama", "llama3")
        assertEquals(0.0, price?.inputPerMillion)
        assertEquals(0.0, price?.outputPerMillion)
    }

    @Test
    fun lookup_ignoresCaseInProvider() {
        assertEquals(2.5, PricingCatalog.lookup("openai", "gpt-4o")?.inputPerMillion)
    }

    @Test
    fun lookup_matchesDateSuffixedModelIds() {
        // Real API model ids carry date/version suffixes; the catalog pattern
        // is a prefix, so these must resolve instead of silently returning null.
        val gptMini = PricingCatalog.lookup("OpenAI", "gpt-4o-mini-2024-07-18")
        assertEquals(0.15, gptMini?.inputPerMillion)
        val sonnet = PricingCatalog.lookup("Anthropic", "claude-3-5-sonnet-20241022")
        assertEquals(3.0, sonnet?.inputPerMillion)
    }

    @Test
    fun lookup_prefixMatchStillSelectsLongestPattern() {
        // A name that is a prefix of TWO catalog entries picks the longest
        // (most specific) pattern, not the shortest.
        val price = PricingCatalog.lookup("OpenAI", "gpt-4o-mini-2024-07-18")
        assertEquals(0.15, price?.inputPerMillion)
        // And a bare "gpt-4o" must still NOT match the mini entry.
        assertEquals(2.5, PricingCatalog.lookup("OpenAI", "gpt-4o")?.inputPerMillion)
    }

    @Test
    fun lookup_suffixOnlyDoesNotMatch() {
        // The prefix anchor must not match mid-name: "o-mini-2024" is not a
        // "gpt-4o-mini" model.
        assertNull(PricingCatalog.lookup("OpenAI", "not-gpt-4o-mini-2024-07-18"))
    }

    @Test
    fun resolve_overrideWinsOverCatalog() {
        val override = chat.donzi.localtavern.data.database.ModelPricing("OpenAI", "gpt-4o", 9.9, 9.9, "USD")
        val resolved = PricingCatalog.resolve("OpenAI", "gpt-4o", override)
        assertEquals(9.9, resolved?.inputPerMillion)
    }
}

class CostEstimatorTest {

    @Test
    fun estimate_computesCostsFromTokenCounts() {
        val pricing = chat.donzi.localtavern.data.database.ModelPricing("p", "m", 2.0, 8.0, "USD")
        // 1M input tokens at $2 + 1M output tokens at $8 = $10.
        val estimate = CostEstimator.estimate(pricing, 500_000, 500_000)
        assertEquals(1.0, estimate?.inputCostUsd)
        assertEquals(4.0, estimate?.outputCostUsd)
        assertEquals(5.0, estimate?.totalUsd)
    }

    @Test
    fun estimate_nullPricingReturnsNull() {
        assertNull(CostEstimator.estimate(null, 100, 100))
    }

    @Test
    fun formatUsd_handlesSubCentAndZero() {
        assertEquals("$0.00", CostEstimator.formatUsd(0.0))
        assertEquals("$0.00", CostEstimator.formatUsd(-1.0))
        assertEquals("$0.0042", CostEstimator.formatUsd(0.0042))
        assertEquals("$0.12", CostEstimator.formatUsd(0.123))
        assertEquals("$1.23", CostEstimator.formatUsd(1.234))
    }
}

class ReasoningDetectionTest {

    @Test
    fun detectsKnownReasoningModels() {
        assertTrue(isReasoningModel("deepseek-reasoner"))
        assertTrue(isReasoningModel("deepseek-r1"))
        assertTrue(isReasoningModel("o1"))
        assertTrue(isReasoningModel("o3-mini"))
        assertTrue(isReasoningModel("my-model-reasoning"))
        assertTrue(isReasoningModel("deepseek-r1-0528"))
    }

    @Test
    fun doesNotFlagPlainModels() {
        assertFalse(isReasoningModel("gpt-4o"))
        assertFalse(isReasoningModel("claude-sonnet-4"))
        assertFalse(isReasoningModel("llama-3.1-70b"))
        assertFalse(isReasoningModel(""))
    }

    @Test
    fun oSeriesGetsEffortParam() {
        assertTrue(isOpenAIEffortModel("o1"))
        assertTrue(isOpenAIEffortModel("o4-mini"))
        assertFalse(isOpenAIEffortModel("deepseek-reasoner"))
        assertFalse(isOpenAIEffortModel("gpt-4o"))
    }
}

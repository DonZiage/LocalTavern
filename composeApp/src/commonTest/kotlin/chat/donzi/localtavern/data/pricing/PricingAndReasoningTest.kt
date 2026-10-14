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
    fun lookup_localProviderReturnsNull() {
        // Local inference has no price info at all: lookups must resolve to
        // null so every cost readout stays hidden for local models.
        assertNull(PricingCatalog.lookup("Ollama", "llama3"))
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
    fun estimateMaxPromptCost_usesBundledCatalogAndConnectionLimits() {
        // gpt-4o: $2.50 input / $10.00 output per 1M tokens.
        val estimate = CostEstimator.estimateMaxPromptCost("OpenAI", "gpt-4o", contextLimit = 100_000, responseLimit = 2_000)
        assertEquals(0.25, estimate?.inputCostUsd)
        assertEquals(0.02, estimate?.outputCostUsd)
        assertEquals(0.27, estimate?.totalUsd)
    }

    @Test
    fun estimateMaxPromptCost_unboundedLimitsUseAppBounds() {
        // 0 context = app-wide 1M-token cap, 0 response = 8192-token proxy.
        val estimate = CostEstimator.estimateMaxPromptCost("OpenAI", "gpt-4o-mini", contextLimit = 0, responseLimit = 0)
        assertEquals(1_000_000, estimate?.inputTokens)
        assertEquals(8_192, estimate?.outputTokens)
    }

    @Test
    fun estimateMaxPromptCost_unknownModelReturnsNull() {
        assertNull(CostEstimator.estimateMaxPromptCost("OpenAI", "not-a-real-model", 1000, 1000))
        assertNull(CostEstimator.estimateMaxPromptCost("Unknown", "anything", 1000, 1000))
    }

    @Test
    fun estimateMaxPromptCost_localProviderReturnsNull() {
        // No price info for local models: the estimator must return null so
        // the UI hides the readout entirely.
        assertNull(CostEstimator.estimateMaxPromptCost("Ollama", "llama3", 100_000, 100_000))
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

package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing
import chat.donzi.localtavern.utils.Tokenizer

data class CostEstimate(
    val inputTokens: Long,
    val outputTokens: Long,
    val inputCostUsd: Double,
    val outputCostUsd: Double,
    val totalUsd: Double
) {
    companion object {
        val ZERO = CostEstimate(0, 0, 0.0, 0.0, 0.0)
    }
}

object CostEstimator {

    // A "Max prompt cost" needs concrete token counts. When a connection runs
    // with an unlimited context (0) the app clamps it to 1M tokens, and an
    // unlimited response is proxied as 8192 tokens by the generation runner —
    // mirror those app-wide bounds so the ceiling stays finite and honest.
    private const val UNBOUNDED_CONTEXT_TOKENS = 1_000_000L
    private const val UNBOUNDED_RESPONSE_TOKENS = 8_192L

    fun estimate(
        pricing: ModelPricing?,
        inputTokens: Long,
        outputTokens: Long
    ): CostEstimate? {
        if (pricing == null) return null
        val inputCost = inputTokens / 1_000_000.0 * pricing.inputPerMillion
        val outputCost = outputTokens / 1_000_000.0 * pricing.outputPerMillion
        return CostEstimate(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            inputCostUsd = inputCost,
            outputCostUsd = outputCost,
            totalUsd = inputCost + outputCost
        )
    }

    // Worst-case cost of one request for the current connection: the whole
    // context budget filled with prompt tokens plus a full response. Pricing
    // is resolved automatically — live cloud prices when fetched, the bundled
    // catalog otherwise — and is null for local models (no price to show).
    fun estimateMaxPromptCost(
        provider: String?,
        model: String?,
        contextLimit: Long,
        responseLimit: Long
    ): CostEstimate? {
        val pricing = PricingResolver.lookup(provider, model) ?: return null
        val maxInput = if (contextLimit <= 0L) UNBOUNDED_CONTEXT_TOKENS else contextLimit
        val maxOutput = if (responseLimit <= 0L) UNBOUNDED_RESPONSE_TOKENS else responseLimit
        return estimate(pricing, maxInput, maxOutput)
    }

    fun estimateWithTokenizer(
        pricing: ModelPricing?,
        promptText: String,
        responseText: String,
        tokenizer: Tokenizer
    ): CostEstimate? =
        estimate(pricing, tokenizer.countTokens(promptText).toLong(), tokenizer.countTokens(responseText).toLong())

    /** "$0.0042"; "$0.00" for free (local) endpoints. */
    fun formatUsd(cost: Double): String {
        if (cost <= 0.0) return "$0.00"
        return if (cost < 0.01) {
            // Four decimals for sub-cent estimates, zero-padded.
            val tenThousandths = (cost * 10000.0).roundToLong().coerceAtLeast(1)
            val whole = tenThousandths / 10000
            val frac = tenThousandths % 10000
            val fracPadded = frac.toString().padStart(4, '0')
            "$$whole.$fracPadded"
        } else {
            val cents = (cost * 100.0).roundToLong()
            val dollars = cents / 100
            val frac = cents % 100
            val fracPadded = frac.toString().padStart(2, '0')
            "$$dollars.$fracPadded"
        }
    }
}

private fun Double.roundToLong(): Long = (this + 0.5).toLong()

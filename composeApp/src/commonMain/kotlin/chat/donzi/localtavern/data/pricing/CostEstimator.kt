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

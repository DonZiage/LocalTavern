package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing

/**
 * Resolves the effective price for a provider/model: live cloud prices first
 * (LivePricingCatalog), the bundled catalog as the offline fallback. Local
 * inference endpoints report no price at all (null), so every cost readout
 * is hidden for them instead of showing a meaningless $0.00.
 */
object PricingResolver {

    fun lookup(provider: String?, model: String?): ModelPricing? {
        if (PricingCatalog.isLocalProvider(provider)) return null
        return LivePricingCatalog.lookup(provider, model) ?: PricingCatalog.lookup(provider, model)
    }
}

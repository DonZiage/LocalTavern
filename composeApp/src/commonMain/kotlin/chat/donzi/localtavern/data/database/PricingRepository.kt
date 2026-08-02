package chat.donzi.localtavern.data.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class PricingRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseRepository(database) {

    suspend fun getAllPricing(): List<ModelPricing> = withContext(ioDispatcher) {
        queries.selectAllModelPricing().executeAsList()
    }

    suspend fun getPricing(provider: String, modelPattern: String): ModelPricing? = withContext(ioDispatcher) {
        queries.selectModelPricing(provider, modelPattern).executeAsOneOrNull()
    }

    suspend fun upsertPricing(pricing: ModelPricing) = withContext(ioDispatcher) {
        queries.insertModelPricing(
            provider = pricing.provider,
            modelPattern = pricing.modelPattern,
            inputPerMillion = pricing.inputPerMillion,
            outputPerMillion = pricing.outputPerMillion,
            currency = pricing.currency
        )
    }

    suspend fun deletePricing(provider: String, modelPattern: String) = withContext(ioDispatcher) {
        queries.deleteModelPricing(provider, modelPattern)
    }
}

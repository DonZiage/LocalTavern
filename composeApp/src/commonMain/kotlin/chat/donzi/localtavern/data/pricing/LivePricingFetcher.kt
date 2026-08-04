package chat.donzi.localtavern.data.pricing

import chat.donzi.localtavern.data.database.ModelPricing
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

// OpenRouter's public models endpoint lists every routable model with live
// USD-per-token pricing, so a single fetch covers most cloud providers the
// app connects to directly.
private const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

@Serializable
private data class OpenRouterModelsResponse(val data: List<OpenRouterModel> = emptyList())

@Serializable
private data class OpenRouterModel(val id: String = "", val pricing: OpenRouterPricing? = null)

@Serializable
private data class OpenRouterPricing(val prompt: String? = null, val completion: String? = null)

/**
 * Fetches the live cloud price list and maps it into the catalog's
 * ModelPricing shape (USD per 1M tokens). Failures (offline, endpoint down)
 * yield an empty list so callers keep their previous cache and the bundled
 * catalog fallback.
 */
class LivePricingFetcher(private val httpClient: HttpClient) {

    suspend fun fetch(): List<ModelPricing> {
        return try {
            val response = httpClient.get(OPENROUTER_MODELS_URL) {
                timeout { requestTimeoutMillis = 15_000 }
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            val body = response.body<OpenRouterModelsResponse>()
            body.data.mapNotNull { model ->
                val price = model.pricing ?: return@mapNotNull null
                val promptPerToken = price.prompt?.toDoubleOrNull() ?: return@mapNotNull null
                val completionPerToken = price.completion?.toDoubleOrNull() ?: return@mapNotNull null
                val parts = model.id.split("/")
                if (parts.size < 2) return@mapNotNull null
                ModelPricing(
                    provider = parts[0],
                    modelPattern = model.id,
                    inputPerMillion = promptPerToken * 1_000_000.0,
                    outputPerMillion = completionPerToken * 1_000_000.0,
                    currency = "USD"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}

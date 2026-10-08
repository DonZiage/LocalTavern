package chat.donzi.localtavern.data.pricing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

// Red-team: non-finite pricing strings must be dropped, never stored.
class LivePricingNaNRedTeamTest {

    private fun fetcherWith(body: String): LivePricingFetcher {
        val engine = MockEngine(
            MockEngineConfig().apply {
                addHandler {
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        )
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return LivePricingFetcher(http)
    }

    @Test
    fun nanAndInfinitePrices_areFiltered() = runTest {
        val fetcher = fetcherWith(
            """{"data":[
                {"id":"openai/gpt-4o","pricing":{"prompt":"NaN","completion":"Infinity"}},
                {"id":"openai/good","pricing":{"prompt":"0.0000025","completion":"0.00001"}}
            ]}"""
        )
        val result = fetcher.fetch()
        assertTrue(result.none { it.modelPattern == "openai/gpt-4o" }, "non-finite pricing must be dropped, got: $result")
        assertTrue(result.any { it.modelPattern == "openai/good" })
        assertTrue(result.all { it.inputPerMillion.isFinite() && it.outputPerMillion.isFinite() })
    }
}

package chat.donzi.localtavern.data.pricing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePricingFetcherTest {

    private fun client(respondingWith: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    respondingWith,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    @Test
    fun fetch_parsesPricesAsUsdPerMillionTokens() = runTest {
        val body = """{
            "data": [
                {"id": "openai/gpt-4o", "pricing": {"prompt": "0.0000025", "completion": "0.00001"}},
                {"id": "deepseek/deepseek-chat", "pricing": {"prompt": "0.00000027", "completion": "0.0000011"}}
            ]
        }"""
        val entries = LivePricingFetcher(client(body)).fetch()
        assertEquals(2, entries.size)
        val gpt4o = entries.first { it.modelPattern == "openai/gpt-4o" }
        assertEquals("openai", gpt4o.provider)
        assertEquals(2.5, gpt4o.inputPerMillion, absoluteTolerance = 1e-9)
        assertEquals(10.0, gpt4o.outputPerMillion, absoluteTolerance = 1e-9)
    }

    @Test
    fun fetch_httpErrorReturnsEmptyList() = runTest {
        val entries = LivePricingFetcher(client("nope", HttpStatusCode.InternalServerError)).fetch()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun fetch_skipsEntriesWithoutPricing() = runTest {
        val body = """{
            "data": [
                {"id": "openai/gpt-4o", "pricing": null},
                {"id": "x-ai/grok-3", "pricing": {"prompt": "0.000003", "completion": "0.000015"}}
            ]
        }"""
        val entries = LivePricingFetcher(client(body)).fetch()
        assertEquals(1, entries.size)
        assertEquals("x-ai/grok-3", entries[0].modelPattern)
        assertEquals(15.0, entries[0].outputPerMillion, absoluteTolerance = 1e-9)
    }
}

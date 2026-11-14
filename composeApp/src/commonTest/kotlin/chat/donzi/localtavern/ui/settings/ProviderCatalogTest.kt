package chat.donzi.localtavern.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderCatalogTest {

    @Test
    fun knownEndpoints_areNamedByTheirProvider() {
        assertEquals("OpenAI", ProviderCatalog.displayNameForEndpoint("https://api.openai.com/v1"))
        assertEquals("OpenRouter", ProviderCatalog.displayNameForEndpoint("https://openrouter.ai/api/v1"))
        assertEquals("Ollama", ProviderCatalog.displayNameForEndpoint("http://localhost:11434/v1"))
        // Surrounding whitespace must not hide the option.
        assertEquals("Anthropic", ProviderCatalog.displayNameForEndpoint("  https://api.anthropic.com/v1 "))
    }

    @Test
    fun customEndpoints_haveNoName() {
        // A hand-typed endpoint is not an option: it is shown as it is.
        assertNull(ProviderCatalog.displayNameForEndpoint("https://my-gateway.example.com/v1"))
        assertNull(ProviderCatalog.displayNameForEndpoint("http://192.168.1.10:8080/v1"))
        assertNull(ProviderCatalog.displayNameForEndpoint(""))
        assertNull(ProviderCatalog.displayNameForEndpoint(null))
    }

    @Test
    fun anEndpointSharedByTwoOptions_namesNeither() {
        // TabbyAPI and Oobabooga default to the same endpoint: the endpoint
        // stands for both, so it names neither of them.
        assertEquals("http://localhost:5000/v1", ProviderCatalog.defaultUrls["TabbyAPI"])
        assertEquals("http://localhost:5000/v1", ProviderCatalog.defaultUrls["Oobabooga"])
        assertNull(ProviderCatalog.displayNameForEndpoint("http://localhost:5000/v1"))
    }

    @Test
    fun everyProviderOption_hasAnEndpoint() {
        // Every option of the catalog must resolve to an endpoint, otherwise
        // picking it would leave the endpoint empty.
        ProviderCatalog.providerSections.forEach { (_, providers) ->
            providers.forEach { provider ->
                val url = ProviderCatalog.defaultUrls[provider]
                if (provider != "OAI-Compatible") {
                    assertNotNull(url, "$provider must have a default endpoint")
                }
            }
        }
    }
}

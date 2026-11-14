package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The provider options of the endpoint picker must be listed by their name
// only: the URL an option stands for is an implementation detail and must
// never be shown next to it.
@OptIn(ExperimentalTestApi::class)
class ProviderOptionsTest {

    @Test
    fun options_areListedByNameOnly() = runComposeUiTest {
        setContent {
            // The options live in a list (the dropdown menu column).
            Column {
                ProviderOptions { _, _ -> }
            }
        }

        // The names of the options are what the user picks.
        onNodeWithText("AI21").assertExists()
        onNodeWithText("Anthropic").assertExists()
        onNodeWithText("OpenRouter").assertExists()
        onNodeWithText("Ollama").assertExists()
        onNodeWithText("Cloud Inference").assertExists()
        onNodeWithText("Local Inference").assertExists()

        // No option may leak the endpoint it stands for.
        onAllNodes(hasText("://", substring = true)).assertCountEquals(0)
    }

    @Test
    fun pickingAnOption_appliesItsEndpoint() = runComposeUiTest {
        var selectedProvider: String? = null
        var selectedUrl: String? = null
        setContent {
            // The options live in a list (the dropdown menu column).
            Column {
                ProviderOptions { provider, url ->
                    selectedProvider = provider
                    selectedUrl = url
                }
            }
        }

        onNodeWithText("Anthropic").performClick()

        // The option is picked by name, but it applies the real endpoint.
        assertEquals("Anthropic", selectedProvider)
        assertEquals("https://api.anthropic.com/v1", selectedUrl)
    }
}

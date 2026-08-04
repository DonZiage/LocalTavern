package chat.donzi.localtavern.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCapabilitiesTest {

    @Test
    fun `auto mode picks chat completions for modern models`() {
        assertTrue(effectiveChatCompletion(0, "gpt-4o", "OpenRouter", "https://openrouter.ai/api/v1"))
        assertTrue(effectiveChatCompletion(0, "deepseek/deepseek-chat", "OpenRouter", null))
        assertTrue(effectiveChatCompletion(0, "mistralai/mistral-7b-instruct", null, null))
        assertTrue(effectiveChatCompletion(0, "ollama-llama3", "Ollama", "http://localhost:11434"))
    }

    @Test
    fun `auto mode picks legacy completions for text-davinci era models`() {
        assertFalse(effectiveChatCompletion(0, "text-davinci-003", "OpenAI", "https://api.openai.com/v1"))
        assertFalse(effectiveChatCompletion(0, "openai/text-davinci-003", "OpenRouter", null))
        assertFalse(effectiveChatCompletion(0, "davinci-002", "OpenAI", null))
        assertFalse(effectiveChatCompletion(0, "gpt-3.5-turbo-instruct", "OpenAI", null))
    }

    @Test
    fun `explicit mode wins over auto`() {
        assertTrue(effectiveChatCompletion(1, "text-davinci-003", null, null))
        assertFalse(effectiveChatCompletion(2, "gpt-4o", null, null))
    }

    @Test
    fun `anthropic endpoints always use the messages route`() {
        // The boolean is irrelevant for Anthropic: endpointFor short-circuits.
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            endpointFor("https://api.anthropic.com/v1", ApiStyle.Anthropic, isChatCompletion = false)
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            endpointFor("https://api.anthropic.com/v1", ApiStyle.Anthropic, isChatCompletion = true)
        )
    }

    @Test
    fun `isLegacyCompletionModel matches completion-only ids`() {
        assertTrue(isLegacyCompletionModel("text-davinci-003"))
        assertTrue(isLegacyCompletionModel("openai/curie-001"))
        assertTrue(isLegacyCompletionModel("babbage"))
        assertFalse(isLegacyCompletionModel("gpt-4o"))
        assertFalse(isLegacyCompletionModel("deepseek-r1-instruct"))
        assertFalse(isLegacyCompletionModel(null))
    }
}

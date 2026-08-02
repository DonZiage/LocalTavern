package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.PromptBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextManagerTest {

    private val character = Character(
        id = "char1",
        name = "Alice",
        description = "A curious explorer",
        personality = "Brave and kind",
        scenario = "Deep forest",
        firstMes = "Hello there!",
        mesExample = listOf("Example: *waves*"),
        creatorNotes = null,
        altGreetings = listOf("Greetings!"),
        avatarData = null
    )

    private val persona = Persona(
        id = "p1",
        name = "Bob",
        description = "A cautious scholar",
        avatarData = null
    )

    private fun block(template: String, isEnabled: Boolean = true) = PromptBlock(
        id = "b_$template.hashCode()",
        name = "block",
        isEnabled = isEnabled,
        template = template
    )

    @Test
    fun replaceSimpleMacros_substitutesCharAndUser() {
        val result = ContextManager.replaceSimpleMacros("Hi {{char}}, I'm {{user}}.", "Alice", "Bob")
        assertEquals("Hi Alice, I'm Bob.", result)
    }

    @Test
    fun buildPayload_placeholdersAreFilled() {
        val result = ContextManager.buildPayload(
            blocks = listOf(
                block("System: {{user_persona}} talks to {{char}}"),
                block("{{chat_history}}")
            ),
            character = character,
            persona = persona,
            chatHistory = listOf(ChatMessage(role = "user", content = "Hello")),
            contextLimit = 4096,
            responseLimit = 256
        )

        assertEquals(2, result.size)
        val system = result[0]
        assertTrue(system.role == "system")
        assertTrue(system.content.contains("A cautious scholar"))
        assertTrue(system.content.contains("Alice"))
    }

    @Test
    fun buildPayload_disabledBlocksAreExcluded() {
        val result = ContextManager.buildPayload(
            blocks = listOf(
                block("INCLUDE ME"),
                block("EXCLUDE ME", isEnabled = false)
            ),
            character = character,
            persona = persona,
            chatHistory = emptyList(),
            contextLimit = 4096,
            responseLimit = 256
        )
        assertTrue(result[0].content.contains("INCLUDE ME"))
        assertTrue(!result[0].content.contains("EXCLUDE ME"))
    }

    @Test
    fun buildPayload_truncatesHistoryToFitContext() {
        val longHistory = (1..200).map { i ->
            ChatMessage(role = if (i % 2 == 0) "user" else "assistant", content = "Message number $i with some padding text.")
        }

        val result = ContextManager.buildPayload(
            blocks = listOf(block("{{chat_history}}")),
            character = character,
            persona = persona,
            chatHistory = longHistory,
            contextLimit = 400,
            responseLimit = 100
        )

        val totalBudget = 400 - 100 - 50
        val used = result.sumOf { DefaultTokenizer.countTokens(it.content) + 4 }
        assertTrue(used <= totalBudget + 4, "History exceeded token budget: $used > $totalBudget")
        assertTrue(result.size < longHistory.size, "History should have been truncated")
    }

    @Test
    fun buildPayload_neverReturnsEmptyPayload() {
        val result = ContextManager.buildPayload(
            blocks = listOf(block("{{chat_history}}")),
            character = character,
            persona = persona,
            chatHistory = emptyList(),
            contextLimit = 4096,
            responseLimit = 256
        )
        assertEquals(1, result.size, "A payload with no history and only a chat-history block must still produce a message")
        assertEquals("system", result[0].role)
    }

    @Test
    fun buildPayload_allBlocksBlankFallsBackToDefaultSystemPrompt() {
        val result = ContextManager.buildPayload(
            blocks = listOf(block("   ")),
            character = character,
            persona = persona,
            chatHistory = emptyList(),
            contextLimit = 4096,
            responseLimit = 256
        )
        assertEquals(1, result.size)
        assertTrue(result[0].content.isNotBlank())
    }

    @Test
    fun buildPayload_imagesConsumeTokenBudget() {
        val history = listOf(
            ChatMessage(role = "user", content = "Older with image", images = listOf(ImageAttachment("aGk=", "image/png"))),
            ChatMessage(role = "user", content = "Newer text only", images = emptyList())
        )

        // availableTokens = 1050 - 50 (safe buffer) = 1000: enough for the
        // text-only message (~7 tokens) but not the image message (~1508).
        // A text message that fits must win over an image message that does not.
        val result = ContextManager.buildPayload(
            blocks = listOf(block("{{chat_history}}")),
            character = character,
            persona = persona,
            chatHistory = history,
            contextLimit = 1050,
            responseLimit = 0
        )

        assertEquals(1, result.size, "Image tokens must count against the context budget")
        assertEquals("Newer text only", result[0].content)
        assertEquals(0, result[0].images.size)
    }

    @Test
    fun buildPayload_unlimitedContextKeepsSystemPromptAndFullHistory() {
        val history = listOf(
            ChatMessage(role = "user", content = "Hello"),
            ChatMessage(role = "assistant", content = "Hi there")
        )
        val result = ContextManager.buildPayload(
            blocks = listOf(block("System: {{user_persona}} talks to {{char}}")),
            character = character,
            persona = persona,
            chatHistory = history,
            contextLimit = 0,
            responseLimit = 256
        )

        assertEquals(3, result.size, "Unlimited context must keep system prompt and all history")
        assertEquals("system", result[0].role)
        assertTrue(result[0].content.contains("A cautious scholar"))
        assertTrue(result[0].content.contains("Alice"))
        assertEquals("Hello", result[1].content)
        assertEquals("Hi there", result[2].content)
    }

    @Test
    fun buildPayload_unlimitedResponseWithSmallContextStillWorks() {
        val result = ContextManager.buildPayload(
            blocks = listOf(block("System: hello")),
            character = character,
            persona = persona,
            chatHistory = listOf(ChatMessage(role = "user", content = "Hello")),
            contextLimit = 4096,
            responseLimit = 0
        )

        assertEquals(2, result.size, "Response limit of 0 (unlimited) must not break payload")
        assertTrue(result[0].content.contains("System: hello"))
        assertEquals("Hello", result[1].content)
    }

    @Test
    fun buildPayload_oversizedContextLimitDoesNotOverflow() {
        val result = ContextManager.buildPayload(
            blocks = listOf(block("System: hello")),
            character = character,
            persona = persona,
            chatHistory = (1..50).map { ChatMessage(role = "user", content = "Message $it") },
            contextLimit = 1_000_000_000_000,
            responseLimit = 256
        )

        assertEquals(51, result.size, "Huge context limits must not overflow the token budget")
        assertEquals("system", result.first().role)
        assertEquals("Message 50", result.last().content)
    }
}

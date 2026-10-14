package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.ImageRef
import chat.donzi.localtavern.domain.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatExportTest {

    private fun message(
        id: String = "m1",
        role: String = "user",
        content: String = "Hello",
        images: List<ByteArray> = emptyList(),
        imageRefs: List<ImageRef> = emptyList(),
        reasoningText: String? = null
    ) = Message(
        id = id, sessionId = "s1", role = role, content = content,
        timestamp = 1_000L, parentId = null, isActivePath = true,
        images = images, imageRefs = imageRefs, reasoningText = reasoningText
    )

    @Test
    fun markdown_rendersSpeakersAndBody() {
        val out = ChatExport.formatMarkdown(
            sessionTitle = "First chat",
            characterName = "Alice",
            personaName = "Bob",
            messages = listOf(
                message(role = "user", content = "Hi Alice"),
                message(id = "m2", role = "assistant", content = "Hi Bob!")
            )
        )
        assertTrue(out.startsWith("# First chat\n"), "Must carry the session title, got:\n$out")
        assertTrue("## Bob\n\nHi Alice" in out, "User block must use the persona name, got:\n$out")
        assertTrue("## Alice\n\nHi Bob!" in out, "Assistant block must use the character name, got:\n$out")
    }

    @Test
    fun markdown_fallsBackToUntitledChat() {
        val out = ChatExport.formatMarkdown(
            sessionTitle = null, characterName = "Alice", personaName = "Bob",
            messages = listOf(message())
        )
        assertTrue(out.startsWith("# Chat with Alice\n"), "Blank title must fall back, got:\n$out")
    }

    @Test
    fun markdown_annotatesImagesAndReasoning() {
        val out = ChatExport.formatMarkdown(
            sessionTitle = "t", characterName = "Alice", personaName = "Bob",
            messages = listOf(
                message(
                    id = "m2", role = "assistant", content = "See this",
                    images = listOf(byteArrayOf(1, 2, 3)),
                    reasoningText = "thinking"
                )
            )
        )
        assertTrue("[1 image attached]" in out, "Image placeholder must note the count, got:\n$out")
        assertTrue("<details><summary>Reasoning</summary>" in out, "Reasoning must be collapsible, got:\n$out")
        assertTrue("thinking" in out)
    }

    @Test
    fun markdown_blankContent_isNotAnEmptyBlock() {
        val out = ChatExport.formatMarkdown(
            sessionTitle = "t", characterName = "Alice", personaName = "Bob",
            messages = listOf(message(content = ""))
        )
        assertTrue("*[no text]*" in out, "Blank content must render a placeholder, got:\n$out")
    }

    @Test
    fun text_rendersSpeakerLines() {
        val out = ChatExport.formatText(
            sessionTitle = "First chat",
            characterName = "Alice",
            personaName = "Bob",
            messages = listOf(message(role = "user", content = "Hi"))
        )
        assertTrue("Bob: Hi" in out, "Plain text must prefix the speaker, got:\n$out")
    }

    @Test
    fun fileName_isSanitizedAndSuffixed() {
        assertEquals("Hello world.md", ChatExport.transcriptFileName("Hello world", "Alice"))
        assertEquals("chat.md", ChatExport.transcriptFileName("   ", ""))
        val evil = ChatExport.transcriptFileName("a/b\\c:d*e?f\"g<h>i|j", "Alice")
        assertTrue('/' !in evil && '\\' !in evil && ':' !in evil, "Path separators must be stripped, got $evil")
        assertTrue(evil.endsWith(".md"))
        val long = ChatExport.transcriptFileName("x".repeat(200), "Alice")
        assertTrue(long.length <= 83, "File name must be capped, got ${long.length}")
    }

    @Test
    fun imageRefs_withoutBytes_stillCount() {
        val out = ChatExport.formatMarkdown(
            sessionTitle = "t", characterName = "Alice", personaName = "Bob",
            messages = listOf(
                message(
                    id = "m2", role = "assistant", content = "pending",
                    imageRefs = listOf(ImageRef("ab".repeat(32), 10L), ImageRef("cd".repeat(32), 12L))
                )
            )
        )
        assertTrue("[2 images attached]" in out, "Pending-sync refs must still be noted, got:\n$out")
    }
}

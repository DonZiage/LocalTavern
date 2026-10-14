package chat.donzi.localtavern.ui.chat

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Red-team: "1)" ordered markers must render like "1." markers.
class MarkdownOrderedParenRedTeamTest {
    private val style = MarkdownStyle(Color.Black, Color.Blue, Color.Gray)

    @Test
    fun parenMarker_rendersWithoutDuplication() {
        val blocks = parseMarkdown("1) hello", style)
        val text = blocks.joinToString("\n") { it.text.toString() }
        assertEquals("1.  hello", text.trim(), "paren marker leaked into output: '$text'")
    }

    @Test
    fun dotMarker_stillRenders() {
        val blocks = parseMarkdown("1. hello", style)
        val text = blocks.joinToString("\n") { it.text.toString() }
        assertEquals("1.  hello", text.trim())
    }

    @Test
    fun mixedParenList_rendersAllItems() {
        val blocks = parseMarkdown("1) first\n2) second", style)
        val text = blocks.joinToString("\n") { it.text.toString() }
        assertTrue("1.  first" in text, "got: '$text'")
        assertTrue("2.  second" in text, "got: '$text'")
    }
}

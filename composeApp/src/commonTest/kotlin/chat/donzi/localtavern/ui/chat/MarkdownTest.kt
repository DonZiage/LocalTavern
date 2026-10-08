package chat.donzi.localtavern.ui.chat
import chat.donzi.localtavern.ui.chat.parseMarkdown
import chat.donzi.localtavern.ui.chat.MarkdownStyle
import chat.donzi.localtavern.ui.chat.MarkdownBlock

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {

    private val style = MarkdownStyle(
        textColor = Color(0xFF000000),
        accentColor = Color(0xFF123456),
        codeBackground = Color(0xFFEEEEEE)
    )

    private fun blocksOf(text: String): List<MarkdownBlock> = parseMarkdown(text, style)

    private fun AnnotatedString.hasSpan(match: (SpanStyle) -> Boolean): Boolean = spanStyles.any { match(it.item) }

    @Test
    fun paragraphs_splitOnBlankLines() {
        val blocks = blocksOf("First paragraph.\n\nSecond paragraph.")
        assertEquals(2, blocks.size)
        assertEquals("First paragraph.", blocks[0].text.text)
        assertEquals("Second paragraph.", blocks[1].text.text)
    }

    @Test
    fun plainText_hasNoSpans() {
        val block = blocksOf("Just plain words, nothing special.").single()
        assertEquals("Just plain words, nothing special.", block.text.text)
        assertTrue(block.text.spanStyles.isEmpty(), "Plain text must not carry any span styles")
    }

    @Test
    fun boldItalicStrikethroughAndCode_areStyled() {
        val block = blocksOf("**bold** *italic* ~~gone~~ `code`").single()
        assertEquals("bold italic gone code", block.text.text)
        assertTrue(block.text.hasSpan { it.fontWeight == FontWeight.Bold }, "Bold text must be bold")
        assertTrue(block.text.hasSpan { it.fontStyle == FontStyle.Italic }, "Italic text must be italic")
        assertTrue(block.text.hasSpan { it.textDecoration == TextDecoration.LineThrough }, "Strikethrough must be struck through")
        assertTrue(block.text.hasSpan { it.fontFamily == FontFamily.Monospace }, "Inline code must be monospace")
    }

    @Test
    fun pseudoEmphasis_isNotStyled() {
        val block = blocksOf("3 * 4 * 5").single()
        assertEquals("3 * 4 * 5", block.text.text)
        assertTrue(block.text.spanStyles.isEmpty(), "Arithmetic asterisks must not become italics")
    }

    @Test
    fun heading_getsLargerBoldText() {
        val blocks = blocksOf("# Title\n\n## Subtitle")
        assertEquals(2, blocks.size)
        assertEquals("Title", blocks[0].text.text)
        assertTrue(blocks[0].text.hasSpan { it.fontWeight == FontWeight.Bold })
        assertTrue(blocks[0].text.hasSpan { it.fontSize == 21.sp }, "H1 must be larger than body")
        assertEquals("Subtitle", blocks[1].text.text)
        assertTrue(blocks[1].text.hasSpan { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun heading_withoutSpace_isNotAHeading() {
        val block = blocksOf("##not a heading").single()
        assertEquals("##not a heading", block.text.text, "Missing space after hashes means plain text")
    }

    @Test
    fun fencedCodeBlock_isRawMonospace() {
        val blocks = blocksOf("```kotlin\nval x = 1\nval y = 2\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].isCodeBlock)
        assertEquals("val x = 1\nval y = 2", blocks[0].text.text, "Code content must be kept verbatim")
        assertTrue(blocks[0].text.hasSpan { it.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun unclosedFence_consumesToEnd() {
        val blocks = blocksOf("```\nstill code\nno fence here")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].isCodeBlock)
        assertEquals("still code\nno fence here", blocks[0].text.text)
    }

    @Test
    fun bulletList_rendersWithMarkers() {
        val blocks = blocksOf("- alpha\n- beta")
        assertEquals(1, blocks.size, "Consecutive bullets merge into one list")
        assertEquals("•  alpha\n•  beta", blocks[0].text.text)
        // A blank line ends the list: the next bullet starts a new list block.
        val split = blocksOf("- alpha\n- beta\n\n- gamma")
        assertEquals(2, split.size)
        assertEquals("•  gamma", split[1].text.text)
    }

    @Test
    fun orderedList_keepsNumbers() {
        val block = blocksOf("1. one\n2. two").single()
        assertEquals("1.  one\n2.  two", block.text.text)
    }

    @Test
    fun blockquote_isQuotedAndItalic() {
        val block = blocksOf("> quoted line\n> second line").single()
        assertEquals("│ quoted line\n│ second line", block.text.text)
        assertTrue(block.text.hasSpan { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun thematicBreak_rendersAsRule() {
        val blocks = blocksOf("Before\n\n---\n\nAfter")
        assertEquals(3, blocks.size)
        assertEquals("— — —", blocks[1].text.text)
    }

    @Test
    fun link_rendersStyledLabel() {
        val block = blocksOf("See [OpenAI](https://openai.com) here").single()
        assertEquals("See OpenAI here", block.text.text, "The URL itself must not leak into the visible text")
        assertTrue(block.text.hasSpan { it.color == style.accentColor && it.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun nestedMarkdown_insideListItem() {
        val block = blocksOf("- **bold item**").single()
        assertEquals("•  bold item", block.text.text)
        assertTrue(block.text.hasSpan { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun emptyInput_producesNoBlocks() {
        assertTrue(blocksOf("").isEmpty())
        assertTrue(blocksOf("\n\n").isEmpty())
    }
}

package chat.donzi.localtavern.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import chat.donzi.localtavern.domain.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSearchTest {

    private fun msg(id: String, content: String): Message =
        Message(
            id = id, sessionId = "s1", role = "user", content = content,
            timestamp = 0L, parentId = null, isActivePath = true,
            images = emptyList(), imageRefs = emptyList(),
            reasoningText = null, costEstimateUsd = null
        )

    @Test
    fun findMatches_blankQueryMatchesNothing() {
        val messages = listOf(msg("1", "hello world"))
        assertEquals(emptyList(), findSearchMatchIds(messages, ""))
        assertEquals(emptyList(), findSearchMatchIds(messages, "   "))
    }

    @Test
    fun findMatches_caseInsensitiveSubstring() {
        val messages = listOf(
            msg("1", "Hello there"),
            msg("2", "goodbye"),
            msg("3", "say HELLO again")
        )
        assertEquals(listOf("1", "3"), findSearchMatchIds(messages, "hello"))
        assertEquals(listOf("1", "3"), findSearchMatchIds(messages, "HELLO"))
        assertEquals(emptyList<String>(), findSearchMatchIds(messages, "zzz"))
    }

    @Test
    fun findMatches_trimsQueryAndKeepsTimelineOrder() {
        val messages = listOf(msg("1", "ab one"), msg("2", "cd"), msg("3", "xx ab yy"))
        assertEquals(listOf("1", "3"), findSearchMatchIds(messages, "  ab  "))
    }

    @Test
    fun navigation_wrapsAround() {
        assertEquals(1, nextSearchMatchIndex(0, 3))
        assertEquals(0, nextSearchMatchIndex(2, 3))
        assertEquals(2, previousSearchMatchIndex(0, 3))
        assertEquals(0, previousSearchMatchIndex(1, 3))
        assertEquals(0, nextSearchMatchIndex(0, 0))
        assertEquals(0, previousSearchMatchIndex(0, 0))
    }

    @Test
    fun currentMatch_clampsIndexAndHandlesEmpty() {
        assertNull(currentSearchMatchId(emptyList(), 0))
        assertEquals("b", currentSearchMatchId(listOf("a", "b"), 1))
        // Out-of-range index (e.g. matches shrank) clamps to the last match.
        assertEquals("b", currentSearchMatchId(listOf("a", "b"), 9))
    }

    @Test
    fun positionText_formatsCounter() {
        assertEquals("1 / 3", searchMatchPositionText(0, 3))
        assertEquals("3 / 3", searchMatchPositionText(2, 3))
        assertEquals("0 / 0", searchMatchPositionText(0, 0))
    }

    @Test
    fun highlightRanges_caseInsensitiveAndMultiple() {
        // "Hello hello HELLO": three non-overlapping hits.
        assertEquals(
            listOf(0..4, 6..10, 12..16),
            findHighlightRanges("Hello hello HELLO", "hello")
        )
    }

    @Test
    fun highlightRanges_trimsQueryAndHandlesNoMatch() {
        assertEquals(listOf(3..4), findHighlightRanges("xx ab yy", "  ab  "))
        assertEquals(emptyList<IntRange>(), findHighlightRanges("hello", "   "))
        assertEquals(emptyList<IntRange>(), findHighlightRanges("hello", "zzz"))
        assertEquals(emptyList<IntRange>(), findHighlightRanges("", "hello"))
    }

    @Test
    fun highlightRanges_nonOverlappingLeftToRight() {
        // "aaa" with "aa" must yield a single hit, not overlapping ones.
        assertEquals(listOf(0..1), findHighlightRanges("aaa", "aa"))
        // Empty query yields nothing even for non-empty text.
        assertEquals(emptyList<IntRange>(), findHighlightRanges("hello", ""))
    }

    @Test
    fun highlightAnnotated_preservesTextAndOriginalSpans() {
        val original = buildAnnotatedString {
            append("Say ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("HELLO") }
            append(" again hello")
        }
        val highlighted = highlightAnnotatedString(
            original,
            "hello",
            background = Color(0xFFFFEB3B),
            foreground = Color(0xFF000000),
            bold = false
        )
        assertEquals(original.text, highlighted.text)
        // The original bold span survives.
        assertTrue(highlighted.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        // Each occurrence gains a highlight background span.
        val highlightCount = highlighted.spanStyles.count { it.item.background == Color(0xFFFFEB3B) }
        assertEquals(2, highlightCount)
        val ranges = highlighted.spanStyles
            .filter { it.item.background == Color(0xFFFFEB3B) }
            .map { it.start..<it.end }
            .sortedBy { it.first }
        assertEquals(listOf(4..<9, 16..<21), ranges)
    }

    @Test
    fun highlightAnnotated_boldForCurrentMatchAndUnchangedWhenNoMatch() {
        val original = AnnotatedString("hello world")
        val highlighted = highlightAnnotatedString(
            original, "HELLO",
            background = Color(0xFFFFEB3B), foreground = Color(0xFF000000), bold = true
        )
        assertTrue(highlighted.spanStyles.any {
            it.item.background == Color(0xFFFFEB3B) && it.item.fontWeight == FontWeight.Bold
        })
        // No match returns the input unchanged.
        assertEquals(original, highlightAnnotatedString(
            original, "zzz",
            background = Color(0xFFFFEB3B), foreground = Color(0xFF000000)
        ))
        assertEquals(original, highlightAnnotatedString(
            original, "   ",
            background = Color(0xFFFFEB3B), foreground = Color(0xFF000000)
        ))
    }
}

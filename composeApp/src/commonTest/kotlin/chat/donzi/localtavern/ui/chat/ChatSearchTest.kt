package chat.donzi.localtavern.ui.chat

import chat.donzi.localtavern.domain.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}

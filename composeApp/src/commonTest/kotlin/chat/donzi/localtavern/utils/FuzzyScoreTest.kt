package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzyScoreTest {

    @Test
    fun blankQueryScoresFull() {
        assertEquals(100, "anything".fuzzyScore(""))
        assertEquals(100, "anything".fuzzyScore("   "))
    }

    @Test
    fun exactMatchScoresHighest() {
        assertEquals(1000, "OpenAI".fuzzyScore("openai"))
    }

    @Test
    fun prefixAndContainsScoreHigh() {
        assertEquals(500, "Mistral".fuzzyScore("mis"))
        assertEquals(200, "OpenRouter".fuzzyScore("router"))
    }

    @Test
    fun subsequenceMatchScoresAboveZero() {
        val score = "together-ai".fuzzyScore("tgr")
        assertTrue(score > 0, "Subsequence match should score above zero, got $score")
    }

    @Test
    fun noMatchScoresZero() {
        assertEquals(0, "OpenAI".fuzzyScore("zzzz"))
    }
}

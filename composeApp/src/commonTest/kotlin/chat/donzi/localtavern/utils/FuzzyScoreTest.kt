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

    @Test
    fun punctuationOnlyQueryScoresZero() {
        // "!!!" has no searchable characters; it must not match every
        // target at the contains-tier score.
        assertEquals(0, "anything".fuzzyScore("!!!"))
        assertEquals(0, "anything".fuzzyScore("..."))
    }

    @Test
    fun longSubsequenceDoesNotOutrankContains() {
        // A long subsequence match (240 before the cap) must not beat a real
        // contains-tier match (200).
        val target = "abcdefghijklmnopqrstuvwxyz"
        // 24 chars of the target with 'n' skipped: a subsequence, not a prefix/contains.
        val subseqScore = target.fuzzyScore("abcdefghijklmopqrstuvwxyz")
        val containsScore = target.fuzzyScore("jklmn")
        assertTrue(subseqScore < containsScore,
            "Subsequence score ($subseqScore) must stay below contains score ($containsScore)")
        assertEquals(199, subseqScore, "Subsequence score must be capped at 199")
    }
}

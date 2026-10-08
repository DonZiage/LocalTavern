package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenizerTest {

    @Test
    fun countTokens_latinUsesFourCharsPerToken() {
        assertEquals(1, DefaultTokenizer.countTokens("abcd"))
        assertEquals(2, DefaultTokenizer.countTokens("abcdefgh"))
        assertEquals(0, DefaultTokenizer.countTokens(""))
        assertEquals(0, DefaultTokenizer.countTokens("abc"))
    }

    @Test
    fun countTokens_cjkCountsOneTokenPerCharacter() {
        // CJK text must not be undercounted ~4x like Latin text, otherwise
        // the context budget lets the payload overshoot the real window.
        assertEquals(2, DefaultTokenizer.countTokens("汉字"))
        assertEquals(6, DefaultTokenizer.countTokens("中文测试文本"))
        assertTrue(DefaultTokenizer.countTokens("汉字汉字汉字汉字") > 4,
            "CJK text must cost roughly one token per character")
    }

    @Test
    fun countTokens_mixedTextAddsBothCosts() {
        val cjk = "汉字"
        val latin = "abcd"
        assertEquals(1 + 2, DefaultTokenizer.countTokens(latin + cjk),
            "Pending Latin units flush into the CJK character's token")
        assertEquals(1 + 2, DefaultTokenizer.countTokens(cjk + latin))
    }

    @Test
    fun truncateByTokens_neverExceedsBudget() {
        val text = "汉字汉字汉字汉字 汉字汉字汉字汉字 ab😀cdefghijkl"
        for (maxTokens in 1..10) {
            val truncated = DefaultTokenizer.truncateByTokens(text, maxTokens)
            assertTrue(DefaultTokenizer.countTokens(truncated) <= maxTokens,
                "Truncated text ($maxTokens) counted ${DefaultTokenizer.countTokens(truncated)}: '$truncated'")
        }
    }

    @Test
    fun truncateByTokens_keepsSurrogatePairsIntact() {
        // A budget of 1 forces the cut right before the emoji; the surrogate
        // repair must not pull in the low surrogate (which would both exceed
        // the budget and leave a lone high surrogate at the cut).
        val text = "abcdef\uD83D\uDE00ghij"
        val truncated = DefaultTokenizer.truncateByTokens(text, 1)
        assertTrue(DefaultTokenizer.countTokens(truncated) <= 1,
            "Budget exceeded: '${truncated}' counted ${DefaultTokenizer.countTokens(truncated)}")
        assertTrue(!truncated.endsWith("\uD83D"), "No lone high surrogate at the cut: '$truncated'")
    }

    @Test
    fun truncateByTokens_emojiInsideSingleTokenBudget() {
        // The pair itself fits within the single-token budget, so the whole
        // emoji must survive the cut.
        val text = "abc\uD83D\uDE00def"
        val truncated = DefaultTokenizer.truncateByTokens(text, 1)
        assertTrue(truncated.contains("\uD83D\uDE00"), "Emoji pair preserved: '$truncated'")
        assertTrue(DefaultTokenizer.countTokens(truncated) <= 1)
    }

    @Test
    fun truncateByTokens_maxTokensZeroReturnsEmpty() {
        assertEquals("", DefaultTokenizer.truncateByTokens("anything", 0))
    }

    @Test
    fun truncateByTokens_fullTextWithinBudgetIsUntouched() {
        val text = "short"
        assertEquals(text, DefaultTokenizer.truncateByTokens(text, 10))
    }
}

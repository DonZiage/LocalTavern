package chat.donzi.localtavern.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Red-team: letter rules must apply to LETTERS only, per the policy header.
class PassphrasePolicySymbolsRedTeamTest {

    @Test
    fun repeatedSymbols_areAllowed() {
        // Valid base: upper+lower+digit+symbol, no letter repeat/mirror.
        val base = "Ab3!xY9?qW"
        assertNull(PassphrasePolicy.firstIssue(base), "base should be valid, got: ${PassphrasePolicy.allIssues(base)}")
        assertNull(
            PassphrasePolicy.firstIssue(base + "!!"),
            "repeated symbols must not trigger RepeatedLetter, got: ${PassphrasePolicy.allIssues(base + "!!")}"
        )
    }

    @Test
    fun digitEndpoints_areNotLetterMirrors() {
        // '1a1' endpoints are digits: not a LETTER mirror (digits have their
        // own RepeatedDigit rule, not the letter mirror rule).
        val issues = PassphrasePolicy.allIssues("X1a1Yb2!Qz")
        assertFalse(
            PassphrasePolicy.Issue.MirroredLetters in issues,
            "digit endpoints must not be a letter mirror, got: $issues"
        )
    }

    @Test
    fun symbolEndpoints_areNotLetterMirrors() {
        // '!a!' is a palindrome whose endpoints are symbols: not a LETTER mirror.
        val issues = PassphrasePolicy.allIssues("Qb!a!Yd2?Wx")
        assertFalse(
            PassphrasePolicy.Issue.MirroredLetters in issues,
            "symbol endpoints must not be a letter mirror, got: $issues"
        )
    }

    @Test
    fun doubleSpace_isNotRepeatedLetter() {
        val issues = PassphrasePolicy.allIssues("Ab3!xY 9?qW")
        assertFalse(
            PassphrasePolicy.Issue.RepeatedLetter in issues,
            "whitespace repeat must not be a letter repeat, got: $issues"
        )
    }

    @Test
    fun genuineLetterRepeat_stillRejected() {
        assertTrue(PassphrasePolicy.Issue.RepeatedLetter in PassphrasePolicy.allIssues("Saa3-Wolf!"))
        assertTrue(PassphrasePolicy.Issue.MirroredLetters in PassphrasePolicy.allIssues("aXa3-Wolf!"))
    }
}

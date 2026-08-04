package chat.donzi.localtavern.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Strength policy for desktop passphrases: composition, digit-uniqueness,
// digit-consecutiveness, letter sequence/mirror rules, and the password
// manager guidance.
class PassphrasePolicyTest {

    // Satisfies every rule: 10 chars, upper+lower, digits (3 only once, not
    // consecutive), symbols, no letter repeats in sequence or mirrored.
    private val valid = "Saf3-Wolf!"

    @Test
    fun validPassphrase_passes() {
        assertNull(PassphrasePolicy.firstIssue(valid))
        assertTrue(PassphrasePolicy.isValid(valid))
    }

    @Test
    fun tooShort_rejected() {
        assertEquals(PassphrasePolicy.Issue.TooShort, PassphrasePolicy.firstIssue("Ab1!cde"))
    }

    @Test
    fun missingUppercase_rejected() {
        assertEquals(PassphrasePolicy.Issue.MissingUppercase, PassphrasePolicy.firstIssue("saf3-wolf!"))
    }

    @Test
    fun missingLowercase_rejected() {
        assertEquals(PassphrasePolicy.Issue.MissingLowercase, PassphrasePolicy.firstIssue("SAF3-WOLF!"))
    }

    @Test
    fun missingDigit_rejected() {
        assertEquals(PassphrasePolicy.Issue.MissingDigit, PassphrasePolicy.firstIssue("Safe-Wolf!"))
    }

    @Test
    fun missingSymbol_rejected() {
        assertEquals(PassphrasePolicy.Issue.MissingSymbol, PassphrasePolicy.firstIssue("Safe3Wolf"))
        assertEquals(PassphrasePolicy.Issue.MissingSymbol, PassphrasePolicy.firstIssue("Safe3 Wolf"), "Whitespace is not a symbol")
    }

    @Test
    fun repeatedDigit_anywhere_rejected() {
        assertEquals(PassphrasePolicy.Issue.RepeatedDigit, PassphrasePolicy.firstIssue("Saf3-Wol3!"))
        assertEquals(PassphrasePolicy.Issue.RepeatedDigit, PassphrasePolicy.firstIssue("S33f-Wolf!"))
        assertEquals(PassphrasePolicy.Issue.RepeatedDigit, PassphrasePolicy.firstIssue("Safe-Wo11f!"))
    }

    @Test
    fun consecutiveDigits_rejected() {
        // Adjacent pairs in both directions.
        assertEquals(PassphrasePolicy.Issue.ConsecutiveDigits, PassphrasePolicy.firstIssue("Saf12-Wolf!"))
        assertEquals(PassphrasePolicy.Issue.ConsecutiveDigits, PassphrasePolicy.firstIssue("Saf21-Wolf!"))
        assertEquals(PassphrasePolicy.Issue.ConsecutiveDigits, PassphrasePolicy.firstIssue("Saf98-Wolf!"))
        // Wrap-around 0<->9 is consecutive too.
        assertEquals(PassphrasePolicy.Issue.ConsecutiveDigits, PassphrasePolicy.firstIssue("Saf09-Wolf!"))
        assertEquals(PassphrasePolicy.Issue.ConsecutiveDigits, PassphrasePolicy.firstIssue("Saf90-Wolf!"))
        // Non-adjacent digits are fine even when numerically consecutive.
        assertNull(PassphrasePolicy.firstIssue("Saf1-Wol3f!"))
    }

    @Test
    fun adjacentDuplicateLetter_rejected() {
        assertEquals(PassphrasePolicy.Issue.RepeatedLetter, PassphrasePolicy.firstIssue("Saa3-Wolf!"))
        assertEquals(PassphrasePolicy.Issue.RepeatedLetter, PassphrasePolicy.firstIssue("Saf3-Woolf!"))
        // Case-insensitive: "Aa" is a repeat in sequence.
        assertEquals(PassphrasePolicy.Issue.RepeatedLetter, PassphrasePolicy.firstIssue("Aa3-Wolf!"))
    }

    @Test
    fun mirroredLetters_rejected() {
        // "abXba": a...a with the span mirrored, no adjacent duplicates.
        assertEquals(PassphrasePolicy.Issue.MirroredLetters, PassphrasePolicy.firstIssue("Saf3-abXba!"))
        // "aXa": three-letter mirror with the digit outside the span.
        assertEquals(PassphrasePolicy.Issue.MirroredLetters, PassphrasePolicy.firstIssue("aXa3-Wolf!"))
        // Case-insensitive mirror.
        assertEquals(PassphrasePolicy.Issue.MirroredLetters, PassphrasePolicy.firstIssue("AXa3-Wolf!"))
        // Digits inside a palindrome span are fine; the mirror is still flagged.
        assertEquals(PassphrasePolicy.Issue.MirroredLetters, PassphrasePolicy.firstIssue("Sab3ba-Wolf!"))
    }

    @Test
    fun nonMirroredLetterRepeat_isAllowed() {
        // Same letter twice, no mirror pattern (span not a palindrome), not
        // adjacent.
        assertNull(PassphrasePolicy.firstIssue("Saf3-Wolf!s"))
        assertNull(PassphrasePolicy.firstIssue("Abca3-Wolf!"))
    }

    @Test
    fun allIssues_reportsEveryViolationInOrder() {
        val issues = PassphrasePolicy.allIssues("abc")
        // Too short, no uppercase, no digit, no symbol.
        assertTrue(PassphrasePolicy.Issue.TooShort in issues)
        assertTrue(PassphrasePolicy.Issue.MissingUppercase in issues)
        assertTrue(PassphrasePolicy.Issue.MissingDigit in issues)
        assertTrue(PassphrasePolicy.Issue.MissingSymbol in issues)
        assertEquals(4, issues.size)
    }

    @Test
    fun messages_existForEveryIssue() {
        PassphrasePolicy.Issue.entries.forEach { issue ->
            assertNotNull(PassphrasePolicy.messageFor(issue))
            assertTrue(PassphrasePolicy.messageFor(issue).isNotBlank())
        }
    }

    @Test
    fun requirements_mentionPasswordManager() {
        assertTrue(PassphrasePolicy.requirements.any { it.contains("password manager", ignoreCase = true) })
        assertTrue(PassphrasePolicy.PASSWORD_MANAGER_TIP.contains("password manager", ignoreCase = true))
    }

    @Test
    fun emptyAndTrivialPassphrases_rejected() {
        assertNotNull(PassphrasePolicy.firstIssue(""))
        assertFalse(PassphrasePolicy.isValid("password1"))
        assertFalse(PassphrasePolicy.isValid("12345678Aa!"), "Repeated/consecutive digits and missing letters")
    }
}

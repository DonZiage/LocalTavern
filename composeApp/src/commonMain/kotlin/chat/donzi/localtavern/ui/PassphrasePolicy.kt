package chat.donzi.localtavern.ui

// Strength policy for desktop passphrases. Enforced at every point a new
// passphrase is created (first-run setup, protect-keys dialog, change
// passphrase). The unlock path only verifies, never validates.
//
// Rules:
// - At least MIN_LENGTH characters.
// - At least one uppercase letter, one lowercase letter, one digit and one
//   symbol (anything that is not a letter, digit or whitespace).
// - A digit must never appear more than once anywhere in the passphrase.
// - Digits must not be consecutive: no adjacent pair that differs by one in
//   either direction, including the 0<->9 wrap-around (12, 21, 98, 09, 90).
// - Letters may repeat, but never directly in sequence (aa, Ll) and never
//   mirrored (aba, abba, aXa) — case-insensitively, so "aA" is a repeat too.
//
// The intended workflow is a password manager generating the passphrase:
// every caller surfaces [PASSWORD_MANAGER_TIP] next to the requirements.
object PassphrasePolicy {

    const val MIN_LENGTH = 8

    const val PASSWORD_MANAGER_TIP =
        "Tip: let a password manager generate and store this passphrase — it will never be stored by LocalTavern itself."

    enum class Issue {
        TooShort,
        MissingUppercase,
        MissingLowercase,
        MissingDigit,
        MissingSymbol,
        RepeatedDigit,
        ConsecutiveDigits,
        RepeatedLetter,
        MirroredLetters
    }

    fun isValid(passphrase: String): Boolean = firstIssue(passphrase) == null

    /** The first violated rule in check order, or null when the passphrase is valid. */
    fun firstIssue(passphrase: String): Issue? = allIssues(passphrase).firstOrNull()

    /** Every violated rule, in check order. */
    fun allIssues(passphrase: String): List<Issue> {
        val issues = ArrayList<Issue>()
        if (passphrase.length < MIN_LENGTH) issues.add(Issue.TooShort)
        if (passphrase.none { it.isUpperCase() }) issues.add(Issue.MissingUppercase)
        if (passphrase.none { it.isLowerCase() }) issues.add(Issue.MissingLowercase)
        if (passphrase.none { it.isDigit() }) issues.add(Issue.MissingDigit)
        if (passphrase.none { it.isSymbol() }) issues.add(Issue.MissingSymbol)

        // Digit rules: every digit at most once, never adjacent-consecutive.
        val digits = passphrase.filter { it.isDigit() }
        if (digits.toSet().size != digits.length) issues.add(Issue.RepeatedDigit)
        for (index in 1 until digits.length) {
            val a = digits[index - 1].digitToInt()
            val b = digits[index].digitToInt()
            val consecutive = kotlin.math.abs(a - b) == 1 ||
                (a == 0 && b == 9) || (a == 9 && b == 0)
            if (consecutive) {
                issues.add(Issue.ConsecutiveDigits)
                break
            }
        }

        // Letter rules: no adjacent duplicate, no mirrored (palindrome) span.
        val letters = passphrase.toList()
        for (index in 1 until letters.size) {
            if (letters[index].letterKey() == letters[index - 1].letterKey()) {
                issues.add(Issue.RepeatedLetter)
                break
            }
        }
        for (i in 0 until letters.size) {
            var mirrored = false
            for (j in i + 2 until letters.size) {
                if (letters[i].letterKey() != letters[j].letterKey()) continue
                if (isPalindrome(letters, i, j)) {
                    mirrored = true
                    break
                }
            }
            if (mirrored) {
                issues.add(Issue.MirroredLetters)
                break
            }
        }
        return issues
    }

    fun messageFor(issue: Issue): String = when (issue) {
        Issue.TooShort -> "Passphrase must be at least $MIN_LENGTH characters."
        Issue.MissingUppercase -> "Passphrase must contain at least one uppercase letter."
        Issue.MissingLowercase -> "Passphrase must contain at least one lowercase letter."
        Issue.MissingDigit -> "Passphrase must contain at least one number."
        Issue.MissingSymbol -> "Passphrase must contain at least one symbol (e.g. ! ? # %)."
        Issue.RepeatedDigit -> "A number must not be used more than once in the passphrase."
        Issue.ConsecutiveDigits -> "Numbers must not be consecutive (e.g. 12, 98, 09)."
        Issue.RepeatedLetter -> "A letter must not repeat in sequence (e.g. aa)."
        Issue.MirroredLetters -> "A letter must not mirror around others (e.g. aba, abba)."
    }

    /** Human-readable summary shown next to the passphrase fields. */
    val requirements: List<String> = listOf(
        "At least $MIN_LENGTH characters",
        "Uppercase, lowercase, numbers and symbols",
        "Each number used at most once, never consecutive",
        "Letters must not repeat in sequence or mirror (aba, aa)",
        "Use a password manager to generate and store it"
    )

    // Case-insensitive identity for letters; everything else (digits, symbols,
    // whitespace) is its own identity and never compared as a letter.
    private fun Char.letterKey(): Char =
        if (isLetter()) lowercaseChar() else this

    private fun Char.isSymbol(): Boolean =
        !isLetterOrDigit() && !isWhitespace()

    private fun isPalindrome(chars: List<Char>, start: Int, end: Int): Boolean {
        var lo = start
        var hi = end
        while (lo < hi) {
            if (chars[lo].letterKey() != chars[hi].letterKey()) return false
            lo++
            hi--
        }
        return true
    }
}

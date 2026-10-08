package chat.donzi.localtavern.utils

/**
 * Centralized input validation for user-supplied text. Every guard lives here
 * (instead of scattered `isNotBlank()` checks at call sites) so the rules are
 * consistent and unit-testable. Validators return an error message or null
 * when the value is acceptable; [cleanDisplayName] applies the accepted
 * normalization.
 */
object InputValidation {

    /** Display names (characters, personas): must be non-blank, capped. */
    const val MAX_NAME_CHARS = 100

    /**
     * Chat message text: capped so one paste cannot pin megabytes in the
     * in-memory response builder and token recount. Image-only sends carry
     * blank text, so blank is valid here (the UI still requires text or
     * images before enabling send).
     */
    const val MAX_MESSAGE_CHARS = 20_000

    /** Null when [name] is an acceptable display name, else the user-facing error. */
    fun validateDisplayName(name: String): String? = when {
        name.isBlank() -> "Name must not be empty."
        name.trim().length > MAX_NAME_CHARS -> "Name must be at most $MAX_NAME_CHARS characters."
        else -> null
    }

    /** Normalizes an already-validated display name for storage. */
    fun cleanDisplayName(name: String): String =
        name.trim().replace(Regex("""\s+"""), " ").take(MAX_NAME_CHARS)

    /** Null when [text] fits the send budget, else the user-facing error. */
    fun validateChatMessage(text: String): String? = when {
        text.length > MAX_MESSAGE_CHARS -> "Message is too long (max $MAX_MESSAGE_CHARS characters)."
        else -> null
    }
}

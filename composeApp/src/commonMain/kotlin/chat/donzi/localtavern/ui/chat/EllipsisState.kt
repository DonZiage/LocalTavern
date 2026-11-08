package chat.donzi.localtavern.ui.chat

/**
 * Waiting indicator visibility: show the ellipsis while the model has
 * not produced any visible character yet.
 *
 * The placeholder "..." is only one of the pre-first-token states.
 * Streaming reasoning (thinking) chunks and blank deltas also leave the
 * visible content empty ("") before the first character lands, in both
 * streamed and non-streamed (whole-response-at-once) modes. Stopping on
 * anything other than the first visible character made the dots vanish
 * early and the bubble flicker blank.
 */
fun shouldShowEllipsis(isUserMessage: Boolean, isGenerating: Boolean, content: String): Boolean =
    !isUserMessage && isGenerating && (content == "..." || content.isBlank())

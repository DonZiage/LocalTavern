package chat.donzi.localtavern.ui.common

/**
 * Auto-capitalisation policy for free-text fields (chat input, message
 * edit, name fields):
 *
 * - While a field is untouched (still empty), sentence capitalisation
 *   stays ON so the first typed letter is upper-case by default.
 * - Once the user deletes the leading character(s) back to an empty
 *   field, capitalisation must NOT be forced back on: the keyboard keeps
 *   whatever shift state it currently has (typically lower-case after
 *   typing the initial capital). Forcing it would pop the shift key
 *   back on every time the field is cleared.
 *
 * The caller resets [currentlySuppressing] to false when the field is
 * cleared programmatically (send accepted, dialog closed and reopened),
 * so the next fresh entry caps the first letter again.
 *
 * Pure so the policy is unit-testable without Compose.
 */
fun nextSuppressAutoCap(
    previousText: String,
    nextText: String,
    currentlySuppressing: Boolean
): Boolean {
    if (previousText.isNotEmpty() && nextText.isEmpty()) return true
    return currentlySuppressing
}

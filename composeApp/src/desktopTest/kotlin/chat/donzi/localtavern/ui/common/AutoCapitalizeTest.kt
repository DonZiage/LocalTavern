package chat.donzi.localtavern.ui.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoCapitalizeTest {

    @Test
    fun emptyFieldKeepsCapitalisationEnabled() {
        // Fresh, untouched field: caps stay on for the first letter.
        assertFalse(nextSuppressAutoCap("", "", false))
    }

    @Test
    fun typingFirstLetterDoesNotSuppress() {
        assertFalse(nextSuppressAutoCap("", "H", false))
    }

    @Test
    fun deletingLeadingCapitalSuppressesRecap() {
        // User deletes the initial capital back to empty: do NOT force
        // caps back on; keep the keyboard's current state.
        assertTrue(nextSuppressAutoCap("H", "", false))
    }

    @Test
    fun deletingLaterTextIntoEmptyAlsoSuppresses() {
        assertTrue(nextSuppressAutoCap("Hello", "", false))
    }

    @Test
    fun suppressionPersistsWhileTypingAgain() {
        assertTrue(nextSuppressAutoCap("", "h", true))
        assertTrue(nextSuppressAutoCap("h", "hi", true))
    }

    @Test
    fun midTextEditsDoNotSuppress() {
        assertFalse(nextSuppressAutoCap("Hi", "Hi!", false))
        assertFalse(nextSuppressAutoCap("Hi!", "Hi", false))
    }
}

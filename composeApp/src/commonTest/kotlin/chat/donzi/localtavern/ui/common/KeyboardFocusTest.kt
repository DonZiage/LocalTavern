package chat.donzi.localtavern.ui.common

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardFocusTest {

    @AfterTest
    fun tearDown() {
        TextFieldFocusTracker.clear()
    }

    @Test
    fun hideOnlyWhenNoTextFieldFocused() {
        // Focus on any element that is not an input -> hide.
        assertTrue(shouldHideKeyboardOnFocusLoss(isAnyTextFieldFocused = false))
        // Focus on an input field -> keep the keyboard up.
        assertFalse(shouldHideKeyboardOnFocusLoss(isAnyTextFieldFocused = true))
    }

    @Test
    fun tracker_tracksIndependentFields() {
        TextFieldFocusTracker.clear()
        assertFalse(TextFieldFocusTracker.isAnyTextFieldFocused)

        TextFieldFocusTracker.onFieldFocusChanged("chat-input", true)
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        // A second field (e.g. a side-panel dialog field) gains focus while
        // the first still holds it: losing the first must NOT report empty.
        TextFieldFocusTracker.onFieldFocusChanged("side-panel-field", true)
        TextFieldFocusTracker.onFieldFocusChanged("chat-input", false)
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        TextFieldFocusTracker.onFieldFocusChanged("side-panel-field", false)
        assertFalse(TextFieldFocusTracker.isAnyTextFieldFocused)
    }

    @Test
    fun tracker_removeAndClear() {
        TextFieldFocusTracker.onFieldFocusChanged("a", true)
        TextFieldFocusTracker.removeField("a")
        assertFalse(TextFieldFocusTracker.isAnyTextFieldFocused)

        TextFieldFocusTracker.onFieldFocusChanged("a", true)
        TextFieldFocusTracker.onFieldFocusChanged("b", true)
        TextFieldFocusTracker.clear()
        assertFalse(TextFieldFocusTracker.isAnyTextFieldFocused)
    }
}

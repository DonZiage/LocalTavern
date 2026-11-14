package chat.donzi.localtavern.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
    fun tapOnANonInputElement_dismissesTheKeyboard() {
        // No input holds the focus: there is no keyboard to hide.
        assertFalse(
            shouldHideKeyboardOnTap(isAnyTextFieldFocused = false, isTapInsideATextField = false)
        )
        // The tap landed on an input: the keyboard stays up.
        assertFalse(
            shouldHideKeyboardOnTap(isAnyTextFieldFocused = true, isTapInsideATextField = true)
        )
        // The tap landed on anything else while an input is focused: the
        // keyboard must go. On touch devices the tap does not move the
        // focus, so this is the only signal the app gets.
        assertTrue(
            shouldHideKeyboardOnTap(isAnyTextFieldFocused = true, isTapInsideATextField = false)
        )
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

    @Test
    fun tracker_knowsWhichPointsLandOnATextField() {
        TextFieldFocusTracker.clear()
        TextFieldFocusTracker.updateFieldBounds("a", Rect(Offset(10f, 20f), Size(100f, 40f)))

        // Inside the field: an input tap, the keyboard stays up.
        assertTrue(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(50f, 30f)))
        // On its edge counts as inside.
        assertTrue(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(10f, 20f)))
        // Outside the field: a tap on any other element.
        assertFalse(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(5f, 5f)))
        assertFalse(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(200f, 30f)))

        // A second field adds its own area.
        TextFieldFocusTracker.updateFieldBounds("b", Rect(Offset(300f, 0f), Size(50f, 50f)))
        assertTrue(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(310f, 10f)))
        assertFalse(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(200f, 30f)))

        // A field that leaves the composition must not keep its area.
        TextFieldFocusTracker.removeField("b")
        assertFalse(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(310f, 10f)))
        assertTrue(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(50f, 30f)))

        TextFieldFocusTracker.clear()
        assertFalse(TextFieldFocusTracker.isPointInsideAnyTextField(Offset(50f, 30f)))
    }
}

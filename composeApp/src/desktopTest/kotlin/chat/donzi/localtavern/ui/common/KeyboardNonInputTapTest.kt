package chat.donzi.localtavern.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Touch mode: on touch devices a tap never moves the Compose focus (clickable
// elements are only focusable for mouse/keyboard input, see
// `Focusability.SystemDefined`). That is exactly the situation in which the
// soft keyboard used to stay up after a tap on a non-input element.
private object TouchInputModeManager : InputModeManager {
    override val inputMode: InputMode = InputMode.Touch
    override fun requestInputMode(inputMode: InputMode): Boolean = false
}

// Screen-level proof that the soft keyboard follows the focus: a tap on an
// element that is not an input must dismiss it, a tap on an input must not.
@OptIn(ExperimentalTestApi::class)
class KeyboardNonInputTapTest {

    @AfterTest
    fun tearDown() {
        TextFieldFocusTracker.clear()
    }

    @Test
    fun tappingANonInputElement_dismissesTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .dismissKeyboardOnNonInputTap()
                ) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    // A plain label: no click action, nothing focusable. Such
                    // an element never takes the focus, so the keyboard would
                    // stay up unless the tap itself dismisses it.
                    Text(
                        text = "Side panel item",
                        modifier = Modifier.padding(top = 200.dp)
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "the input must hold the focus before the tap"
        )

        onNodeWithText("Side panel item").performTouchInput { click() }
        waitForIdle()

        // The keyboard belongs to the input that was left behind: it must go.
        assertFalse(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap outside an input must dismiss the keyboard"
        )
    }

    @Test
    fun tappingAButton_dismissesTheKeyboardAndStillClicks() = runComposeUiTest {
        val focusRequester = FocusRequester()
        var buttonTapped = false
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .dismissKeyboardOnNonInputTap()
                ) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    Text(
                        text = "Side panel item",
                        modifier = Modifier
                            .padding(top = 200.dp)
                            .clickable { buttonTapped = true }
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        onNodeWithText("Side panel item").performTouchInput { click() }
        waitForIdle()

        // The tap must still be delivered to the element it landed on.
        assertTrue(buttonTapped, "the tap must still reach the clicked element")
        assertFalse(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap on a button must dismiss the keyboard"
        )
    }

    @Test
    fun tappingTheInputField_keepsTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .dismissKeyboardOnNonInputTap()
                ) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    Text(
                        text = "Side panel item",
                        modifier = Modifier.padding(top = 200.dp)
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        // Placing the cursor inside the focused field must keep the keyboard.
        onNodeWithText("Draft").performTouchInput { click() }
        waitForIdle()

        assertTrue(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap inside the focused input must keep the keyboard"
        )
    }

    @Test
    fun tappingAnotherInput_keepsTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .dismissKeyboardOnNonInputTap()
                ) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    BasicTextField(
                        value = "Search",
                        onValueChange = { },
                        modifier = Modifier
                            .padding(top = 200.dp)
                            .trackTextFieldFocus()
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        // Moving the focus from one input to another is not a non-input tap.
        onNodeWithText("Search").performTouchInput { click() }
        waitForIdle()

        assertTrue(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "an input-to-input transfer must keep the keyboard"
        )
    }
}

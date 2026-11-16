package chat.donzi.localtavern.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.zIndex
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

    // Regression for the side panels: the real panels are not a flat Box.
    // They are a scrolling overlay (zIndex above the chat, vertical scroll,
    // clickable rows) — and the panel-level watcher must dismiss the
    // keyboard on its own, even with no root watcher above it, because
    // that is the case the flat tests above never covered.
    @Test
    fun tappingAnItemInsideAScrollingOverlayPanel_dismissesTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        var rowTapped = false
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    // The panel: overlay above the chat, scrolling, with
                    // its own dismiss watcher and no root watcher above it.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .dismissKeyboardOnNonInputTap()
                            .zIndex(100f)
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = "Panel character row",
                                modifier = Modifier
                                    .padding(top = 200.dp)
                                    .clickable { rowTapped = true }
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        onNodeWithText("Panel character row").performTouchInput { click() }
        waitForIdle()

        assertTrue(rowTapped, "the tap must still reach the panel row")
        assertFalse(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap on a side-panel row must dismiss the keyboard"
        )
    }

    @Test
    fun tappingATextFieldInsideThePanel_keepsTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .dismissKeyboardOnNonInputTap()
                            .zIndex(100f)
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            BasicTextField(
                                value = "Panel search",
                                onValueChange = { },
                                modifier = Modifier
                                    .padding(top = 200.dp)
                                    .trackTextFieldFocus()
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        onNodeWithText("Panel search").performTouchInput { click() }
        waitForIdle()

        assertTrue(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap inside a panel text field must keep the keyboard"
        )
    }

    @Test
    fun openingADrawer_dismissesTheKeyboard() = runComposeUiTest {
        val focusRequester = FocusRequester()
        var drawerOpen by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                HideKeyboardOnDrawerOpen(isDrawerOpen = drawerOpen)
                BasicTextField(
                    value = "Draft",
                    onValueChange = { },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .trackTextFieldFocus()
                )
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        // Opening the drawer is a state change, not a tap: the keyboard
        // must go even though nothing was tapped.
        runOnIdle { drawerOpen = true }
        waitForIdle()

        assertFalse(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "opening a drawer must dismiss the keyboard"
        )
    }

    // Regression for 45c621d on Android: the mobile side panels live in
    // an always-composed, full-screen Box above the chat (zIndex 100),
    // even with no drawer open. Putting the dismiss watcher on that
    // container made it the hit target for every tap, so the chat
    // underneath — top bar, input, send button, everything — stopped
    // receiving taps at all, while the screen still looked fine. The
    // watcher therefore belongs on the drawers themselves, never on the
    // full-screen container. This mirrors SidePanels.kt with the drawer
    // closed: outer Box with no watcher, chat button underneath.
    @Test
    fun tappingChatContentUnderAClosedOverlay_reachesTheChat() = runComposeUiTest {
        var buttonTapped = false
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Chat send button",
                        modifier = Modifier
                            .padding(top = 200.dp)
                            .clickable { buttonTapped = true }
                    )
                    // The SidePanels container: always composed, full
                    // screen, above the chat — and with NO dismiss
                    // watcher on it. No drawer is open, so it holds
                    // nothing visible.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(100f)
                    ) {
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("Chat send button").performTouchInput { click() }
        waitForIdle()

        assertTrue(
            buttonTapped,
            "a tap on the chat must reach the chat even with the closed overlay above it"
        )
    }

    // The other half of the same fix: with the drawer open, the watcher
    // scoped to the drawer itself (drawerWidth wide) must still dismiss
    // the keyboard on a panel row tap, and the tap must still reach the
    // row. This mirrors SidePanels.kt with a drawer open.
    @Test
    fun tappingAPanelRowWithDrawerOpen_dismissesTheKeyboardAndStillClicks() = runComposeUiTest {
        val focusRequester = FocusRequester()
        var rowTapped = false
        setContent {
            CompositionLocalProvider(LocalInputModeManager provides TouchInputModeManager) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = "Draft",
                        onValueChange = { },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .trackTextFieldFocus()
                    )
                    // The SidePanels container: no watcher on it.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(100f)
                    ) {
                        // The open drawer: watcher scoped to the drawer.
                        Box(
                            modifier = Modifier
                                .width(300.dp)
                                .fillMaxHeight()
                                .dismissKeyboardOnNonInputTap()
                        ) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = "Panel character row",
                                    modifier = Modifier
                                        .padding(top = 200.dp)
                                        .clickable { rowTapped = true }
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(TextFieldFocusTracker.isAnyTextFieldFocused)

        onNodeWithText("Panel character row").performTouchInput { click() }
        waitForIdle()

        assertTrue(rowTapped, "the tap must still reach the panel row")
        assertFalse(
            TextFieldFocusTracker.isAnyTextFieldFocused,
            "a tap on a side-panel row must dismiss the keyboard"
        )
    }
}

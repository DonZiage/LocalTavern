package chat.donzi.localtavern.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlin.random.Random

// Tracks which text fields currently hold focus so the soft keyboard can be
// hidden whenever focus moves to any element that is not a text field
// (message rows, buttons, cards, side-panel items, top bars, ...), while a
// transfer between two text fields keeps the keyboard up.
//
// Pure set logic lives here so it stays unit-testable; the Compose wiring is
// only [Modifier.trackTextFieldFocus] and [AutoHideKeyboardOnNonInputFocus].
object TextFieldFocusTracker {
    private val focusedFieldIds = mutableStateSetOf<String>()

    val isAnyTextFieldFocused: Boolean
        get() = focusedFieldIds.isNotEmpty()

    fun onFieldFocusChanged(id: String, isFocused: Boolean) {
        if (isFocused) focusedFieldIds.add(id) else focusedFieldIds.remove(id)
    }

    fun removeField(id: String) {
        focusedFieldIds.remove(id)
    }

    fun clear() {
        focusedFieldIds.clear()
    }
}

// The keyboard must hide when focus leaves every text field (i.e. it landed
// on a non-input element). The debounce in
// [AutoHideKeyboardOnNonInputFocus] lets a TextField -> TextField transfer
// (which momentarily reports "nothing focused") settle without hiding.
fun shouldHideKeyboardOnFocusLoss(isAnyTextFieldFocused: Boolean): Boolean =
    !isAnyTextFieldFocused

/**
 * Registers an editable text field with [TextFieldFocusTracker]. Apply to the
 * modifier of every `TextField` / `OutlinedTextField` / `BasicTextField` in
 * the app (including side-panel and dialog fields): focusing it keeps the
 * keyboard target state "input", while any other focusable element gaining
 * focus reports "non-input" and hides the keyboard.
 *
 * A stable unique id is remembered per composition instance so reused
 * components (e.g. [chat.donzi.localtavern.ui.settings.ParameterSlider])
 * never collide.
 */
@Composable
fun Modifier.trackTextFieldFocus(): Modifier {
    val id = remember { Random.nextLong().toString(16) + "-" + Random.nextInt().toString(16) }
    DisposableEffect(id) {
        onDispose { TextFieldFocusTracker.removeField(id) }
    }
    return this.onFocusChanged { focusState ->
        TextFieldFocusTracker.onFieldFocusChanged(id, focusState.isFocused)
    }
}

/**
 * Global observer: hides the soft keyboard as soon as focus is on no text
 * field anymore. Must be provided once at the app root so it also covers
 * side panels and dialogs.
 *
 * The delay lets a TextField -> TextField focus transfer complete first;
 * a transfer between two inputs must NOT hide the keyboard, while a transfer
 * to a button/card/row leaves nothing focused after the delay and hides it.
 */
@Composable
fun AutoHideKeyboardOnNonInputFocus() {
    val keyboardController = LocalSoftwareKeyboardController.current
    // Snapshot read: relaunches when the tracker set changes.
    val isAnyFocused = TextFieldFocusTracker.isAnyTextFieldFocused
    LaunchedEffect(isAnyFocused) {
        if (shouldHideKeyboardOnFocusLoss(isAnyFocused)) {
            delay(100)
            if (!TextFieldFocusTracker.isAnyTextFieldFocused) {
                keyboardController?.hide()
            }
        }
    }
}

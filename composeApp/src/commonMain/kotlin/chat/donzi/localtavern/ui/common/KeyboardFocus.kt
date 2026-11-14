package chat.donzi.localtavern.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlin.random.Random

// Tracks which text fields currently hold focus so the soft keyboard can be
// hidden whenever focus moves to any element that is not a text field
// (message rows, buttons, cards, side-panel items, top bars, ...), while a
// transfer between two text fields keeps the keyboard up.
//
// Pure set logic lives here so it stays unit-testable; the Compose wiring is
// only [Modifier.trackTextFieldFocus], [AutoHideKeyboardOnNonInputFocus] and
// [Modifier.dismissKeyboardOnNonInputTap].
object TextFieldFocusTracker {
    private val focusedFieldIds = mutableStateSetOf<String>()

    // Root-relative bounds of every registered text field. They tell a tap on
    // an input apart from a tap on anything else (a button, a message row, a
    // side-panel item, ...), which is what the keyboard dismissal needs: on
    // touch devices such a tap never moves the Compose focus, so the focus
    // tracker alone cannot see it.
    private val fieldBounds = mutableMapOf<String, Rect>()

    val isAnyTextFieldFocused: Boolean
        get() = focusedFieldIds.isNotEmpty()

    fun onFieldFocusChanged(id: String, isFocused: Boolean) {
        if (isFocused) focusedFieldIds.add(id) else focusedFieldIds.remove(id)
    }

    fun removeField(id: String) {
        focusedFieldIds.remove(id)
        fieldBounds.remove(id)
    }

    fun clear() {
        focusedFieldIds.clear()
        fieldBounds.clear()
    }

    // The bounds of a text field change whenever it is laid out or scrolled;
    // they are read only on a pointer event, so a plain map is enough.
    fun updateFieldBounds(id: String, bounds: Rect) {
        fieldBounds[id] = bounds
    }

    // True when the given root-relative point falls inside a registered text
    // field: the tap landed on an input and must keep the keyboard up.
    fun isPointInsideAnyTextField(point: Offset): Boolean =
        fieldBounds.values.any { it.contains(point) }
}

// The keyboard must hide when focus leaves every text field (i.e. it landed
// on a non-input element). The debounce in
// [AutoHideKeyboardOnNonInputFocus] lets a TextField -> TextField transfer
// (which momentarily reports "nothing focused") settle without hiding.
fun shouldHideKeyboardOnFocusLoss(isAnyTextFieldFocused: Boolean): Boolean =
    !isAnyTextFieldFocused

/**
 * What a tap means for the soft keyboard.
 *
 * The keyboard belongs to the input that is in use, so it stays up only when
 * the tap landed on a text field. A tap on anything else (a button, a
 * message row, a side-panel item, ...) dismisses it - on touch devices such
 * a tap does not even move the focus, so the tap itself is the only signal.
 */
fun shouldHideKeyboardOnTap(
    isAnyTextFieldFocused: Boolean,
    isTapInsideATextField: Boolean
): Boolean = isAnyTextFieldFocused && !isTapInsideATextField

/**
 * Registers an editable text field with [TextFieldFocusTracker]. Apply to the
 * modifier of every `TextField` / `OutlinedTextField` / `BasicTextField` in
 * the app (including side-panel and dialog fields): focusing it keeps the
 * keyboard target state "input", while any other focusable element gaining
 * focus reports "non-input" and hides the keyboard.
 *
 * The field's bounds are registered as well: they let the root tap observer
 * ([Modifier.dismissKeyboardOnNonInputTap]) tell a tap on an input from a
 * tap on any other element.
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
    return this
        .onFocusChanged { focusState ->
            TextFieldFocusTracker.onFieldFocusChanged(id, focusState.isFocused)
        }
        .onGloballyPositioned { coordinates ->
            // The bounds are only meaningful while the field is placed.
            if (coordinates.isAttached) {
                TextFieldFocusTracker.updateFieldBounds(id, coordinates.boundsInRoot())
            }
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

// Holder for the root offset of the observed node. A plain holder (not a
// snapshot state) keeps the layout callback free of recomposition triggers:
// the value is only read while dispatching a pointer event.
private class RootOffsetHolder {
    var value: Offset = Offset.Zero
}

/**
 * Dismisses the soft keyboard when the user taps an element that is not an
 * input field.
 *
 * On touch devices a tap does NOT move the Compose focus: clickable
 * elements are only focusable in mouse/keyboard mode (see
 * `Focusability.SystemDefined`), so a focused text field keeps both the
 * focus and the keyboard after the user taps a button, a message row or a
 * side-panel item. The focus tracker cannot see such a tap, so the pointer
 * is watched at the root of the screen instead: a tap that lands outside
 * every registered text field is a non-input tap and must dismiss the
 * keyboard.
 *
 * Apply it to the root node of the screen (so it also covers the side
 * panels); the text fields themselves are recognized through
 * [Modifier.trackTextFieldFocus].
 */
@Composable
fun Modifier.dismissKeyboardOnNonInputTap(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentKeyboardController by rememberUpdatedState(keyboardController)
    val currentFocusManager by rememberUpdatedState(focusManager)
    // Pointer positions are relative to this node while the registered field
    // bounds are root-relative: keep this node's offset inside the root.
    val rootOffset = remember { RootOffsetHolder() }
    return this
        .onGloballyPositioned { coordinates ->
            rootOffset.value = coordinates.positionInRoot()
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // The final pass sees every pointer event of the screen,
                    // even the ones a child already consumed.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                    val isAnyFocused = TextFieldFocusTracker.isAnyTextFieldFocused
                    val point = down.position + rootOffset.value
                    val isInsideTextField = TextFieldFocusTracker.isPointInsideAnyTextField(point)
                    if (!shouldHideKeyboardOnTap(isAnyFocused, isInsideTextField)) continue
                    // The tap landed on anything else: drop the input focus
                    // and dismiss the keyboard.
                    currentFocusManager.clearFocus()
                    currentKeyboardController?.hide()
                }
            }
        }
}

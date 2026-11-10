package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.ui.common.nextSuppressAutoCap
import chat.donzi.localtavern.ui.common.trackTextFieldFocus
import kotlinx.coroutines.launch

@Composable
internal fun MessageEditTextField(
    value: TextFieldValue,
    textColor: Color,
    focusRequester: FocusRequester,
    bringIntoViewRequester: BringIntoViewRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    // Empty-field first-letter caps without re-forcing caps after the
    // user deletes the leading character (see AutoCapitalize).
    var suppressAutoCap by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = {
            suppressAutoCap = nextSuppressAutoCap(value.text, it.text, suppressAutoCap)
            onValueChange(it)
            coroutineScope.launch {
                bringIntoViewRequester.bringIntoView()
            }
        },
        modifier = Modifier
            .widthIn(min = 40.dp)
            .trackTextFieldFocus()
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    if (event.isShiftPressed) {
                        onValueChange(value.insertNewline())
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                        true
                    } else {
                        onSubmit()
                        true
                    }
                } else {
                    false
                }
            },
        textStyle = LocalTextStyle.current.copy(
            color = textColor,
            fontSize = 16.sp,
            lineHeight = 22.sp
        ),
        // Mobile Enter inserts a newline; hardware Enter is handled by
        // onPreviewKeyEvent above (send). The soft-keyboard action is
        // mapped to a newline too, so only the UI button saves on mobile.
        keyboardOptions = KeyboardOptions(
            capitalization = if (suppressAutoCap) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default
        ),
        keyboardActions = KeyboardActions(onSend = { onValueChange(value.insertNewline()) }),
        cursorBrush = SolidColor(textColor)
    )
}

internal fun TextFieldValue.insertNewline(): TextFieldValue {
    val currentText = this.text
    val selection = this.selection
    val newText = currentText.substring(0, selection.min) + "\n" + currentText.substring(selection.max)
    return TextFieldValue(
        text = newText,
        selection = TextRange(selection.min + 1)
    )
}

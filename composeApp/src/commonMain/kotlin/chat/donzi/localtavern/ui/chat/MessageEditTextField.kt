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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    BasicTextField(
        value = value,
        onValueChange = {
            onValueChange(it)
            coroutineScope.launch {
                bringIntoViewRequester.bringIntoView()
            }
        },
        modifier = Modifier
            .widthIn(min = 40.dp)
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
        // IME action so mobile soft keyboards can submit the edit; hardware
        // Enter is handled by onPreviewKeyEvent above.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
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

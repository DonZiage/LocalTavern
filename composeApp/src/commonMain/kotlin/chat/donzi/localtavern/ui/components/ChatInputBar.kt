package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBar(
    onSendMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean,
    onEnterSelectMode: () -> Unit,
    canDelete: Boolean,
    isGenerating: Boolean = false,
    onStopGeneration: () -> Unit = {},
    onManageChats: () -> Unit,
    canManageChats: Boolean,
    onGoToParent: (() -> Unit)? = null
) {
    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    var showMenu by remember { mutableStateOf(false) }

    fun handleSend() {
        if (textValue.text.isNotBlank() && !isGenerating) {
            onSendMessage(textValue.text)
            textValue = TextFieldValue("")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .onKeyEvent { event ->
                if (isGenerating && event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    !event.isShiftPressed) {
                    onStopGeneration()
                    true
                } else false
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Menu, contentDescription = "Chat Options")
            }
            ChatOptionsMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                onRegenerate = onRegenerate,
                canRegenerate = canRegenerate,
                onEnterSelectMode = onEnterSelectMode,
                canDelete = canDelete,
                onManageChats = onManageChats,
                canManageChats = canManageChats,
                onGoToParent = onGoToParent
            )
        }

        TextField(
            value = textValue,
            onValueChange = { textValue = it },
            placeholder = { Text("Message...") },
            enabled = !isGenerating,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (!isGenerating && event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        if (event.isShiftPressed) {
                            textValue = textValue.insertNewline()
                            true
                        } else {
                            handleSend()
                            true
                        }
                    } else {
                        false
                    }
                },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent
            )
        )

        if (isGenerating) {
            IconButton(onClick = onStopGeneration) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Response Generation",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(
                onClick = { handleSend() },
                enabled = textValue.text.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (textValue.text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}

private fun TextFieldValue.insertNewline(): TextFieldValue {
    val currentText = this.text
    val selection = this.selection
    val newText = currentText.substring(0, selection.min) + "\n" + currentText.substring(selection.max)
    return TextFieldValue(
        text = newText,
        selection = TextRange(selection.min + 1)
    )
}
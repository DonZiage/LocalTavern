package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBar(
    onSendMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean
) {
    var text by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    fun handleSend() {
        if (text.isNotBlank()) {
            onSendMessage(text)
            text = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
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
                canRegenerate = canRegenerate
            )
        }

        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message...") },
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && 
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        if (event.isShiftPressed) {
                            false // Allow newline
                        } else {
                            handleSend()
                            true // Consumed
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

        IconButton(
            onClick = { handleSend() },
            enabled = text.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }
    }
}

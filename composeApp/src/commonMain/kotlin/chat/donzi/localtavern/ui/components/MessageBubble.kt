package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    onEdit: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember { mutableStateOf(content) }

    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
        ) {
            if (content == "...") {
                AnimatedEllipsis()
            } else if (isEditing) {
                Column {
                    TextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier.fillMaxWidth(0.8f),
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = { isEditing = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = textColor)
                        }
                        IconButton(onClick = {
                            onEdit(editedText)
                            isEditing = false
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = textColor)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = content,
                        color = textColor,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { isEditing = true },
                        tint = textColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

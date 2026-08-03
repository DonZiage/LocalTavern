package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import coil3.compose.AsyncImage

private fun TextFieldValue.localTavernInsertNewline(): TextFieldValue {
    val selectionStart = selection.start
    val selectionEnd = selection.end
    val newText = text.substring(0, selectionStart) + "\n" + text.substring(selectionEnd)
    return TextFieldValue(
        text = newText,
        selection = TextRange(selectionStart + 1)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    textValue: TextFieldValue,
    onTextValueChange: (TextFieldValue) -> Unit,
    attachedImages: List<ByteArray>,
    onAttachedImagesChange: (List<ByteArray>) -> Unit,
    onSendMessage: (String, List<ByteArray>) -> Boolean,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean,
    onEnterSelectMode: () -> Unit,
    canDelete: Boolean,
    isGenerating: Boolean = false,
    onStopGeneration: () -> Unit = {},
    onManageChats: () -> Unit,
    canManageChats: Boolean,
    onGoToParent: (() -> Unit)? = null,
    sendWithCtrlEnter: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberImagePickerLauncher(
        onImagesPicked = { imagesList ->
            onAttachedImagesChange(attachedImages + imagesList)
        }
    )

    fun handleSend() {
        if ((textValue.text.isNotBlank() || attachedImages.isNotEmpty()) && !isGenerating) {
            // Clear the draft only when the send was actually accepted; a
            // refused send (e.g. no API connection) must keep the typed text.
            if (onSendMessage(textValue.text, attachedImages)) {
                onTextValueChange(TextFieldValue(""))
                onAttachedImagesChange(emptyList())
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (attachedImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                attachedImages.forEachIndexed { index, bytes ->
                    Box(
                        modifier = Modifier.size(72.dp)
                    ) {
                        AsyncImage(
                            model = bytes,
                            contentDescription = "Staged Attachment Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(18.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                                .clip(CircleShape)
                                .clickable { onAttachedImagesChange(attachedImages.filterIndexed { i, _ -> i != index }) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove Attachment",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }
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
                    canRegenerate = canRegenerate,
                    onEnterSelectMode = onEnterSelectMode,
                    canDelete = canDelete,
                    onManageChats = onManageChats,
                    canManageChats = canManageChats,
                    onGoToParent = onGoToParent,
                    onAttachImage = { imagePickerLauncher() }
                )
            }

            TextField(
                value = textValue,
                onValueChange = { onTextValueChange(it) },
                placeholder = { Text("Message...") },
                // Keep the field editable during generation so users can draft
                // the next message (and so mobile keyboards/focus are not
                // dropped mid-conversation); handleSend still guards sending.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { handleSend() }),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (!isGenerating && event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            val send = if (sendWithCtrlEnter) event.isCtrlPressed else !event.isShiftPressed
                            if (send) {
                                handleSend()
                            } else {
                                onTextValueChange(textValue.localTavernInsertNewline())
                            }
                            true
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
                val canSend = textValue.text.isNotBlank() || attachedImages.isNotEmpty()
                IconButton(
                    onClick = { handleSend() },
                    enabled = canSend
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        // Theme-aware disabled tint (plain gray is nearly
                        // invisible in dark mode).
                        tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}
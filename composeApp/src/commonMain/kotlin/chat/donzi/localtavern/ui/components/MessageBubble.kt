package chat.donzi.localtavern.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    onEdit: (String) -> Unit,
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onAddImage: () -> Unit = {},
    onBranch: () -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedTextValue by remember(content) {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }
    val focusRequester = remember { FocusRequester() }
    
    var showActionsOnMobile by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Determine if actions should be visible (Hover on desktop or Long Click on mobile)
    val actionsVisible = (isHovered || showActionsOnMobile) && !isEditing

    // Auto-focus when entering edit mode
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showActionsOnMobile = false }, // Close on tap away
                onLongClick = { showActionsOnMobile = !showActionsOnMobile }
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isUser) {
            MessageActions(
                visible = actionsVisible,
                onEdit = { 
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                    isEditing = true 
                },
                onMore = { showMenu = true }
            )
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .animateContentSize()
                .padding(12.dp)
                .widthIn(max = 400.dp)
        ) {
            if (content == "...") {
                AnimatedEllipsis(color = textColor)
            } else if (isEditing) {
                Column {
                    BasicTextField(
                        value = editedTextValue,
                        onValueChange = { editedTextValue = it },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    if (event.isShiftPressed) {
                                        false
                                    } else {
                                        onEdit(editedTextValue.text)
                                        isEditing = false
                                        true
                                    }
                                } else {
                                    false
                                }
                            },
                        textStyle = LocalTextStyle.current.copy(
                            color = textColor,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(textColor)
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isEditing = false
                                editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, "Cancel", tint = textColor, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                onEdit(editedTextValue.text)
                                isEditing = false
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Check, "Save", tint = textColor, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else {
                Text(
                    text = content,
                    color = textColor,
                    fontSize = 16.sp
                )
            }
            
            MessageActionMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                onCopy = onCopy,
                onDelete = onDelete,
                onAddImage = onAddImage,
                onBranch = onBranch
            )
        }

        if (!isUser) {
            MessageActions(
                visible = actionsVisible,
                onEdit = { 
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                    isEditing = true 
                },
                onMore = { showMenu = true }
            )
        }
    }
}

@Composable
fun MessageActions(
    visible: Boolean,
    onEdit: () -> Unit,
    onMore: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

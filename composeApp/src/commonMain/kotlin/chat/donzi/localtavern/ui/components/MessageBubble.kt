package chat.donzi.localtavern.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    onEdit: (String, List<ByteArray>) -> Unit,
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onAddImage: () -> Unit = {},
    onBranch: () -> Unit = {},
    avatarData: ByteArray? = null,
    messageImages: List<ByteArray> = emptyList(),
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    isSwipeable: Boolean = false,
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    isGenerating: Boolean = false,
    canEdit: Boolean = true,
    reasoningText: String? = null,
    costText: String? = null
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedTextValue by remember(content) {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }
    var editedImages by remember(messageImages, isEditing) { mutableStateOf(messageImages) }

    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    var showActionsOnMobile by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val actionsVisible = (isHovered || showActionsOnMobile || showMenu) && !isEditing && !isSelectMode

    // Swipe feedback: the bubble follows the finger; the trigger threshold is
    // dp-based so it feels the same on high- and low-DPI screens.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
            bringIntoViewRequester.bringIntoView()
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

    val rowBgColor = if (isSelectMode && isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }

    // Defined once and slotted into whichever side carries the actions (left
    // for the user, right for the assistant); it closes over the edit state
    // so entering/cancelling/saving an edit stays in one place.
    val actionsBlock = @Composable {
        if (isEditing) {
            EditActions(
                onCancel = {
                    isEditing = false
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                },
                onSave = {
                    onEdit(editedTextValue.text, editedImages)
                    isEditing = false
                }
            )
        } else {
            MessageActions(
                visible = actionsVisible,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                canEdit = canEdit,
                onEdit = {
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                    editedImages = messageImages
                    isEditing = true
                },
                onCopy = onCopy,
                onDelete = onDelete,
                onAddImage = onAddImage,
                onBranch = onBranch,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isSelectMode) {
                        onSelectToggle()
                    } else {
                        showActionsOnMobile = !showActionsOnMobile
                    }
                },
                onLongClick = {
                    if (!isSelectMode) {
                        showActionsOnMobile = !showActionsOnMobile
                    }
                }
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (isSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectToggle() },
                modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)
            )
        }

        if (isUser) {
            Box(modifier = Modifier.align(Alignment.Top)) {
                actionsBlock()
            }
        } else {
            BubbleAvatar(
                avatarData = avatarData,
                onFullscreen = { showFullImage = true },
                modifier = Modifier.align(Alignment.Top).padding(end = 6.dp)
            )
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .let { modifier -> if (!isUser) modifier.animateContentSize() else modifier }
                .graphicsLayer { translationX = dragOffset }
                .pointerInput(isSwipeable) {
                    if (isSwipeable) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffset > swipeThresholdPx) {
                                    onSwipeRight()
                                } else if (dragOffset < -swipeThresholdPx) {
                                    onSwipeLeft()
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                // Follow the finger and stop hijacking the
                                // list's vertical scrolling by consuming the
                                // change once a horizontal drag is detected.
                                change.consume()
                                dragOffset = (dragOffset + dragAmount)
                                    .coerceIn(-swipeThresholdPx * 1.5f, swipeThresholdPx * 1.5f)
                            }
                        )
                    }
                }
                .weight(1f, fill = false)
                .widthIn(max = 460.dp)
                .padding(12.dp)
        ) {
            MessageBubbleContent(
                content = content,
                isUser = isUser,
                isGenerating = isGenerating,
                isEditing = isEditing,
                textColor = textColor,
                editedTextValue = editedTextValue,
                onEditedTextValueChange = { editedTextValue = it },
                editedImages = editedImages,
                onRemoveEditedImage = { index -> editedImages = editedImages.filterIndexed { i, _ -> i != index } },
                focusRequester = focusRequester,
                bringIntoViewRequester = bringIntoViewRequester,
                onSubmitEdit = {
                    onEdit(editedTextValue.text, editedImages)
                    isEditing = false
                },
                messageImages = messageImages,
                reasoningText = reasoningText,
                costText = costText
            )
        }

        if (isUser) {
            BubbleAvatar(
                avatarData = avatarData,
                onFullscreen = { showFullImage = true },
                modifier = Modifier.align(Alignment.Top).padding(start = 6.dp)
            )
        } else {
            Box(modifier = Modifier.align(Alignment.Top)) {
                actionsBlock()
            }
        }
    }

    if (showFullImage && avatarData != null) {
        FullscreenImageViewer(avatarData = avatarData, onDismiss = { showFullImage = false })
    }
}

@Composable
private fun BubbleAvatar(
    avatarData: ByteArray?,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (avatarData != null) onFullscreen() },
        contentAlignment = Alignment.Center
    ) {
        if (avatarData != null) {
            AsyncImage(
                model = avatarData,
                contentDescription = "Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

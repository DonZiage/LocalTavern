package chat.donzi.localtavern.ui.chat
import chat.donzi.localtavern.ui.common.DefaultAvatarImage
import chat.donzi.localtavern.ui.common.FullscreenImageViewer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import chat.donzi.localtavern.isDesktop
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
    pendingImageCount: Int = 0,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    isSwipeable: Boolean = false,
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    isGenerating: Boolean = false,
    canEdit: Boolean = true,
    reasoningText: String? = null,
    costText: String? = null,
    timestampText: String? = null,
    // While another message is being edited in the input field, every
    // other bubble is dimmed so the edited one reads as highlighted.
    dimmed: Boolean = false,
    // Tap never opens options (on mobile it only cancels editing or
    // dismisses an already-open bar); holding (long-press) does. On
    // desktop a right-click opens the floating menu instead.
    onMessageTap: () -> Unit = {},
    onMessageLongPress: () -> Unit = {},
    onMessageRightClick: () -> Unit = {},
    // Desktop floating menu state, hoisted so the bottom three-dot button
    // (in ChatMessageItem) and right-click both open the same menu.
    desktopMenuExpanded: Boolean = false,
    onDesktopMenuChange: (Boolean) -> Unit = {},
    // Reports the bubble's pixel width so the caller can hug the
    // bottom three-dot to the bubble's right edge (not the screen edge).
    onBubbleWidthChanged: (Int) -> Unit = {},
    // In-conversation search highlight: matches get a tertiary border,
    // the current match a thicker primary border.
    isSearchMatch: Boolean = false,
    isCurrentMatch: Boolean = false
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

    // Desktop floating menu placement: to the side of the bubble (right
    // for assistant, left for user) when there is room, otherwise above
    // it — always hovering over the messages without pushing them.
    val desktopMenuPositionProvider = remember(isUser) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val gap = 8
                if (!isUser) {
                    val sideX = anchorBounds.right + gap
                    if (sideX + popupContentSize.width <= windowSize.width) {
                        val y = anchorBounds.top.coerceIn(
                            0,
                            (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                        )
                        return IntOffset(x = sideX, y = y)
                    }
                } else {
                    val sideX = anchorBounds.left - popupContentSize.width - gap
                    if (sideX >= 0) {
                        val y = anchorBounds.top.coerceIn(
                            0,
                            (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                        )
                        return IntOffset(x = sideX, y = y)
                    }
                }
                val preferredX = if (!isUser) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val x = preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height - 4).coerceAtLeast(0)
                return IntOffset(x = x, y = y)
            }
        }
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
            // Inline actions are superseded by the options bar over the
            // input field; keep the composable for compat but hidden.
            MessageActions(
                visible = false,
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
                            // Whole-line hitbox: drags starting anywhere on
                            // the line (avatar, empty space or bubble) shift
                            // the bubble; consuming stops the list's
                            // vertical scrolling from hijacking the swipe.
                            change.consume()
                            dragOffset = (dragOffset + dragAmount)
                                .coerceIn(-swipeThresholdPx * 1.5f, swipeThresholdPx * 1.5f)
                        }
                    )
                }
            }
            .alpha(if (dimmed) 0.55f else 1f)
            // Desktop right-click opens the floating menu.
            .let {
                if (isDesktop && !isSelectMode) it.onRightClick { onMessageRightClick() } else it
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isSelectMode) {
                        onSelectToggle()
                    } else {
                        onMessageTap()
                    }
                },
                onLongClick = {
                    if (!isSelectMode) {
                        onMessageLongPress()
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
                .let {
                    when {
                        isCurrentMatch -> it.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        isSearchMatch -> it.border(1.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                        else -> it
                    }
                }
                .let { modifier -> if (!isUser) modifier.animateContentSize() else modifier }
                .graphicsLayer { translationX = dragOffset }
                .weight(1f, fill = false)
                .widthIn(max = 460.dp)
                .padding(12.dp)
                .onSizeChanged { onBubbleWidthChanged(it.width) }
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
                pendingImageCount = pendingImageCount,
                reasoningText = reasoningText,
                costText = costText,
                timestampText = timestampText
            )
            if (isDesktop && desktopMenuExpanded && !isEditing) {
                Popup(
                    popupPositionProvider = desktopMenuPositionProvider,
                    properties = PopupProperties(focusable = true),
                    onDismissRequest = { onDesktopMenuChange(false) }
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column {
                            MoreMenuItem(label = "Copy", enabled = true, onClick = { onDesktopMenuChange(false); onCopy() })
                            MoreMenuItem(label = "Branch", enabled = true, onClick = { onDesktopMenuChange(false); onBranch() })
                            MoreMenuItem(
                                label = "Edit",
                                enabled = canEdit,
                                onClick = {
                                    onDesktopMenuChange(false)
                                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                                    editedImages = messageImages
                                    isEditing = true
                                }
                            )
                            MoreMenuItem(label = "Add Image", enabled = true, onClick = { onDesktopMenuChange(false); onAddImage() })
                            MoreMenuItem(
                                label = "Delete",
                                enabled = true,
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { onDesktopMenuChange(false); onDelete() }
                            )
                        }
                    }
                }
            }
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

// Right-click (secondary button) opens the desktop floating menu.
private fun Modifier.onRightClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            if (event.buttons.isSecondaryPressed && event.changes.fastAll { it.changedToDown() }) {
                event.changes.forEach { it.consume() }
                onClick()
                break
            }
        }
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
            DefaultAvatarImage(contentDescription = "Default avatar")
        }
    }
}

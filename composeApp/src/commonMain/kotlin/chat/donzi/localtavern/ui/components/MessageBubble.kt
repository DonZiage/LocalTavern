package chat.donzi.localtavern.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun parseMarkdownToAnnotatedString(text: String, defaultColor: Color): AnnotatedString {
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    return remember(text, defaultColor, codeBackgroundColor) {
        buildAnnotatedString {
            val pattern = """(`[^`\n]+`|\*\*\*[^*]+\*\*\*|\*\*[^*]+\*\*|\*[^*]+\*|_[^_]+_)""".toRegex()
            var lastIndex = 0

            pattern.findAll(text).forEach { matchResult ->
                if (matchResult.range.first > lastIndex) {
                    append(text.substring(lastIndex, matchResult.range.first))
                }

                val token = matchResult.value
                when {
                    token.startsWith("`") && token.endsWith("`") -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackgroundColor,
                                fontSize = 14.sp
                            )
                        ) {
                            append(token.removeSurrounding("`"))
                        }
                    }
                    token.startsWith("***") && token.endsWith("***") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(token.removeSurrounding("***"))
                        }
                    }
                    token.startsWith("**") && token.endsWith("**") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(token.removeSurrounding("**"))
                        }
                    }
                    token.startsWith("*") && token.endsWith("*") -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor.copy(alpha = 0.85f))) {
                            append(token.removeSurrounding("*"))
                        }
                    }
                    token.startsWith("_") && token.endsWith("_") -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(token.removeSurrounding("_"))
                        }
                    }
                    else -> append(token)
                }
                lastIndex = matchResult.range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    onEdit: (String) -> Unit,
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onAddImage: () -> Unit = {},
    onBranch: () -> Unit = {},
    avatarData: ByteArray? = null,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    isSwipeable: Boolean = false,
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedTextValue by remember(content) {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }
    val focusRequester = remember { FocusRequester() }

    var showActionsOnMobile by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val actionsVisible = (isHovered || showActionsOnMobile || showMenu) && !isEditing && !isSelectMode

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

    val annotatedContent = parseMarkdownToAnnotatedString(text = content, defaultColor = textColor)

    val rowBgColor = if (isSelectMode && isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
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
                        showActionsOnMobile = false
                    }
                },
                onLongClick = {
                    if (!isSelectMode) {
                        showActionsOnMobile = !showActionsOnMobile
                    }
                }
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectToggle() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        if (isUser) {
            MessageActions(
                visible = actionsVisible,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onEdit = {
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                    isEditing = true
                },
                onCopy = onCopy,
                onDelete = onDelete,
                onAddImage = onAddImage,
                onBranch = onBranch
            )
        } else {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (avatarData != null) showFullImage = true },
                contentAlignment = Alignment.Center
            ) {
                if (avatarData != null) {
                    AsyncImage(
                        model = avatarData,
                        contentDescription = "Character Avatar",
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

        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .let { modifier -> if (!isUser) modifier.animateContentSize() else modifier }
                .let { modifier ->
                    if (isSwipeable) {
                        modifier.pointerInput(Unit) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (totalDrag > 80f) {
                                        onSwipeRight()
                                    } else if (totalDrag < -80f) {
                                        onSwipeLeft()
                                    }
                                    totalDrag = 0f
                                },
                                onDragCancel = { totalDrag = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                }
                            )
                        }
                    } else modifier
                }
                .padding(12.dp)
                .widthIn(max = 460.dp)
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
                    text = annotatedContent,
                    color = textColor,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }
        }

        if (isUser) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (avatarData != null) showFullImage = true },
                contentAlignment = Alignment.Center
            ) {
                if (avatarData != null) {
                    AsyncImage(
                        model = avatarData,
                        contentDescription = "User Avatar",
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
        } else {
            MessageActions(
                visible = actionsVisible,
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onEdit = {
                    editedTextValue = TextFieldValue(content, selection = TextRange(content.length))
                    isEditing = true
                },
                onCopy = onCopy,
                onDelete = onDelete,
                onAddImage = onAddImage,
                onBranch = onBranch
            )
        }
    }

    if (showFullImage && avatarData != null) {
        FullscreenImageViewer(avatarData = avatarData, onDismiss = { showFullImage = false })
    }
}

@Composable
fun MessageActions(
    visible: Boolean,
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onAddImage: () -> Unit,
    onBranch: () -> Unit
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
            Box {
                IconButton(onClick = { onShowMenuChange(true) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                MessageActionMenu(
                    expanded = showMenu,
                    onDismissRequest = { onShowMenuChange(false) },
                    onCopy = onCopy,
                    onDelete = onDelete,
                    onAddImage = onAddImage,
                    onBranch = onBranch
                )
            }
        }
    }
}
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch

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
    onSwipeRight: () -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedTextValue by remember(content) {
        mutableStateOf(TextFieldValue(content, selection = TextRange(content.length)))
    }
    var editedImages by remember(messageImages, isEditing) { mutableStateOf(messageImages) }

    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    var showActionsOnMobile by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    var showGallery by remember { mutableStateOf(false) }
    var galleryInitialIndex by remember { mutableStateOf(0) }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val actionsVisible = (isHovered || showActionsOnMobile || showMenu) && !isEditing && !isSelectMode

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

    val annotatedContent = parseMarkdownToAnnotatedString(text = content, defaultColor = textColor)

    val rowBgColor = if (isSelectMode && isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }

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
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (avatarData != null) showFullImage = true }
                    .align(Alignment.Top),
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
                .pointerInput(isSwipeable) {
                    if (isSwipeable) {
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
                }
                .weight(1f, fill = false)
                .widthIn(max = 460.dp)
                .padding(12.dp)
        ) {
            Column {
                if (content == "...") {
                    AnimatedEllipsis(color = textColor)
                } else if (isEditing) {
                    BasicTextField(
                        value = editedTextValue,
                        onValueChange = {
                            editedTextValue = it
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
                                        editedTextValue = editedTextValue.insertNewline()
                                        coroutineScope.launch {
                                            bringIntoViewRequester.bringIntoView()
                                        }
                                        true
                                    } else {
                                        onEdit(editedTextValue.text, editedImages)
                                        isEditing = false
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
                        cursorBrush = SolidColor(textColor)
                    )
                } else if (content.isNotBlank()) {
                    Text(
                        text = annotatedContent,
                        color = textColor,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                }

                if (isEditing) {
                    if (editedImages.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            editedImages.forEachIndexed { index, bytes ->
                                Box(modifier = Modifier.size(72.dp)) {
                                    AsyncImage(
                                        model = bytes,
                                        contentDescription = "Editing Image Preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    IconButton(
                                        onClick = {
                                            editedImages = editedImages.filterIndexed { i, _ -> i != index }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 6.dp, y = (-6).dp)
                                            .size(20.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove Image",
                                            tint = MaterialTheme.colorScheme.onError,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (messageImages.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .padding(top = if (content.isNotBlank() && content != "...") 8.dp else 0.dp)
                                .fillMaxWidth()
                                .wrapContentHeight()
                        ) {
                            when (messageImages.size) {
                                1 -> {
                                    AsyncImage(
                                        model = messageImages[0],
                                        contentDescription = "Message Attached Image",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                galleryInitialIndex = 0
                                                showGallery = true
                                            }
                                    )
                                }
                                2 -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(160.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        messageImages.forEachIndexed { index, bytes ->
                                            AsyncImage(
                                                model = bytes,
                                                contentDescription = "Message Attached Image Split",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        galleryInitialIndex = index
                                                        showGallery = true
                                                    }
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(210.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AsyncImage(
                                            model = messageImages[0],
                                            contentDescription = "Message Attached Image Collage Left",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                                                .clickable {
                                                    galleryInitialIndex = 0
                                                    showGallery = true
                                                }
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AsyncImage(
                                                model = messageImages[1],
                                                contentDescription = "Message Attached Image Collage Top Right",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(topEnd = 12.dp))
                                                    .clickable {
                                                        galleryInitialIndex = 1
                                                        showGallery = true
                                                    }
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth()
                                            ) {
                                                AsyncImage(
                                                    model = messageImages[2],
                                                    contentDescription = "Message Attached Image Collage Bottom Right",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(bottomEnd = 12.dp))
                                                        .clickable {
                                                            galleryInitialIndex = 2
                                                            showGallery = true
                                                        }
                                                )
                                                if (messageImages.size > 3) {
                                                    val hiddenCount = messageImages.size - 3
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(bottomEnd = 12.dp))
                                                            .background(Color.Black.copy(alpha = 0.55f))
                                                            .clickable {
                                                                galleryInitialIndex = 2
                                                                showGallery = true
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "+$hiddenCount",
                                                            color = Color.White,
                                                            fontSize = 18.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isUser) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (avatarData != null) showFullImage = true }
                    .align(Alignment.Top),
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
            Box(modifier = Modifier.align(Alignment.Top)) {
                actionsBlock()
            }
        }
    }

    if (showFullImage && avatarData != null) {
        FullscreenImageViewer(avatarData = avatarData, onDismiss = { showFullImage = false })
    }

    if (showGallery && messageImages.isNotEmpty()) {
        FullscreenImageViewer(images = messageImages, initialIndex = galleryInitialIndex, onDismiss = { showGallery = false })
    }
}

@Composable
fun EditActions(
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
        }
        IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Save",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
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
                    onBranch = onBranch,
                )
            }
        }
    }
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
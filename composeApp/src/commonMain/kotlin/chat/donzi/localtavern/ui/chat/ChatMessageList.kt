package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.data.pricing.CostEstimator
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.isDesktop
import chat.donzi.localtavern.utils.ContextManager
import chat.donzi.localtavern.utils.MessageTime
import kotlinx.coroutines.launch
import kotlin.time.Clock

// The reversed message list with its scroll-follow and keyboard controls
// (variation swipes, stop-generation on Enter). Kept separate from ChatArea
// so the surrounding column (onboarding/input wiring) stays readable.
@Composable
fun ChatMessageList(
    chatState: ChatUiState,
    activeCharacter: Character?,
    activePersonaName: String,
    activePersonaAvatar: ByteArray?,
    // Effective avatar bytes for the active character (headers carry none;
    // the caller loads them). Preferred over activeCharacter.avatarData.
    activeCharacterAvatar: ByteArray? = null,
    isSelectMode: Boolean,
    selectedMessageIds: Set<String>,
    actions: ChatActions,
    onSelectMessageToggle: (String) -> Unit,
    onRequestDelete: (Message) -> Unit,
    onRequestAddImage: (Message) -> Unit,
    modifier: Modifier = Modifier,
    editingMessageId: String? = null,
    optionsMessageId: String? = null,
    onRequestOptions: (Message) -> Unit = {},
    onCancelEditing: () -> Unit = {},
    onDismissOptions: () -> Unit = {},
    // Desktop floating-menu request (right-click / bottom three-dot).
    // When null, the desktop menu is driven per-item instead.
    onRequestDesktopMenu: ((Message) -> Unit)? = null,
    // In-conversation search: matched ids, the focused match, and whether
    // any search is running (non-blank query dims non-matches).
    searchMatchedIds: Set<String> = emptySet(),
    searchCurrentId: String? = null,
    isSearchActive: Boolean = false
) {
    val messages = chatState.messages
    val siblingsMap = chatState.siblingsMap
    val isGenerating = chatState.isGenerating
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val lastMessage = messages.lastOrNull()
    val siblings = lastMessage?.let { siblingsMap[it.id] ?: listOf(it) } ?: emptyList()

    val currentIndex = lastMessage?.let { siblings.indexOfFirst { child -> child.id == it.id } }?.coerceAtLeast(0) ?: 0
    val totalCount = siblings.size

    // Whether the list is pinned to the newest message. The list is
    // reversed, so index 0 (with no scroll offset) is the bottom.
    var autoScroll by remember { mutableStateOf(true) }
    // (Per-item bubble width is tracked inside ChatMessageItem.)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            autoScroll = index <= 0 && offset <= 0
        }
    }

    // Follow new messages only while the user is at the bottom; if they
    // scrolled up to re-read, don't yank them back down. Suspended while a
    // search is focused on a match so new tokens don't steal the scroll.
    LaunchedEffect(messages.size, autoScroll) {
        if (searchCurrentId == null && autoScroll && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Pin the list to the currently selected search match.
    LaunchedEffect(searchCurrentId) {
        if (searchCurrentId != null) {
            val index = messages.reversed().indexOfFirst { it.id == searchCurrentId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    // Switching sessions must always land on the newest message, regardless
    // of the previous list's scroll position.
    LaunchedEffect(chatState.currentSession?.id) {
        listState.scrollToItem(0)
        autoScroll = true
    }

    // Desktop keyboard shortcuts (variation arrows, Enter to stop) only work
    // while the list has focus; the input bar holds focus otherwise, so
    // request it once on desktop. Mobile relies on touch gestures instead.
    if (isDesktop) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if (isGenerating) {
                        if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed) {
                            actions.onStopGeneration()
                            true
                        } else false
                    } else if (lastMessage != null && lastMessage.role != "user") {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (currentIndex > 0) {
                                    actions.onSelectVariation(siblings[currentIndex - 1].id)
                                    coroutineScope.launch { listState.animateScrollToItem(0) }
                                    true
                                } else if (totalCount > 1) {
                                    actions.onSelectVariation(siblings[totalCount - 1].id)
                                    coroutineScope.launch { listState.animateScrollToItem(0) }
                                    true
                                } else false
                            }
                            Key.DirectionRight -> {
                                if (currentIndex < totalCount - 1) {
                                    actions.onSelectVariation(siblings[currentIndex + 1].id)
                                    coroutineScope.launch { listState.animateScrollToItem(0) }
                                    true
                                } else if (lastMessage.parentId == null) {
                                    // Greeting roots are static: swiping past
                                    // the last one wraps, never generates.
                                    if (totalCount > 1) {
                                        actions.onSelectVariation(siblings[0].id)
                                        coroutineScope.launch { listState.animateScrollToItem(0) }
                                        true
                                    } else false
                                } else {
                                    actions.onGenerateNewVariation(lastMessage.id)
                                    coroutineScope.launch { listState.animateScrollToItem(0) }
                                    true
                                }
                            }
                            else -> false
                        }
                    } else false
                } else false
            },
        contentPadding = PaddingValues(8.dp),
        reverseLayout = true
    ) {
        items(messages.reversed(), key = { it.id }) { message ->
            val isEditingTarget = editingMessageId == message.id
            val isSearchMatch = message.id in searchMatchedIds
            val isCurrentMatch = searchCurrentId != null && message.id == searchCurrentId
            val isDimmed = (editingMessageId != null && !isEditingTarget) ||
                (isSearchActive && searchMatchedIds.isNotEmpty() && !isSearchMatch)
            ChatMessageItem(
                message = message,
                isLastMessage = messages.lastOrNull()?.id == message.id,
                isGenerating = isGenerating,
                isSelectMode = isSelectMode,
                selectedMessageIds = selectedMessageIds,
                activeCharacter = activeCharacter,
                activePersonaName = activePersonaName,
                activePersonaAvatar = activePersonaAvatar,
                activeCharacterAvatar = activeCharacterAvatar,
                siblingsMap = siblingsMap,
                actions = actions,
                onSelectMessageToggle = onSelectMessageToggle,
                onRequestDelete = onRequestDelete,
                onRequestAddImage = onRequestAddImage,
                isDimmed = isDimmed,
                isEditingTarget = isEditingTarget,
                isSearchMatch = isSearchMatch,
                isCurrentMatch = isCurrentMatch,
                // Tap never opens options: it only cancels editing or
                // dismisses an already-open bar. Holding (or right-click /
                // the desktop three-dot) is what opens options.
                onMessageTap = {
                    when {
                        editingMessageId != null && !isEditingTarget -> onCancelEditing()
                        editingMessageId != null && isEditingTarget -> Unit
                        optionsMessageId == message.id -> onDismissOptions()
                        else -> Unit
                    }
                },
                onMessageLongPress = {
                    when {
                        editingMessageId != null -> Unit
                        isDesktop -> onRequestDesktopMenu?.invoke(message)
                        optionsMessageId == message.id -> onDismissOptions()
                        else -> onRequestOptions(message)
                    }
                },
                onMessageRightClick = {
                    if (editingMessageId == null) onRequestDesktopMenu?.invoke(message)
                }
            )
        }
    }

    // Jump-to-latest: autoScroll pins the list while the user is at the
    // bottom; once they scroll up, offer a one-tap way back instead of
    // leaving them stranded above new messages.
    if (!autoScroll && messages.isNotEmpty()) {
        SmallFloatingActionButton(
            onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Scroll to latest message"
            )
        }
    }
    }
}

@Composable
private fun ChatMessageItem(
    message: Message,
    isLastMessage: Boolean,
    isGenerating: Boolean,
    isSelectMode: Boolean,
    selectedMessageIds: Set<String>,
    activeCharacter: Character?,
    activePersonaName: String,
    activePersonaAvatar: ByteArray?,
    // Effective avatar bytes for the active character (headers carry none).
    activeCharacterAvatar: ByteArray? = null,
    siblingsMap: Map<String, List<Message>>,
    actions: ChatActions,
    onSelectMessageToggle: (String) -> Unit,
    onRequestDelete: (Message) -> Unit,
    onRequestAddImage: (Message) -> Unit,
    isDimmed: Boolean = false,
    isEditingTarget: Boolean = false,
    isSearchMatch: Boolean = false,
    isCurrentMatch: Boolean = false,
    onMessageTap: () -> Unit = {},
    onMessageLongPress: () -> Unit = {},
    onMessageRightClick: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val isUserMessage = message.role == "user"

    val currentAvatar = remember(isUserMessage, activePersonaAvatar, activeCharacterAvatar, activeCharacter?.avatarData) {
        if (isUserMessage) {
            activePersonaAvatar
        } else {
            activeCharacterAvatar ?: activeCharacter?.avatarData
        }
    }

    val isSwipeable = !isUserMessage && isLastMessage && !isGenerating && !isDimmed && !isEditingTarget

    val msgSiblings = siblingsMap[message.id] ?: listOf(message)
    val msgCurrentIndex = msgSiblings.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
    val msgTotalCount = msgSiblings.size

    val displayContent = remember(message.content, activeCharacter?.name, activePersonaName) {
        ContextManager.replaceSimpleMacros(
            text = message.content,
            charName = activeCharacter?.name.orEmpty(),
            userName = activePersonaName
        )
    }
    // Per-bubble desktop floating menu (right-click / long-press /
    // bottom three-dot). Mobile long-press goes to the options bar via
    // [onMessageLongPress] instead.
    var desktopMenuExpanded by remember { mutableStateOf(false) }
    var bubbleWidthPx by remember { mutableStateOf(0) }
    val bubbleDensity = androidx.compose.ui.platform.LocalDensity.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start
    ) {
        MessageBubble(
            content = displayContent,
            isUser = isUserMessage,
            onEdit = { newContent, updatedImages -> actions.onEditMessage(message.id, newContent, updatedImages) },
            onCopy = { clipboardManager.setText(AnnotatedString(message.content)) },
            onDelete = { onRequestDelete(message) },
            onAddImage = { onRequestAddImage(message) },
            onBranch = { actions.onBranchMessage(message) },
            avatarData = currentAvatar,
            messageImages = message.images,
            pendingImageCount = (message.imageRefs.size - message.images.size).coerceAtLeast(0),
            isSelectMode = isSelectMode,
            isSelected = selectedMessageIds.contains(message.id),
            onSelectToggle = { onSelectMessageToggle(message.id) },
            isSwipeable = isSwipeable,
            isGenerating = isGenerating,
            // Editing the in-flight message would be overwritten by the
            // stream; gate only the streaming message.
            canEdit = !(isGenerating && isLastMessage),
            reasoningText = message.reasoningText?.takeIf { it.isNotBlank() },
            dimmed = isDimmed,
            isSearchMatch = isSearchMatch,
            isCurrentMatch = isCurrentMatch,
            onMessageTap = onMessageTap,
            onMessageLongPress = {
                if (isDesktop) desktopMenuExpanded = !desktopMenuExpanded else onMessageLongPress()
            },
            onMessageRightClick = { desktopMenuExpanded = !desktopMenuExpanded },
            desktopMenuExpanded = desktopMenuExpanded,
            onDesktopMenuChange = { desktopMenuExpanded = it },
            onBubbleWidthChanged = { if (it != bubbleWidthPx) bubbleWidthPx = it },
            costText = message.costEstimateUsd?.let { CostEstimator.formatUsd(it) },
            timestampText = remember(message.timestamp) {
                // The placeholder has no real timestamp yet; stamping it
                // "just now" would flicker, so it shows no time at all.
                if (message.content == "...") null
                else MessageTime.format(message.timestamp, Clock.System.now().toEpochMilliseconds())
            },
            onSwipeRight = {
                if (!isGenerating) {
                    if (msgCurrentIndex > 0) {
                        actions.onSelectVariation(msgSiblings[msgCurrentIndex - 1].id)
                    } else if (msgTotalCount > 1) {
                        actions.onSelectVariation(msgSiblings[msgTotalCount - 1].id)
                    }
                }
            },
            onSwipeLeft = {
                if (!isGenerating) {
                    if (msgCurrentIndex < msgTotalCount - 1) {
                        actions.onSelectVariation(msgSiblings[msgCurrentIndex + 1].id)
                    } else if (message.parentId == null) {
                        // Greeting roots wrap to the first greeting.
                        if (msgTotalCount > 1) actions.onSelectVariation(msgSiblings[0].id)
                    } else {
                        actions.onGenerateNewVariation(message.id)
                    }
                }
            }
        )

        // Bottom strip: the swipe counter (assistant last message) and a
        // vertical three-dot below the bubble's right bottom corner
        // (at the same height as the swipe counter). The strip is
        // constrained to the bubble width (54 dp gutter + 460 dp max),
        // so the dot hugs the bubble instead of the chat-area edge.
        val showCounter = !isUserMessage && isLastMessage && !isSelectMode && !isDimmed && !isEditingTarget
        val showDots = !isSelectMode && !isDimmed && !isEditingTarget
        if (showCounter || showDots) {
            // Hug the measured bubble width so the dot sits below its
            // right corner even for short bubbles; fall back to the
            // 460 dp bubble max before the first layout.
            val bubbleWidthModifier = if (bubbleWidthPx > 0) {
                Modifier.width(with(bubbleDensity) { bubbleWidthPx.toDp() })
            } else {
                Modifier.fillMaxWidth().widthIn(max = 460.dp)
            }
            Row(
                modifier = Modifier
                    .padding(
                        start = if (!isUserMessage) 54.dp else 0.dp,
                        end = if (isUserMessage) 54.dp else 0.dp,
                        top = 2.dp,
                        bottom = 6.dp
                    )
                    .then(bubbleWidthModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: swipe counter.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showCounter) {
                        IconButton(
                            onClick = {
                                if (msgCurrentIndex > 0) {
                                    actions.onSelectVariation(msgSiblings[msgCurrentIndex - 1].id)
                                } else if (msgTotalCount > 1) {
                                    actions.onSelectVariation(msgSiblings[msgTotalCount - 1].id)
                                }
                            },
                            enabled = !isGenerating,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous variation",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = "${msgCurrentIndex + 1} / $msgTotalCount",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        IconButton(
                            onClick = {
                                if (msgCurrentIndex < msgTotalCount - 1) {
                                    actions.onSelectVariation(msgSiblings[msgCurrentIndex + 1].id)
                                } else if (message.parentId == null) {
                                    if (msgTotalCount > 1) actions.onSelectVariation(msgSiblings[0].id)
                                } else {
                                    actions.onGenerateNewVariation(message.id)
                                }
                            },
                            enabled = !isGenerating,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next variation",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                // Right-bottom corner of the bubble.
                if (showDots) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (isDesktop) desktopMenuExpanded = !desktopMenuExpanded else onMessageLongPress()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Message options",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

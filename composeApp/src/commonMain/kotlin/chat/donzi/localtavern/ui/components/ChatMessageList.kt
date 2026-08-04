package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import chat.donzi.localtavern.utils.ContextManager
import kotlinx.coroutines.launch

// The reversed message list with its scroll-follow and keyboard controls
// (variation swipes, stop-generation on Enter). Kept separate from ChatArea
// so the surrounding column (onboarding/input wiring) stays readable.
@Composable
fun ChatMessageList(
    chatState: ChatUiState,
    activeCharacter: Character?,
    activePersonaName: String,
    activePersonaAvatar: ByteArray?,
    isSelectMode: Boolean,
    selectedMessageIds: Set<String>,
    actions: ChatActions,
    onSelectMessageToggle: (String) -> Unit,
    onRequestDelete: (Message) -> Unit,
    onRequestAddImage: (Message) -> Unit,
    modifier: Modifier = Modifier
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

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            autoScroll = index <= 0 && offset <= 0
        }
    }

    // Follow new messages only while the user is at the bottom; if they
    // scrolled up to re-read, don't yank them back down.
    LaunchedEffect(messages.size, autoScroll) {
        if (autoScroll && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Switching sessions must always land on the newest message, regardless
    // of the previous list's scroll position.
    LaunchedEffect(chatState.currentSession?.id) {
        listState.scrollToItem(0)
        autoScroll = true
    }

    LazyColumn(
        state = listState,
        modifier = modifier
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
            ChatMessageItem(
                message = message,
                isLastMessage = messages.lastOrNull()?.id == message.id,
                isGenerating = isGenerating,
                isSelectMode = isSelectMode,
                selectedMessageIds = selectedMessageIds,
                activeCharacter = activeCharacter,
                activePersonaName = activePersonaName,
                activePersonaAvatar = activePersonaAvatar,
                siblingsMap = siblingsMap,
                actions = actions,
                onSelectMessageToggle = onSelectMessageToggle,
                onRequestDelete = onRequestDelete,
                onRequestAddImage = onRequestAddImage
            )
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
    siblingsMap: Map<String, List<Message>>,
    actions: ChatActions,
    onSelectMessageToggle: (String) -> Unit,
    onRequestDelete: (Message) -> Unit,
    onRequestAddImage: (Message) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val isUserMessage = message.role == "user"

    val currentAvatar = remember(isUserMessage, activePersonaAvatar, activeCharacter?.avatarData) {
        if (isUserMessage) {
            activePersonaAvatar
        } else {
            activeCharacter?.avatarData
        }
    }

    val isSwipeable = !isUserMessage && isLastMessage && !isGenerating

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
            costText = message.costEstimateUsd?.let { CostEstimator.formatUsd(it) },
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
                    } else {
                        actions.onGenerateNewVariation(message.id)
                    }
                }
            }
        )

        if (!isUserMessage && isLastMessage && !isSelectMode) {
            Row(
                modifier = Modifier
                    .padding(start = 54.dp, top = 2.dp, bottom = 6.dp)
                    .widthIn(max = 460.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
    }
}

package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.MessageEntity

@Composable
fun ChatArea(
    activeCharacter: CharacterEntity?,
    activePersonaAvatar: ByteArray?,
    messages: List<MessageEntity>,
    siblingsMap: Map<Long, List<MessageEntity>>,
    onSendMessage: (String) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onRegenerate: () -> Unit,
    onSelectVariation: (Long) -> Unit,
    onGenerateNewVariation: (Long) -> Unit,
    isSelectMode: Boolean = false,
    selectedMessageIds: Set<Long> = emptySet(),
    onSelectMessageToggle: (Long) -> Unit = {},
    onEnterSelectMode: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (activeCharacter == null && messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(
                        text = "Welcome to LocalTavern",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = "Select a character from the menu to begin your journey, or simply type below to talk with the Assistant.",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 28.sp
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { message ->
                    val isUserMessage = message.role == "user"
                    val currentAvatar = if (isUserMessage) {
                        activePersonaAvatar
                    } else {
                        activeCharacter?.avatarData
                    }

                    val isLastMessage = messages.lastOrNull()?.id == message.id
                    val isSwipeable = !isUserMessage && isLastMessage

                    val siblings = siblingsMap[message.id] ?: listOf(message)
                    val currentIndex = siblings.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
                    val totalCount = siblings.size

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start
                    ) {
                        MessageBubble(
                            content = message.content,
                            isUser = isUserMessage,
                            onEdit = { newContent -> onEditMessage(message.id, newContent) },
                            onDelete = { onDeleteMessage(message.id) },
                            avatarData = currentAvatar,
                            isSelectMode = isSelectMode,
                            isSelected = selectedMessageIds.contains(message.id),
                            onSelectToggle = { onSelectMessageToggle(message.id) },
                            isSwipeable = isSwipeable,
                            onSwipeRight = {
                                if (currentIndex > 0) {
                                    onSelectVariation(siblings[currentIndex - 1].id)
                                }
                            },
                            onSwipeLeft = {
                                if (currentIndex < totalCount - 1) {
                                    onSelectVariation(siblings[currentIndex + 1].id)
                                } else {
                                    onGenerateNewVariation(message.id)
                                }
                            }
                        )

                        if (isSwipeable) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 54.dp, top = 2.dp, bottom = 6.dp)
                                    .widthIn(max = 460.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (currentIndex > 0) {
                                            onSelectVariation(siblings[currentIndex - 1].id)
                                        }
                                    },
                                    enabled = currentIndex > 0,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Previous variation",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                Text(
                                    text = "${currentIndex + 1} / $totalCount",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )

                                IconButton(
                                    onClick = {
                                        if (currentIndex < totalCount - 1) {
                                            onSelectVariation(siblings[currentIndex + 1].id)
                                        } else {
                                            onGenerateNewVariation(message.id)
                                        }
                                    },
                                    enabled = true,
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
            }
        }

        if (!isSelectMode) {
            ChatInputBar(
                onSendMessage = onSendMessage,
                onRegenerate = onRegenerate,
                canRegenerate = messages.any { it.role == "user" },
                onEnterSelectMode = onEnterSelectMode,
                canDelete = messages.isNotEmpty()
            )
        }
    }
}
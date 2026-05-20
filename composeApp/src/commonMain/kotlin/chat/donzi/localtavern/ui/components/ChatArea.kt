package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onSendMessage: (String) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onRegenerate: () -> Unit,
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

                    val isFirstMessage = activeCharacter != null && messages.firstOrNull()?.id == message.id && !isUserMessage

                    val greetings = remember(activeCharacter) {
                        val list = mutableListOf<String>()
                        activeCharacter?.let {
                            if (!it.firstMes.isNullOrBlank()) list.add(it.firstMes)
                            it.altGreetings?.split("|||")?.filter { g -> g.isNotBlank() }?.let { alt ->
                                list.addAll(alt)
                            }
                        }
                        if (list.isEmpty()) list.add("")
                        list
                    }

                    val currentGreetingIndex = if (isFirstMessage) {
                        val idx = greetings.indexOf(message.content)
                        if (idx != -1) idx else 0
                    } else 0

                    MessageBubble(
                        content = message.content,
                        isUser = isUserMessage,
                        onEdit = { newContent -> onEditMessage(message.id, newContent) },
                        onDelete = { onDeleteMessage(message.id) },
                        avatarData = currentAvatar,
                        isSelectMode = isSelectMode,
                        isSelected = selectedMessageIds.contains(message.id),
                        onSelectToggle = { onSelectMessageToggle(message.id) },
                        isFirstMessage = isFirstMessage,
                        currentGreetingIndex = currentGreetingIndex,
                        totalGreetingsCount = greetings.size,
                        onGreetingSwipe = { index ->
                            if (index in greetings.indices) {
                                onEditMessage(message.id, greetings[index])
                            }
                        }
                    )
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
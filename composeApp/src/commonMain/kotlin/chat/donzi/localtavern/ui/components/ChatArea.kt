package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.MessageEntity
import chat.donzi.localtavern.utils.ContextManager

private enum class OnboardingStep {
    API, PERSONA, CHARACTER
}

@Composable
fun ChatArea(
    activeCharacter: CharacterEntity?,
    activePersonaName: String,
    activePersonaAvatar: ByteArray?,
    messages: List<MessageEntity>,
    siblingsMap: Map<Long, List<MessageEntity>>,
    hasApiProfile: Boolean,
    hasPersona: Boolean,
    hasCharacter: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToPersonas: () -> Unit,
    onNavigateToCharacters: () -> Unit,
    onSendMessage: (String) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onDeleteMessages: (List<Long>) -> Unit = {},
    onRegenerate: () -> Unit,
    onSelectVariation: (Long) -> Unit,
    onGenerateNewVariation: (Long) -> Unit,
    isSelectMode: Boolean = false,
    selectedMessageIds: Set<Long> = emptySet(),
    onSelectMessageToggle: (Long) -> Unit = {},
    onEnterSelectMode: () -> Unit = {},
    isGenerating: Boolean = false,
    onStopGeneration: () -> Unit = {},
    onManageChats: () -> Unit,
    onBranchMessage: (MessageEntity) -> Unit = {},
    onGoToParentChat: (() -> Unit)? = null
) {
    var messageToDelete by remember { mutableStateOf<MessageEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (activeCharacter == null && messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text(
                        text = "Welcome to LocalTavern",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val visibleSteps = remember(hasApiProfile, hasPersona, hasCharacter) {
                        mutableListOf<OnboardingStep>().apply {
                            if (!hasApiProfile) add(OnboardingStep.API)
                            if (!hasPersona) add(OnboardingStep.PERSONA)
                            if (!hasCharacter) add(OnboardingStep.CHARACTER)
                        }
                    }

                    if (visibleSteps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            visibleSteps.forEachIndexed { index, step ->
                                val stepDisplayNumber = index + 1

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "$stepDisplayNumber - ",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )

                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp),
                                        tonalElevation = 1.dp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                when (step) {
                                                    OnboardingStep.API -> onNavigateToSettings()
                                                    OnboardingStep.PERSONA -> onNavigateToPersonas()
                                                    OnboardingStep.CHARACTER -> onNavigateToCharacters()
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when (step) {
                                                    OnboardingStep.API -> "Add an API connection in Settings"
                                                    OnboardingStep.PERSONA -> "Introduce yourself in Personas"
                                                    OnboardingStep.CHARACTER -> "Meet your first Character"
                                                },
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = when (step) {
                                                    OnboardingStep.API -> Icons.Default.Settings
                                                    OnboardingStep.PERSONA -> Icons.Default.Person
                                                    OnboardingStep.CHARACTER -> Icons.Default.AccountBox
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Select a character from the menu side panel to begin your conversation.",
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(36.dp))

                    Text(
                        text = "Or type below to talk with the Assistant",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
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
                    val isSwipeable = !isUserMessage && isLastMessage && !isGenerating

                    val siblings = siblingsMap[message.id] ?: listOf(message)
                    val currentIndex = siblings.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
                    val totalCount = siblings.size

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
                            onEdit = { newContent -> onEditMessage(message.id, newContent) },
                            onDelete = { messageToDelete = message },
                            onBranch = { onBranchMessage(message) },
                            avatarData = currentAvatar,
                            isSelectMode = isSelectMode,
                            isSelected = selectedMessageIds.contains(message.id),
                            onSelectToggle = { onSelectMessageToggle(message.id) },
                            isSwipeable = isSwipeable,
                            onSwipeRight = {
                                if (currentIndex > 0 && !isGenerating) {
                                    onSelectVariation(siblings[currentIndex - 1].id)
                                }
                            },
                            onSwipeLeft = {
                                if (!isGenerating) {
                                    if (currentIndex < totalCount - 1) {
                                        onSelectVariation(siblings[currentIndex + 1].id)
                                    } else {
                                        onGenerateNewVariation(message.id)
                                    }
                                }
                            }
                        )

                        if (!isUserMessage && isLastMessage) {
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
                                    enabled = currentIndex > 0 && !isGenerating,
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
            }
        }

        if (!isSelectMode) {
            ChatInputBar(
                onSendMessage = onSendMessage,
                onRegenerate = onRegenerate,
                canRegenerate = messages.any { it.role == "user" },
                onEnterSelectMode = onEnterSelectMode,
                canDelete = messages.isNotEmpty(),
                isGenerating = isGenerating,
                onStopGeneration = onStopGeneration,
                onManageChats = onManageChats,
                canManageChats = activeCharacter != null,
                onGoToParent = onGoToParentChat
            )
        }
    }

    if (messageToDelete != null) {
        val currentMsg = messageToDelete!!
        val isUserMsg = currentMsg.role == "user"
        val siblings = siblingsMap[currentMsg.id] ?: listOf(currentMsg)
        val totalCount = siblings.size

        Dialog(onDismissRequest = { messageToDelete = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 340.dp)
                    .wrapContentSize()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isUserMsg || totalCount < 2) "Delete message?" else "Delete option",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (isUserMsg || totalCount < 2) "This action cannot be undone." else "Delete this swipe or the entire message?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isUserMsg || totalCount < 2) {
                            TextButton(onClick = { messageToDelete = null }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    onDeleteMessage(currentMsg.id)
                                    messageToDelete = null
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete")
                            }
                        } else {
                            TextButton(onClick = { messageToDelete = null }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    onDeleteMessage(currentMsg.id)
                                    messageToDelete = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7B1FA2),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Swipe")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    onDeleteMessages(siblings.map { it.id })
                                    messageToDelete = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7B1FA2),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Message")
                            }
                        }
                    }
                }
            }
        }
    }
}
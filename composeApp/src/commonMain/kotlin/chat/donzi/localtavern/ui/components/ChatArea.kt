package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.utils.rememberImagePickerLauncher

@Composable
fun ChatArea(
    chatState: ChatUiState,
    activeCharacter: Character?,
    activePersonaName: String,
    activePersonaAvatar: ByteArray?,
    hasApiProfile: Boolean,
    hasPersona: Boolean,
    hasCharacter: Boolean,
    isSelectMode: Boolean = false,
    selectedMessageIds: Set<String> = emptySet(),
    onSelectMessageToggle: (String) -> Unit = {},
    onEnterSelectMode: () -> Unit = {},
    actions: ChatActions
) {
    var messageToDelete by remember { mutableStateOf<Message?>(null) }
    var imageTargetMessageId by remember { mutableStateOf<String?>(null) }

    val bubbleImagePicker = rememberImagePickerLauncher(
        onImagesPicked = { imagesList ->
            if (imagesList.isNotEmpty()) {
                imageTargetMessageId?.let { targetId ->
                    actions.onAddImageToMessage(targetId, imagesList)
                }
            }
            imageTargetMessageId = null
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        if (activeCharacter == null && chatState.messages.isEmpty()) {
            ChatOnboarding(
                hasApiProfile = hasApiProfile,
                hasPersona = hasPersona,
                hasCharacter = hasCharacter,
                onNavigateToSettings = actions.onNavigateToSettings,
                onNavigateToPersonas = actions.onNavigateToPersonas,
                onNavigateToCharacters = actions.onNavigateToCharacters,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp)
            )
        } else {
            ChatMessageList(
                chatState = chatState,
                activeCharacter = activeCharacter,
                activePersonaName = activePersonaName,
                activePersonaAvatar = activePersonaAvatar,
                isSelectMode = isSelectMode,
                selectedMessageIds = selectedMessageIds,
                actions = actions,
                onSelectMessageToggle = onSelectMessageToggle,
                onRequestDelete = { messageToDelete = it },
                onRequestAddImage = { message ->
                    imageTargetMessageId = message.id
                    bubbleImagePicker()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        ChatInputArea(
            chatState = chatState,
            activeCharacter = activeCharacter,
            isSelectMode = isSelectMode,
            actions = actions,
            onEnterSelectMode = onEnterSelectMode
        )
    }

    MessageDeleteDialog(
        messageToDelete = messageToDelete,
        siblingsMap = chatState.siblingsMap,
        onDismiss = { messageToDelete = null },
        onDeleteMessage = { id -> actions.onDeleteMessage(id) },
        onDeleteMessages = { ids -> actions.onDeleteMessages(ids) }
    )
}

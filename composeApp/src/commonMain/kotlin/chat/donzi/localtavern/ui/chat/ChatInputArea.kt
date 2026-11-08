package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.data.pricing.CostEstimator
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message

// Draft input (text + attachments) and the live cost readout of the
// in-flight generation. The draft state lives here rather than inside
// ChatInputBar so it survives select mode, where the input bar leaves the
// composition entirely: this composable stays composed either way. Drafts are
// keyed by session: switching chats parks the in-progress message and
// restores it when coming back, instead of discarding it.
//
// Two temporary overlays replace the input field:
// - While a message's options are open ([optionsMessage]), the options
//   bar is shown instead of the input (draft hidden, restored on close).
// - While a message is being edited ([editingMessage]), its text (and
//   images) are parked in the input field with an "Editing message"
//   indicator above it. The previous draft is stashed and restored on
//   save/cancel. Tapping outside the input/buttons cancels (handled by
//   ChatMessageList calling [onEditingCancel]).
@Composable
fun ChatInputArea(
    chatState: ChatUiState,
    activeCharacter: Character?,
    isSelectMode: Boolean,
    actions: ChatActions,
    onEnterSelectMode: () -> Unit,
    sendWithCtrlEnter: Boolean = false,
    optionsMessage: Message? = null,
    onOptionsClose: () -> Unit = {},
    onOptionsEdit: (Message) -> Unit = {},
    onOptionsCopy: (Message) -> Unit = {},
    onOptionsBranch: (Message) -> Unit = {},
    onOptionsAddImage: (Message) -> Unit = {},
    onOptionsDelete: (Message) -> Unit = {},
    editingMessage: Message? = null,
    onEditingCancel: () -> Unit = {}
) {
    val sessionKey = chatState.currentSession?.id
    var draftText by rememberSaveable(sessionKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var draftImages by remember(sessionKey) { mutableStateOf<List<ByteArray>>(emptyList()) }

    // Parked draft while a message is being edited in the input field.
    var parkedDraftText by remember(sessionKey) { mutableStateOf<TextFieldValue?>(null) }
    var parkedDraftImages by remember(sessionKey) { mutableStateOf<List<ByteArray>>(emptyList()) }
    var editingId by remember(sessionKey) { mutableStateOf<String?>(null) }

    fun startEditing(message: Message) {
        if (editingId != message.id) {
            parkedDraftText = draftText
            parkedDraftImages = draftImages
            editingId = message.id
            draftText = TextFieldValue(message.content, selection = TextRange(message.content.length))
            draftImages = message.images
        }
    }

    fun finishEditing(save: Boolean) {
        if (save && editingId != null) {
            actions.onEditMessage(editingId!!, draftText.text, draftImages)
        }
        draftText = parkedDraftText ?: TextFieldValue("")
        draftImages = parkedDraftImages
        parkedDraftText = null
        parkedDraftImages = emptyList()
        editingId = null
        onEditingCancel()
    }

    LaunchedEffect(editingMessage?.id) {
        editingMessage?.let { startEditing(it) }
    }

    if (!isSelectMode) {
        // Live cost estimate of the in-flight generation, refreshed as the
        // output tokens accumulate. Heuristic (tokenizer-based), labelled "~".
        val liveCost = chatState.liveCostEstimate
        if (liveCost != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "~${CostEstimator.formatUsd(liveCost.totalUsd)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        when {
            optionsMessage != null && editingMessage == null -> {
                // Message options replace the input field temporarily.
                // Draft stays in draftText/draftImages and reappears on close.
                MessageOptionsBar(
                    message = optionsMessage,
                    canEdit = !(chatState.isGenerating && chatState.messages.lastOrNull()?.id == optionsMessage.id),
                    onEdit = { onOptionsEdit(optionsMessage) },
                    onCopy = { onOptionsCopy(optionsMessage) },
                    onBranch = { onOptionsBranch(optionsMessage) },
                    onAddImage = { onOptionsAddImage(optionsMessage); onOptionsClose() },
                    onDelete = { onOptionsDelete(optionsMessage); onOptionsClose() },
                    onClose = onOptionsClose
                )
            }
            editingMessage != null && editingId != null -> {
                EditingIndicator(onCancel = { finishEditing(save = false) })
                ChatInputBar(
                    textValue = draftText,
                    onTextValueChange = { draftText = it },
                    attachedImages = draftImages,
                    onAttachedImagesChange = { draftImages = it },
                    onSendMessage = actions.onSendMessage,
                    onRegenerate = actions.onRegenerate,
                    canRegenerate = false,
                    onEnterSelectMode = onEnterSelectMode,
                    canDelete = false,
                    isGenerating = chatState.isGenerating,
                    onStopGeneration = actions.onStopGeneration,
                    onManageChats = actions.onManageChats,
                    canManageChats = false,
                    onGoToParent = null,
                    onExportChat = null,
                    canExportChat = false,
                    sendWithCtrlEnter = sendWithCtrlEnter,
                    sessionKey = (sessionKey ?: "") + ":" + editingId,
                    isEditingMode = true,
                    onEditingSave = { _, _ -> finishEditing(save = true) }
                )
            }
            else -> {
                ChatInputBar(
                    textValue = draftText,
                    onTextValueChange = { draftText = it },
                    attachedImages = draftImages,
                    onAttachedImagesChange = { draftImages = it },
                    onSendMessage = actions.onSendMessage,
                    onRegenerate = actions.onRegenerate,
                    canRegenerate = chatState.messages.any { it.role == "user" } && !chatState.isGenerating,
                    onEnterSelectMode = onEnterSelectMode,
                    canDelete = chatState.messages.isNotEmpty(),
                    isGenerating = chatState.isGenerating,
                    onStopGeneration = actions.onStopGeneration,
                    onManageChats = actions.onManageChats,
                    canManageChats = activeCharacter != null,
                    onGoToParent = actions.onGoToParentChat,
                    onExportChat = actions.onExportChat,
                    canExportChat = chatState.messages.isNotEmpty(),
                    sendWithCtrlEnter = sendWithCtrlEnter,
                    sessionKey = sessionKey
                )
            }
        }
    } else {
        // Leaving select mode must not strand an edit/options state: the
        // list handles outside-press cancellation; here we just hide the
        // input (draft + parked edit survive via state above).
    }
}

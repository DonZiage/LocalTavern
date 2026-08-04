package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.data.pricing.CostEstimator
import chat.donzi.localtavern.domain.Character

// Draft input (text + attachments) and the live cost readout of the
// in-flight generation. The draft state lives here rather than inside
// ChatInputBar so it survives select mode, where the input bar leaves the
// composition entirely: this composable stays composed either way.
@Composable
fun ChatInputArea(
    chatState: ChatUiState,
    activeCharacter: Character?,
    isSelectMode: Boolean,
    actions: ChatActions,
    onEnterSelectMode: () -> Unit,
    sendWithCtrlEnter: Boolean = false
) {
    var draftText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var draftImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }

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
            sendWithCtrlEnter = sendWithCtrlEnter
        )
    }
}

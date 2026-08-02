package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.domain.Message

data class ChatActions(
    // Returns true when the send was accepted (message committed to the
    // session); false when it was refused, so the input keeps the draft.
    val onSendMessage: (String, List<ByteArray>) -> Boolean,
    val onEditMessage: (String, String, List<ByteArray>) -> Unit,
    val onDeleteMessage: (String) -> Unit,
    val onDeleteMessages: (List<String>) -> Unit,
    val onRegenerate: () -> Unit,
    val onSelectVariation: (String) -> Unit,
    val onGenerateNewVariation: (String) -> Unit,
    val onStopGeneration: () -> Unit,
    val onManageChats: () -> Unit,
    val onBranchMessage: (Message) -> Unit,
    val onGoToParentChat: (() -> Unit)?,
    val onAddImageToMessage: (String, List<ByteArray>) -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToPersonas: () -> Unit,
    val onNavigateToCharacters: () -> Unit
)

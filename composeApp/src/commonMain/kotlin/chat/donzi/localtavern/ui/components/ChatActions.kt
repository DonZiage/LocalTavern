package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.domain.Message

data class ChatActions(
    val onSendMessage: (String, List<ByteArray>) -> Unit,
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

package chat.donzi.localtavern.ui.layout
import chat.donzi.localtavern.ui.chat.ChatActions

import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.CharacterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Wires every chat action from the controller/state down to the ChatActions
// bag consumed by ChatArea. Kept outside MainScreen so the composable stays
// orchestration-only.
internal fun buildChatActions(
    chatController: ChatController,
    activeSessionId: String?,
    activeCharacter: Character?,
    activePersona: Persona?,
    messages: List<Message>,
    siblingsMap: Map<String, List<Message>>,
    isGenerating: Boolean,
    currentSessionDetails: Session?,
    scope: CoroutineScope,
    isDesktop: Boolean,
    onSendMessage: (String, List<ByteArray>) -> Boolean,
    onSetActiveSession: (String?) -> Unit,
    onManageChats: () -> Unit,
    onRequestPersonaEdit: () -> Unit,
    onRequestCharacterMenu: () -> Unit,
    onActiveDrawerChange: (ActiveDrawer) -> Unit
): ChatActions = ChatActions(
    onSendMessage = onSendMessage,
    onEditMessage = { id, content, updatedImages ->
        activeSessionId?.let { chatController.editMessage(it, id, content, updatedImages) }
    },
    onDeleteMessage = { id ->
        activeSessionId?.let { chatController.deleteMessage(it, id) }
    },
    onDeleteMessages = { ids ->
        activeSessionId?.let { chatController.deleteMessages(it, ids) }
    },
    onRegenerate = {
        activeSessionId?.let { chatController.regenerate(it, activeCharacter, activePersona) }
    },
    onSelectVariation = { variationId ->
        activeSessionId?.let { sessionId ->
            val msg = messages.find { it.id == variationId } ?: siblingsMap[variationId]?.find { it.id == variationId }
            chatController.selectVariation(sessionId, variationId, msg?.parentId)
        }
    },
    onGenerateNewVariation = { lastMessageId ->
        if (!isGenerating) {
            activeSessionId?.let { sessionId ->
                val existingMsg = messages.find { it.id == lastMessageId }
                    ?: siblingsMap[lastMessageId]?.find { it.id == lastMessageId }
                if (existingMsg != null) {
                    chatController.requestAiResponse(sessionId, activeCharacter, activePersona, existingMsg.parentId)
                }
            }
        }
    },
    onStopGeneration = { chatController.stopGeneration() },
    onManageChats = onManageChats,
    onBranchMessage = { message ->
        scope.launch {
            activeSessionId?.let { sessionId ->
                val newSessionId = chatController.branchSession(sessionId, message, activeCharacter?.name)
                onSetActiveSession(newSessionId)
                chatController.refresh(newSessionId)
            }
        }
    },
    onGoToParentChat = currentSessionDetails?.parentSessionId?.let { parentId ->
        { onSetActiveSession(parentId); chatController.refresh(parentId) }
    },
    onAddImageToMessage = { targetMessageId, pickedBytesList ->
        activeSessionId?.let { chatController.addImageToMessage(it, targetMessageId, pickedBytesList) }
    },
    onNavigateToSettings = { if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Settings) },
    onNavigateToPersonas = { onRequestPersonaEdit(); if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) },
    onNavigateToCharacters = { onRequestCharacterMenu(); if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) }
)

// The suspend half of the send flow: assistant auto-creation, session
// resolution, user message commit, auto-title and the AI request. The
// synchronous send guards (persona/API checks, double-tap protection) stay at
// the call site because they must run before any suspend point.
internal suspend fun commitUserMessage(
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    chatController: ChatController,
    initialActiveCharacter: Character?,
    activePersonaId: String,
    activePersona: Persona?,
    userMessage: String,
    imageList: List<ByteArray>,
    onSetActiveCharacter: (Character) -> Unit,
    onSetActiveSession: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var currentActiveCharacter = initialActiveCharacter
    if (currentActiveCharacter == null) {
        var assistant = characterRepository.getAssistant()
        if (assistant == null) {
            characterRepository.createAssistant()
            assistant = characterRepository.getAssistant()
        }
        if (assistant != null) {
            onSetActiveCharacter(assistant)
            currentActiveCharacter = assistant
        }
    }

    if (currentActiveCharacter == null) {
        chatController.reportError("Could not create the Assistant character.")
        return
    }

    // Guard again after the DB round-trips above: a fast double send can still
    // pass the UI-level isGenerating check, and inserting a second user
    // message would orphan it without a response.
    if (chatController.state.value.isGenerating) return

    val sessionId = sessionRepository.getOrCreateSession(currentActiveCharacter.id, activePersonaId)
    onSetActiveSession(sessionId)

    sessionRepository.ensureInitialGreetings(sessionId, currentActiveCharacter)

    val updatedSession = sessionRepository.getSessionById(sessionId)
    sessionRepository.insertMessage(sessionId, "user", userMessage, updatedSession?.currentMessageId, imageList)
    onRefresh()

    val updatedSession2 = sessionRepository.getSessionById(sessionId)
    if (updatedSession2?.title.isNullOrBlank()) {
        val autoTitle = userMessage
            .lineSequence()
            .first()
            .trim()
            .take(50)
            .ifBlank { null }
        if (autoTitle != null) {
            sessionRepository.updateSessionTitle(sessionId, autoTitle)
        }
    }
    chatController.requestAiResponse(sessionId, currentActiveCharacter, activePersona, updatedSession2?.currentMessageId)
}

// Builds the character-export flow handler used by the character lists. PNG
// re-encoding can be heavy, so it runs off the main thread; the actual save
// (file dialog) must stay on the main thread.
internal fun buildExportHandler(
    isDesktop: Boolean,
    onCloseDrawer: () -> Unit,
    scope: CoroutineScope,
    onExported: (parentDir: String) -> Unit,
    onExportFailed: (message: String) -> Unit
): (Character) -> Unit = { targetChar ->
    if (!isDesktop) onCloseDrawer()
    scope.launch {
        try {
            val (fileName, bytes) = withContext(Dispatchers.Default) {
                CharacterManager.prepareExportBytes(targetChar)
            }
            val parentDir = CharacterManager.saveExportedFile(fileName, bytes)
            if (parentDir != null) {
                onExported(parentDir)
            } else {
                onExportFailed("Failed to export: Could not save file")
            }
        } catch (e: Exception) {
            onExportFailed("Failed to export: ${e.message}")
        }
    }
}

// Mass export: every selected character is prepared (PNG embed or JSON
// fallback) and packed into a single stored ZIP archive, then saved through
// the same save-file path as a single export.
internal fun buildBatchExportHandler(
    isDesktop: Boolean,
    onCloseDrawer: () -> Unit,
    scope: CoroutineScope,
    onExported: (count: Int, parentDir: String) -> Unit,
    onExportFailed: (message: String) -> Unit
): (List<Character>) -> Unit = { characters ->
    if (!isDesktop) onCloseDrawer()
    scope.launch {
        try {
            val count = characters.size
            val bytes = withContext(Dispatchers.Default) {
                CharacterManager.prepareBatchExportBytes(characters)
            }
            val parentDir = CharacterManager.saveExportedFile(
                CharacterManager.batchExportFileName(),
                bytes
            )
            if (parentDir != null) {
                onExported(count, parentDir)
            } else {
                onExportFailed("Failed to export: Could not save file")
            }
        } catch (e: Exception) {
            onExportFailed("Failed to export: ${e.message}")
        }
    }
}

package chat.donzi.localtavern.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.ui.layout.ActiveDrawer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Holds every piece of the chat screen's UI state plus the logic that
// mutates it: message selection, the send flow (with its double-tap guards
// and assistant auto-creation) and the ChatActions bag consumed by the chat
// components. The active character/session live in the coordinating
// MainScreenState and are read/written through the provided lambdas, so this
// holder is unit-testable without Compose.
@Stable
class ChatScreenState(
    private val characterRepository: CharacterRepository,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val chatController: ChatController,
    private val scope: CoroutineScope,
    private val activeSessionIdProvider: () -> String?,
    private val activeCharacterProvider: () -> Character?,
    private val onActiveSessionIdChange: (String?) -> Unit,
    private val onActiveCharacterChange: (Character) -> Unit
) {
    var isSelectMode by mutableStateOf(false)
    var selectedMessageIds by mutableStateOf<Set<String>>(emptySet())

    var sendInFlight by mutableStateOf(false)

    var hasApiProfile by mutableStateOf(false)

    fun enterSelectMode() {
        isSelectMode = true
        selectedMessageIds = emptySet()
    }

    fun exitSelectMode() {
        isSelectMode = false
        selectedMessageIds = emptySet()
    }

    fun refreshApiProfile() {
        scope.launch {
            hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
        }
    }

    fun refreshMessages() {
        refreshApiProfile()
        chatController.refresh(activeSessionIdProvider())
    }

    // Resolves the session shown for the active character/persona: reuses the
    // current one when it still matches, otherwise creates a fresh session,
    // seeds its greeting roots and points the chat controller at it. Clearing
    // the view (no character/persona) resets the selection state too.
    suspend fun syncActiveSession(character: Character?, personaId: String?) {
        isSelectMode = false
        selectedMessageIds = emptySet()
        hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
        if (personaId != null && character != null) {
            var sessionId = activeSessionIdProvider()
            val currentSession = sessionId?.let { sessionRepository.getSessionById(it) }

            if (currentSession == null || currentSession.characterId != character.id || currentSession.personaId != personaId) {
                sessionId = sessionRepository.getOrCreateSession(character.id, personaId)
                onActiveSessionIdChange(sessionId)
            }

            messageRepository.ensureInitialGreetings(sessionId, character)
            chatController.refresh(sessionId)
        } else {
            onActiveSessionIdChange(null)
            chatController.refresh(null)
        }
    }

    // Returns true when the send is accepted (the user message is committed);
    // false keeps the draft in the input bar so typed text is never lost.
    fun trySendMessage(
        userMessage: String,
        imageList: List<ByteArray>,
        activePersonaId: String?,
        activePersona: Persona?,
        activeApiConnection: ApiConfig?,
        isGenerating: Boolean
    ): Boolean {
        if (activePersonaId == null) {
            chatController.reportError("Create a persona before sending a message.")
            return false
        } else if (activeApiConnection == null) {
            chatController.reportError("No active API connection configured. Add one in Settings before sending.")
            return false
        } else if (!isGenerating && !sendInFlight) {
            // Set the flag synchronously, before any suspend point: the DB
            // round-trips below let a second tap slip through the flow-based
            // isGenerating check (it is only set once requestAiResponse runs),
            // which would insert a duplicate user message.
            sendInFlight = true
            scope.launch {
                try {
                    commitUserMessage(
                        initialActiveCharacter = activeCharacterProvider(),
                        activePersonaId = activePersonaId,
                        activePersona = activePersona,
                        userMessage = userMessage,
                        imageList = imageList
                    )
                } finally {
                    sendInFlight = false
                }
            }
            return true
        }
        return false
    }

    // Wires every chat action from the controller/state down to the
    // ChatActions bag consumed by ChatArea. Kept outside the composables so
    // they stay orchestration-only.
    fun buildChatActions(
        activePersona: Persona?,
        messages: List<Message>,
        siblingsMap: Map<String, List<Message>>,
        isGenerating: Boolean,
        currentSessionDetails: Session?,
        scope: CoroutineScope,
        isDesktop: Boolean,
        onSendMessage: (String, List<ByteArray>) -> Boolean,
        onManageChats: () -> Unit,
        onRequestPersonaEdit: () -> Unit,
        onRequestCharacterMenu: () -> Unit,
        onActiveDrawerChange: (ActiveDrawer) -> Unit
    ): ChatActions {
        val activeSessionId = activeSessionIdProvider()
        val activeCharacter = activeCharacterProvider()
        return ChatActions(
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
                        onActiveSessionIdChange(newSessionId)
                        chatController.refresh(newSessionId)
                    }
                }
            },
            onGoToParentChat = currentSessionDetails?.parentSessionId?.let { parentId ->
                { onActiveSessionIdChange(parentId); chatController.refresh(parentId) }
            },
            onAddImageToMessage = { targetMessageId, pickedBytesList ->
                activeSessionId?.let { chatController.addImageToMessage(it, targetMessageId, pickedBytesList) }
            },
            onNavigateToSettings = { if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Settings) },
            onNavigateToPersonas = { onRequestPersonaEdit(); if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) },
            onNavigateToCharacters = { onRequestCharacterMenu(); if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) }
        )
    }

    // The suspend half of the send flow: assistant auto-creation, session
    // resolution, user message commit, auto-title and the AI request. The
    // synchronous send guards (persona/API checks, double-tap protection) stay
    // at the call site because they must run before any suspend point.
    private suspend fun commitUserMessage(
        initialActiveCharacter: Character?,
        activePersonaId: String,
        activePersona: Persona?,
        userMessage: String,
        imageList: List<ByteArray>
    ) {
        var currentActiveCharacter = initialActiveCharacter
        if (currentActiveCharacter == null) {
            var assistant = characterRepository.getAssistant()
            if (assistant == null) {
                characterRepository.createAssistant()
                assistant = characterRepository.getAssistant()
            }
            if (assistant != null) {
                onActiveCharacterChange(assistant)
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
        onActiveSessionIdChange(sessionId)

        messageRepository.ensureInitialGreetings(sessionId, currentActiveCharacter)

        val updatedSession = sessionRepository.getSessionById(sessionId)
        messageRepository.insertMessage(sessionId, "user", userMessage, updatedSession?.currentMessageId, imageList)
        refreshMessages()

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
}

package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val siblingsMap: Map<String, List<Message>> = emptyMap(),
    val currentSession: Session? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
    val errorIsWarning: Boolean = false,
    // Live estimated cost of the in-flight generation (null when unknown).
    val liveCostEstimate: chat.donzi.localtavern.data.pricing.CostEstimate? = null
)

class ChatController(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val chatClient: ChatClient,
    private val scope: CoroutineScope,
    private val payloadDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val generationRunner = GenerationRunner(
        messageRepository = messageRepository,
        apiSettingsRepository = apiSettingsRepository,
        chatClient = chatClient,
        scope = scope,
        payloadDispatcher = payloadDispatcher
    )

    private var responseJob: Job? = null

    // The session the UI is currently showing. Background generation for another
    // session must not overwrite this view when it completes.
    private var viewedSessionId: String? = null

    // Cancels an in-flight generation and waits for its cleanup to finish.
    // Destructive actions (delete, edit, swipe, regenerate) must never run
    // while the stream is still writing into the message rows: the partial
    // response would be silently lost or clobber the user's action.
    private suspend fun cancelGenerationIfActive() {
        if (_state.value.isGenerating) {
            responseJob?.cancel()
            responseJob?.join()
        }
    }

    // Surfaces a transient user-facing message through the same error bubble
    // used by generation failures (e.g. a send that had to be dropped).
    fun reportError(message: String) {
        _state.update { it.copy(errorMessage = message, errorIsWarning = true) }
    }

    fun refresh(sessionId: String?) {
        viewedSessionId = sessionId
        scope.launch { loadSessionState(sessionId) }
    }

    private fun refreshIfViewed(sessionId: String) {
        if (viewedSessionId == sessionId) refresh(sessionId)
    }

    private suspend fun loadSessionState(sessionId: String?) {
        if (sessionId == null) {
            // A stale load for a previous session must not re-apply its state
            // after the view has moved to null (or elsewhere).
            if (viewedSessionId == null) {
                _state.update { it.copy(messages = emptyList(), siblingsMap = emptyMap(), currentSession = null) }
            }
            return
        }
        val sessionDetails = sessionRepository.getSessionById(sessionId)
        val activeTimeline = messageRepository.getMessagesForSession(sessionId)
        val allMessages = messageRepository.getAllMessagesForSession(sessionId)

        val siblingsByParent = allMessages.groupBy { it.parentId }
        val updatedSiblings = mutableMapOf<String, List<Message>>()
        allMessages.forEach { msg ->
            val siblingsList = siblingsByParent[msg.parentId].orEmpty()
            if (siblingsList.isNotEmpty()) {
                siblingsList.forEach { updatedSiblings[it.id] = siblingsList }
            }
        }

        // The user may have switched to another session (or closed the chat)
        // while this load was in flight; applying a stale snapshot would show
        // the wrong session's messages.
        if (viewedSessionId != sessionId) return

        _state.update { it.copy(messages = activeTimeline, siblingsMap = updatedSiblings, currentSession = sessionDetails) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null, errorIsWarning = false) }
    }

    fun requestAiResponse(sessionId: String, character: Character?, persona: Persona?, targetParentId: String? = null) {
        // A new request supersedes any in-flight one: cancel the previous job
        // (its cleanup preserves or removes its placeholder) and start fresh,
        // so e.g. "regenerate" during streaming restarts cleanly instead of
        // deleting the placeholder and silently no-oping. A stale error from
        // a previous run is cleared so it cannot coexist with a live attempt.
        responseJob?.cancel()
        _state.update { it.copy(isGenerating = true, errorMessage = null, errorIsWarning = false, liveCostEstimate = null) }
        responseJob = scope.launch {
            try {
                generationRunner.run(
                    sessionId = sessionId,
                    character = character,
                    persona = persona,
                    targetParentId = targetParentId,
                    state = _state,
                    isCurrentView = { viewedSessionId == sessionId },
                    onRefresh = { refreshIfViewed(it) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Failure before the placeholder was inserted (e.g. a DB error
                // reading the active connection): surface it instead of letting
                // the exception vanish into the app scope's SupervisorJob.
                _state.update { it.copy(errorMessage = e.message ?: "Unknown error occurred", errorIsWarning = false) }
            } finally {
                // Only the job that is still the current one may reset the
                // generating flag; a superseded job that finishes its cleanup
                // after a newer generation started must not touch its state.
                if (responseJob === coroutineContext[Job]) {
                    _state.update { it.copy(isGenerating = false, liveCostEstimate = null) }
                    responseJob = null
                }
            }
        }
    }

    fun stopGeneration() {
        responseJob?.cancel()
    }

    fun regenerate(sessionId: String, character: Character?, persona: Persona?) {
        scope.launch {
            // Regenerating while a response is streaming would delete the
            // in-flight placeholder under the live stream; stop it first.
            cancelGenerationIfActive()
            // Read from the DB, not the possibly-stale UI snapshot: the
            // cancelled job may have just removed or updated the last message.
            val lastMsg = messageRepository.getMessagesForSession(sessionId).lastOrNull() ?: return@launch
            val parentId = if (lastMsg.role == "assistant") {
                messageRepository.deleteMessage(lastMsg.id)
                lastMsg.parentId
            } else {
                lastMsg.id
            }
            if (parentId != null) {
                requestAiResponse(sessionId, character, persona, parentId)
            } else {
                refresh(sessionId)
            }
        }
    }

    fun selectVariation(sessionId: String, messageId: String, parentId: String?) {
        scope.launch {
            cancelGenerationIfActive()
            messageRepository.selectVariation(sessionId, messageId, parentId)
            refresh(sessionId)
        }
    }

    fun deleteMessage(sessionId: String, id: String) {
        scope.launch {
            // Deleting while streaming would cascade into the in-flight
            // placeholder (or the stream into a deactivated row): stop first.
            cancelGenerationIfActive()
            val activeTimeline = messageRepository.getMessagesForSession(sessionId)
            if (activeTimeline.any { it.id == id }) {
                val msg = activeTimeline.find { it.id == id }
                val parentId = msg?.parentId
                val siblings = messageRepository.getMessageSiblings(sessionId, parentId)
                val otherSibling = siblings.firstOrNull { it.id != id }
                if (otherSibling != null) {
                    messageRepository.selectVariation(sessionId, otherSibling.id, parentId)
                } else if (parentId != null) {
                    sessionRepository.updateSessionCurrentMessage(sessionId, parentId)
                }
            }
            messageRepository.deleteMessage(id)
            refresh(sessionId)
        }
    }

    fun deleteMessages(sessionId: String, ids: List<String>) {
        scope.launch {
            cancelGenerationIfActive()
            val activeTimeline = messageRepository.getMessagesForSession(sessionId)
            val activeDeleted = activeTimeline.find { it.id in ids }
            if (activeDeleted != null) {
                val parentId = activeDeleted.parentId
                if (parentId != null) {
                    val parentMsg = messageRepository.getMessagesForSession(sessionId).find { it.id == parentId }
                    messageRepository.selectVariation(sessionId, parentId, parentMsg?.parentId)
                }
            }
            ids.forEach { messageRepository.deleteMessage(it) }
            refresh(sessionId)
        }
    }

    fun deleteMessagesRaw(sessionId: String, ids: List<String>) {
        scope.launch {
            cancelGenerationIfActive()
            ids.forEach { messageRepository.deleteMessage(it) }
            // The bulk delete has no per-message sibling/parent fix-up; repoint
            // a dangling currentMessageId at the last surviving message (or
            // clear it) so the next user message is not parented to a deleted
            // node.
            val session = sessionRepository.getSessionById(sessionId)
            val currentId = session?.currentMessageId
            if (currentId != null && currentId in ids) {
                val remaining = messageRepository.getMessagesForSession(sessionId)
                sessionRepository.updateSessionCurrentMessage(sessionId, remaining.lastOrNull()?.id)
            }
            refresh(sessionId)
        }
    }

    fun editMessage(sessionId: String, id: String, content: String, updatedImages: List<ByteArray>) {
        scope.launch {
            // Editing the in-flight message would be overwritten by the next
            // stream tick; stop the generation so the edit sticks.
            cancelGenerationIfActive()
            messageRepository.updateMessageContent(id, content)
            messageRepository.updateMessageImage(id, updatedImages)
            refresh(sessionId)
        }
    }

    fun addImageToMessage(sessionId: String, targetMessageId: String, pickedBytesList: List<ByteArray>) {
        scope.launch {
            // Read-modify-write runs inside one DB transaction (the UI
            // snapshot could be stale and would lose a concurrent add).
            messageRepository.appendImagesToMessage(sessionId, targetMessageId, pickedBytesList)
            refresh(sessionId)
        }
    }

    suspend fun branchSession(sessionId: String, message: Message, characterName: String?): String {
        val currentSession = sessionRepository.getSessionById(sessionId)
        val baseTitle = currentSession?.title ?: "${characterName ?: "Chat"} #$sessionId"
        return sessionRepository.branchSession(sessionId, message.id, _state.value.messages, "Branch of $baseTitle")
    }
}

package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.network.GenerationParams
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.ChatMessage
import chat.donzi.localtavern.utils.ContextManager
import chat.donzi.localtavern.utils.ImageAttachment
import chat.donzi.localtavern.utils.detectMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val siblingsMap: Map<String, List<Message>> = emptyMap(),
    val currentSession: Session? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
    val errorIsWarning: Boolean = false
)

class ChatController(
    private val sessionRepository: SessionRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val chatClient: ChatClient,
    private val scope: CoroutineScope,
    private val payloadDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var responseJob: Job? = null

    // The session the UI is currently showing. Background generation for another
    // session must not overwrite this view when it completes.
    private var viewedSessionId: String? = null

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
        val activeTimeline = sessionRepository.getMessagesForSession(sessionId)
        val allMessages = sessionRepository.getAllMessagesForSession(sessionId)

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
        if (_state.value.isGenerating) return
        responseJob?.cancel()
        _state.update { it.copy(isGenerating = true) }
        responseJob = scope.launch {
            try {
                val activeConnection = apiSettingsRepository.getActiveApiConnection()
                if (activeConnection != null) {
                    val aiMessageId = sessionRepository.insertMessage(sessionId, "assistant", "...", targetParentId)
                    refreshIfViewed(sessionId)

                    val responseBuilder = StringBuilder()
                    var lastStateTime = 0L
                    var lastPersistTime = 0L

                    // The placeholder is inserted before the payload is built
                    // and the stream is opened. Any failure in that phase must
                    // remove the placeholder, or a phantom "..." message would
                    // remain in the session with no error surfaced.
                    try {
                        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                        val messagesPayload = withContext(payloadDispatcher) {
                            val dbMessages = sessionRepository.getMessagesForSession(sessionId)

                            val chatHistory = dbMessages
                                .filter { it.id != aiMessageId }
                                .map { msg ->
                                    val attachmentsList = msg.images.map { imgBytes ->
                                        ImageAttachment(
                                            base64 = kotlin.io.encoding.Base64.encode(imgBytes),
                                            mimeType = detectMimeType(imgBytes)
                                        )
                                    }
                                    ChatMessage(
                                        role = msg.role,
                                        content = msg.content,
                                        images = attachmentsList
                                    )
                                }

                            val blocks = apiSettingsRepository.getAllPromptBlocks()
                            ContextManager.buildPayload(
                                blocks = blocks, character = character, persona = persona, chatHistory = chatHistory,
                                contextLimit = activeConnection.contextLimit, responseLimit = activeConnection.responseLimit
                            )
                        }

                        val timeoutLimitSeconds = activeConnection.timeoutLimit
                        val tokenChannel = Channel<String>(Channel.UNLIMITED)

                        val generationParams = GenerationParams(
                            temperature = activeConnection.temperature,
                            topP = activeConnection.topP,
                            topK = activeConnection.topK,
                            presencePenalty = activeConnection.presencePenalty,
                            frequencyPenalty = activeConnection.frequencyPenalty,
                            maxTokens = activeConnection.responseLimit
                        )

                        val streamJob = scope.launch {
                            try {
                                chatClient.streamChatRequest(
                                    baseUrl = activeConnection.baseUrl ?: "", apiKey = activeConnection.apiKey ?: "",
                                    model = activeConnection.model ?: "", messages = messagesPayload,
                                    isChatCompletion = activeConnection.isChatCompletion,
                                    params = generationParams, provider = activeConnection.provider,
                                    timeoutSeconds = activeConnection.timeoutLimit
                                ).collect { token -> tokenChannel.send(token) }
                            } catch (e: Exception) {
                                tokenChannel.close(e)
                                return@launch
                            }
                            tokenChannel.close()
                        }

                        val responseDeadline = if (timeoutLimitSeconds == 0L) {
                            null
                        } else {
                            Clock.System.now().plus(timeoutLimitSeconds.seconds)
                        }

                        try {
                            while (true) {
                                val channelResult = when {
                                    responseDeadline == null -> tokenChannel.receiveCatching()
                                    else -> {
                                        val remaining = responseDeadline - Clock.System.now()
                                        if (remaining <= Duration.ZERO) {
                                            withTimeout(Duration.ZERO) { tokenChannel.receiveCatching() }
                                        } else {
                                            withTimeout(remaining) { tokenChannel.receiveCatching() }
                                        }
                                    }
                                }

                                if (channelResult.isClosed) {
                                    val cause = channelResult.exceptionOrNull()
                                    if (cause != null) {
                                        throw cause
                                    }
                                    break
                                }

                                val token = channelResult.getOrNull() ?: break

                                responseBuilder.append(token)

                                val now = Clock.System.now().toEpochMilliseconds()
                                // Throttle UI and DB updates so fast token streams do
                                // not rebuild the message list (and re-encode the full
                                // response string) on every single token.
                                if (now - lastStateTime >= 50) {
                                    lastStateTime = now
                                    val snapshot = responseBuilder.toString()
                                    _state.update { st ->
                                        if (viewedSessionId != sessionId) {
                                            st
                                        } else {
                                            st.copy(messages = st.messages.map { msg -> if (msg.id == aiMessageId) msg.copy(content = snapshot) else msg })
                                        }
                                    }
                                }
                                if (now - lastPersistTime >= 250) {
                                    lastPersistTime = now
                                    sessionRepository.updateMessageContent(aiMessageId, responseBuilder.toString())
                                }
                            }
                            val fullResponse = responseBuilder.toString()
                            if (fullResponse.isBlank()) {
                                sessionRepository.deleteMessage(aiMessageId)
                            } else {
                                sessionRepository.updateMessageContent(aiMessageId, fullResponse)
                            }
                            refreshIfViewed(sessionId)
                        } catch (_: TimeoutCancellationException) {
                            streamJob.cancel()
                            val partialResponse = responseBuilder.toString()
                            if (partialResponse.isBlank()) {
                                sessionRepository.deleteMessage(aiMessageId)
                            } else {
                                sessionRepository.updateMessageContent(aiMessageId, partialResponse)
                            }
                            _state.update { it.copy(errorMessage = "Response timeout exceeded.", errorIsWarning = true) }
                            refreshIfViewed(sessionId)
                        } catch (_: CancellationException) {
                            streamJob.cancel()
                            withContext(NonCancellable) {
                                val partialResponse = responseBuilder.toString()
                                if (partialResponse.isBlank()) {
                                    sessionRepository.deleteMessage(aiMessageId)
                                } else {
                                    sessionRepository.updateMessageContent(aiMessageId, partialResponse)
                                }
                            }
                            refreshIfViewed(sessionId)
                        } catch (e: Exception) {
                            streamJob.cancel()
                            val partialResponse = responseBuilder.toString()
                            if (partialResponse.isBlank()) {
                                sessionRepository.deleteMessage(aiMessageId)
                            } else {
                                sessionRepository.updateMessageContent(aiMessageId, partialResponse)
                            }
                            _state.update { it.copy(errorMessage = e.message ?: "Unknown error occurred", errorIsWarning = false) }
                            refreshIfViewed(sessionId)
                        }
                    } catch (e: CancellationException) {
                        // Generation was stopped before any token was handled;
                        // drop the placeholder so it cannot linger in the session.
                        withContext(NonCancellable) {
                            sessionRepository.deleteMessage(aiMessageId)
                        }
                        refreshIfViewed(sessionId)
                        throw e
                    } catch (e: Exception) {
                        // Payload build or stream setup failed before any token
                        // was handled; remove the placeholder and surface the error.
                        withContext(NonCancellable) {
                            sessionRepository.deleteMessage(aiMessageId)
                        }
                        _state.update { it.copy(errorMessage = e.message ?: "Unknown error occurred", errorIsWarning = false) }
                        refreshIfViewed(sessionId)
                    }
                } else {
                    _state.update { it.copy(errorMessage = "No active API connection configured.", errorIsWarning = false) }
                    refreshIfViewed(sessionId)
                }
            } finally {
                _state.update { it.copy(isGenerating = false) }
                responseJob = null
            }
        }
    }

    fun stopGeneration() {
        responseJob?.cancel()
    }

    fun regenerate(sessionId: String, character: Character?, persona: Persona?) {
        val currentMessages = _state.value.messages
        if (currentMessages.isEmpty()) return
        scope.launch {
            val lastMsg = currentMessages.last()
            val parentId = if (lastMsg.role == "assistant") {
                sessionRepository.deleteMessage(lastMsg.id)
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
            sessionRepository.selectVariation(sessionId, messageId, parentId)
            refresh(sessionId)
        }
    }

    fun deleteMessage(sessionId: String, id: String) {
        scope.launch {
            val activeTimeline = sessionRepository.getMessagesForSession(sessionId)
            if (activeTimeline.any { it.id == id }) {
                val msg = activeTimeline.find { it.id == id }
                val parentId = msg?.parentId
                val siblings = sessionRepository.getMessageSiblings(sessionId, parentId)
                val otherSibling = siblings.firstOrNull { it.id != id }
                if (otherSibling != null) {
                    sessionRepository.selectVariation(sessionId, otherSibling.id, parentId)
                } else if (parentId != null) {
                    sessionRepository.updateSessionCurrentMessage(sessionId, parentId)
                }
            }
            sessionRepository.deleteMessage(id)
            refresh(sessionId)
        }
    }

    fun deleteMessages(sessionId: String, ids: List<String>) {
        scope.launch {
            val activeTimeline = sessionRepository.getMessagesForSession(sessionId)
            val activeDeleted = activeTimeline.find { it.id in ids }
            if (activeDeleted != null) {
                val parentId = activeDeleted.parentId
                if (parentId != null) {
                    val parentMsg = sessionRepository.getMessagesForSession(sessionId).find { it.id == parentId }
                    sessionRepository.selectVariation(sessionId, parentId, parentMsg?.parentId)
                }
            }
            ids.forEach { sessionRepository.deleteMessage(it) }
            refresh(sessionId)
        }
    }

    fun deleteMessagesRaw(sessionId: String, ids: List<String>) {
        scope.launch {
            ids.forEach { sessionRepository.deleteMessage(it) }
            // The bulk delete has no per-message sibling/parent fix-up; repoint
            // a dangling currentMessageId at the last surviving message (or
            // clear it) so the next user message is not parented to a deleted
            // node.
            val session = sessionRepository.getSessionById(sessionId)
            val currentId = session?.currentMessageId
            if (currentId != null && currentId in ids) {
                val remaining = sessionRepository.getMessagesForSession(sessionId)
                sessionRepository.updateSessionCurrentMessage(sessionId, remaining.lastOrNull()?.id)
            }
            refresh(sessionId)
        }
    }

    fun editMessage(sessionId: String, id: String, content: String, updatedImages: List<ByteArray>) {
        scope.launch {
            sessionRepository.updateMessageContent(id, content)
            sessionRepository.updateMessageImage(id, updatedImages)
            refresh(sessionId)
        }
    }

    fun addImageToMessage(sessionId: String, targetMessageId: String, pickedBytesList: List<ByteArray>) {
        scope.launch {
            val targetMsg = _state.value.messages.find { it.id == targetMessageId }
            val combinedImages = (targetMsg?.images ?: emptyList()) + pickedBytesList
            sessionRepository.updateMessageImage(targetMessageId, combinedImages)
            refresh(sessionId)
        }
    }

    suspend fun branchSession(sessionId: String, message: Message, characterName: String?): String {
        val currentSession = sessionRepository.getSessionById(sessionId)
        val baseTitle = currentSession?.title ?: "${characterName ?: "Chat"} #$sessionId"
        return sessionRepository.branchSession(sessionId, message.id, _state.value.messages, "Branch of $baseTitle")
    }
}

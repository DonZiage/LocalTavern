package chat.donzi.localtavern.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Holds every piece of MainScreen's local UI state plus the logic that
// mutates it, so the composable stays a thin layout orchestrator. One
// instance is remember-ed in MainScreen; the mutableStateOf-backed
// properties here replace the previous remember { mutableStateOf(...) }
// locals and keep the same recomposition granularity.
@Stable
class MainScreenState(
    private val characterRepository: CharacterRepository,
    private val sessionRepository: SessionRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val chatController: ChatController,
    private val scope: CoroutineScope
) {
    var activeCharacter by mutableStateOf<Character?>(null)
    var activeSessionId by mutableStateOf<String?>(null)

    var isSelectMode by mutableStateOf(false)
    var selectedMessageIds by mutableStateOf<Set<String>>(emptySet())

    var editingCharacter by mutableStateOf<Character?>(null)
    var lastEditingCharacter by mutableStateOf<Character?>(null)

    var hasApiProfile by mutableStateOf(false)
    var sendInFlight by mutableStateOf(false)

    var autoEditPersonaTrigger by mutableStateOf(false)
    var autoShowCharacterMenuTrigger by mutableStateOf(false)
    var showChatManagerDialog by mutableStateOf(false)

    var showExportNotification by mutableStateOf(false)
    var exportedDir by mutableStateOf("")

    // Name of a character created through the panel while the list reloads;
    // consumed by the characters LaunchedEffect in MainScreen to open the
    // freshly-created character's editor.
    var pendingCreationName by mutableStateOf<String?>(null)

    // Sets the editor target synchronously: a LaunchedEffect would only run
    // after the first frame, briefly showing the previous character's editor
    // (with its callbacks) and playing the enter animation empty on first use.
    fun openEditor(character: Character?) {
        editingCharacter = character
        if (character != null) lastEditingCharacter = character
    }

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
        chatController.refresh(activeSessionId)
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
            var sessionId = activeSessionId
            val currentSession = sessionId?.let { sessionRepository.getSessionById(it) }

            if (currentSession == null || currentSession.characterId != character.id || currentSession.personaId != personaId) {
                sessionId = sessionRepository.getOrCreateSession(character.id, personaId)
                activeSessionId = sessionId
            }

            sessionRepository.ensureInitialGreetings(sessionId, character)
            chatController.refresh(sessionId)
        } else {
            activeSessionId = null
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
                        characterRepository = characterRepository,
                        sessionRepository = sessionRepository,
                        chatController = chatController,
                        initialActiveCharacter = activeCharacter,
                        activePersonaId = activePersonaId,
                        activePersona = activePersona,
                        userMessage = userMessage,
                        imageList = imageList,
                        onSetActiveCharacter = { activeCharacter = it },
                        onSetActiveSession = { activeSessionId = it },
                        onRefresh = { refreshMessages() }
                    )
                } finally {
                    sendInFlight = false
                }
            }
            return true
        }
        return false
    }
}

package chat.donzi.localtavern.ui.layout

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
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.ui.characters.CharactersPanelState
import chat.donzi.localtavern.ui.chat.ChatScreenState
import kotlinx.coroutines.CoroutineScope

// Coordinator for the MainScreen layout: owns the cross-feature selection
// (active character/session), the navigation triggers shared by chat and
// character panels, and composes the per-feature state holders (chat and
// characters), whose own fields are exposed here as delegated accessors so
// the layout code keeps one entry point. One instance is remember-ed in
// MainScreen; the mutableStateOf-backed properties replace the previous
// remember { mutableStateOf(...) } locals with the same recomposition
// granularity.
@Stable
class MainScreenState(
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    messageRepository: MessageRepository,
    apiSettingsRepository: ApiSettingsRepository,
    chatController: ChatController,
    scope: CoroutineScope
) {
    var activeCharacter by mutableStateOf<Character?>(null)
    var activeSessionId by mutableStateOf<String?>(null)

    val chatState: ChatScreenState = ChatScreenState(
        characterRepository = characterRepository,
        sessionRepository = sessionRepository,
        messageRepository = messageRepository,
        apiSettingsRepository = apiSettingsRepository,
        chatController = chatController,
        scope = scope,
        activeSessionIdProvider = { activeSessionId },
        activeCharacterProvider = { activeCharacter },
        onActiveSessionIdChange = { activeSessionId = it },
        onActiveCharacterChange = { activeCharacter = it }
    )

    val charactersState: CharactersPanelState = CharactersPanelState(scope)

    var isSelectMode: Boolean
        get() = chatState.isSelectMode
        set(value) { chatState.isSelectMode = value }

    var selectedMessageIds: Set<String>
        get() = chatState.selectedMessageIds
        set(value) { chatState.selectedMessageIds = value }

    var sendInFlight: Boolean
        get() = chatState.sendInFlight
        set(value) { chatState.sendInFlight = value }

    var hasApiProfile: Boolean
        get() = chatState.hasApiProfile
        set(value) { chatState.hasApiProfile = value }

    var editingCharacter: Character?
        get() = charactersState.editingCharacter
        set(value) { charactersState.editingCharacter = value }

    var lastEditingCharacter: Character?
        get() = charactersState.lastEditingCharacter
        set(value) { charactersState.lastEditingCharacter = value }

    var showExportNotification: Boolean
        get() = charactersState.showExportNotification
        set(value) { charactersState.showExportNotification = value }

    var exportedDir: String
        get() = charactersState.exportedDir
        set(value) { charactersState.exportedDir = value }

    var exportedCount: Int
        get() = charactersState.exportedCount
        set(value) { charactersState.exportedCount = value }

    var pendingCreationName: String?
        get() = charactersState.pendingCreationName
        set(value) { charactersState.pendingCreationName = value }

    // Navigation triggers shared between the chat and character panels.
    var showChatManagerDialog by mutableStateOf(false)
    var autoEditPersonaTrigger by mutableStateOf(false)
    var autoShowCharacterMenuTrigger by mutableStateOf(false)

    // Sets the editor target synchronously (see CharactersPanelState).
    fun openEditor(character: Character?) = charactersState.openEditor(character)

    fun enterSelectMode() = chatState.enterSelectMode()

    fun exitSelectMode() = chatState.exitSelectMode()

    fun refreshApiProfile() = chatState.refreshApiProfile()

    fun refreshMessages() = chatState.refreshMessages()

    // Resolves the session shown for the active character/persona.
    suspend fun syncActiveSession(character: Character?, personaId: String?) =
        chatState.syncActiveSession(character, personaId)

    // Returns true when the send is accepted (the user message is committed);
    // false keeps the draft in the input bar so typed text is never lost.
    fun trySendMessage(
        userMessage: String,
        imageList: List<ByteArray>,
        activePersonaId: String?,
        activePersona: Persona?,
        activeApiConnection: ApiConfig?,
        isGenerating: Boolean
    ): Boolean = chatState.trySendMessage(
        userMessage = userMessage,
        imageList = imageList,
        activePersonaId = activePersonaId,
        activePersona = activePersona,
        activeApiConnection = activeApiConnection,
        isGenerating = isGenerating
    )

    // Character export flows (single + batch), setting the notification state.
    fun buildExportHandler(
        isDesktop: Boolean,
        onCloseDrawer: () -> Unit,
        onExportFailed: (message: String) -> Unit
    ): (Character) -> Unit = charactersState.buildExportHandler(isDesktop, onCloseDrawer, onExportFailed)

    fun buildBatchExportHandler(
        isDesktop: Boolean,
        onCloseDrawer: () -> Unit,
        onExportFailed: (message: String) -> Unit
    ): (List<Character>) -> Unit = charactersState.buildBatchExportHandler(isDesktop, onCloseDrawer, onExportFailed)
}

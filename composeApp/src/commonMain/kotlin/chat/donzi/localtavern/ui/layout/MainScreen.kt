package chat.donzi.localtavern.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.isDesktop
import chat.donzi.localtavern.utils.BatchImportResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MainScreen(
    deps: MainScreenDependencies,
    characters: List<Character>,
    personas: List<Persona>,
    activePersonaId: String?,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    autoSyncOnLaunch: Boolean,
    onAutoSyncOnLaunchChange: (Boolean) -> Unit,
    sendWithCtrlEnter: Boolean,
    onSendWithCtrlEnterChange: (Boolean) -> Unit,
    confirmBeforeDelete: Boolean,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    autoLockIdleMinutes: Int,
    onAutoLockIdleMinutesChange: (Int) -> Unit,
    activeDrawer: ActiveDrawer,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onCharacterImport: (BatchImportResult) -> Unit,
    onCharacterCreate: (String) -> Unit
) {
    val drawerWidth = 300.dp
    val coroutineScope = rememberCoroutineScope()
    val state = remember {
        MainScreenState(
            characterRepository = deps.characterRepository,
            sessionRepository = deps.sessionRepository,
            messageRepository = deps.messageRepository,
            apiSettingsRepository = deps.apiSettingsRepository,
            chatController = deps.chatController,
            scope = coroutineScope
        )
    }

    val chatState by deps.chatController.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val hasPersona = remember(personas) {
        // The default "User" persona is no longer stored: the cards only ever
        // list user-defined personas, so any entry counts.
        personas.isNotEmpty()
    }
    val hasCharacter = remember(characters) {
        characters.isNotEmpty()
    }

    // The persona actually in use: the active selection when it still exists,
    // otherwise the first available persona, otherwise the implicit blank
    // "User" persona (never stored, so it never shows in the persona cards).
    // Chatting must never be blocked by a missing persona selection.
    val activePersona = remember(personas, activePersonaId) {
        personas.firstOrNull { it.id == activePersonaId }
            ?: personas.firstOrNull()
            ?: Persona.defaultUser()
    }
    val effectivePersonaId = activePersona.id

    // Live (not one-shot) view of whether an active API connection exists, so
    // a send without a profile can be refused BEFORE the user message is
    // committed — otherwise the text becomes an orphaned message with no reply.
    val activeApiConnection by deps.apiSettingsRepository.observeActiveApiConnection()
        .collectAsState(initial = null)

    val exportCharacterFromList = state.buildExportHandler(
        isDesktop = isDesktop,
        onCloseDrawer = { onActiveDrawerChange(ActiveDrawer.None) },
        onExportFailed = { message -> coroutineScope.launch { snackbarHostState.showSnackbar(message) } }
    )

    val exportCharactersFromList = state.buildBatchExportHandler(
        isDesktop = isDesktop,
        onCloseDrawer = { onActiveDrawerChange(ActiveDrawer.None) },
        onExportFailed = { message -> coroutineScope.launch { snackbarHostState.showSnackbar(message) } }
    )

    val onSendMessage: (String, List<ByteArray>) -> Boolean = { userMessage, imageList ->
        state.trySendMessage(
            userMessage = userMessage,
            imageList = imageList,
            activePersonaId = effectivePersonaId,
            activePersona = activePersona,
            activeApiConnection = activeApiConnection,
            isGenerating = chatState.isGenerating
        )
    }

    LaunchedEffect(state.activeSessionId, characters, personas, activeDrawer) {
        state.refreshApiProfile()
    }

    LaunchedEffect(characters) {
        state.pendingCreationName?.let { name ->
            val newChar = characters.findLast { it.name == name }
            if (newChar != null) {
                state.openEditor(newChar)
                state.pendingCreationName = null
            }
        }
    }

    LaunchedEffect(state.showExportNotification) {
        if (state.showExportNotification) {
            delay(5000.milliseconds)
            state.showExportNotification = false
        }
    }

    LaunchedEffect(chatState.errorMessage) {
        if (chatState.errorMessage != null) {
            delay(5000.milliseconds)
            deps.chatController.clearError()
        }
    }

    LaunchedEffect(state.activeCharacter, effectivePersonaId, state.activeSessionId, characters, personas) {
        state.syncActiveSession(state.activeCharacter, effectivePersonaId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isDesktop) {
                DesktopSettingsPanel(
                    deps = deps,
                    state = state,
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = onToggleDarkMode,
                    autoSyncOnLaunch = autoSyncOnLaunch,
                    onAutoSyncOnLaunchChange = onAutoSyncOnLaunchChange,
                    sendWithCtrlEnter = sendWithCtrlEnter,
                    onSendWithCtrlEnterChange = onSendWithCtrlEnterChange,
                    confirmBeforeDelete = confirmBeforeDelete,
                    onConfirmBeforeDeleteChange = onConfirmBeforeDeleteChange,
                    autoLockIdleMinutes = autoLockIdleMinutes,
                    onAutoLockIdleMinutesChange = onAutoLockIdleMinutesChange,
                    drawerWidth = drawerWidth
                )
            }

            ChatScaffold(
                state = state,
                deps = deps,
                chatState = chatState,
                activePersona = activePersona,
                hasApiProfile = state.hasApiProfile,
                hasPersona = hasPersona,
                hasCharacter = hasCharacter,
                onSendMessage = onSendMessage,
                onActiveDrawerChange = onActiveDrawerChange,
                sendWithCtrlEnter = sendWithCtrlEnter,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )

            if (isDesktop) {
                DesktopCharactersPanel(
                    state = state,
                    personas = personas,
                    activePersonaId = effectivePersonaId,
                    characters = characters,
                    drawerWidth = drawerWidth,
                    onPersonaSelect = onPersonaSelect,
                    onPersonaAdd = onPersonaAdd,
                    onPersonaUpdate = onPersonaUpdate,
                    onPersonaDelete = onPersonaDelete,
                    onCharactersDelete = onCharactersDelete,
                    onImportCharacters = onCharacterImport,
                    onExportSelected = { ids -> exportCharactersFromList(characters.filter { it.id in ids }) },
                    onCharacterCreate = onCharacterCreate,
                    exportCharacterFromList = exportCharacterFromList,
                    confirmBeforeDelete = confirmBeforeDelete
                )
            }
        }

        if (!isDesktop) {
            MobilePanels(
                activeDrawer = activeDrawer,
                drawerWidth = drawerWidth,
                deps = deps,
                state = state,
                personas = personas,
                activePersonaId = effectivePersonaId,
                characters = characters,
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode,
                autoSyncOnLaunch = autoSyncOnLaunch,
                onAutoSyncOnLaunchChange = onAutoSyncOnLaunchChange,
                sendWithCtrlEnter = sendWithCtrlEnter,
                onSendWithCtrlEnterChange = onSendWithCtrlEnterChange,
                confirmBeforeDelete = confirmBeforeDelete,
                onConfirmBeforeDeleteChange = onConfirmBeforeDeleteChange,
                autoLockIdleMinutes = autoLockIdleMinutes,
                onAutoLockIdleMinutesChange = onAutoLockIdleMinutesChange,
                onActiveDrawerChange = onActiveDrawerChange,
                onPersonaSelect = onPersonaSelect,
                onPersonaAdd = onPersonaAdd,
                onPersonaUpdate = onPersonaUpdate,
                onPersonaDelete = onPersonaDelete,
                onCharactersDelete = onCharactersDelete,
                onImportCharacters = onCharacterImport,
                onExportSelected = { ids -> exportCharactersFromList(characters.filter { it.id in ids }) },
                onCharacterCreate = onCharacterCreate,
                exportCharacterFromList = exportCharacterFromList
            )
        }

        MainScreenOverlays(
            deps = deps,
            state = state,
            chatState = chatState,
            activePersonaId = effectivePersonaId,
            exportCharacterFromList = exportCharacterFromList,
            onRefreshMessages = { state.refreshMessages() },
            modifier = Modifier.fillMaxSize()
        )
    }
}

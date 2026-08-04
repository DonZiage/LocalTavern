package chat.donzi.localtavern.ui.layout
import chat.donzi.localtavern.ui.settings.SettingsPanelContent
import chat.donzi.localtavern.ui.chat.MessageSelectTopBar
import chat.donzi.localtavern.ui.common.ExportNotificationBubble
import chat.donzi.localtavern.ui.common.ErrorNotificationBubble
import chat.donzi.localtavern.ui.chat.ChatTopBar
import chat.donzi.localtavern.ui.chat.ChatManagerDialog
import chat.donzi.localtavern.ui.chat.ChatArea
import chat.donzi.localtavern.ui.characters.CharactersPanelContent
import chat.donzi.localtavern.ui.characters.CharacterEditorOverlay
import chat.donzi.localtavern.ui.settings.AppSettingsSection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.isDesktop
import chat.donzi.localtavern.utils.BatchImportResult

// Layout pieces of MainScreen, extracted so the orchestrator only wires
// state and data. The desktop panels keep their own collapse/expansion
// remember state here: they stay composed for the whole session on desktop,
// so the state survives recompositions exactly like the original locals.

@Composable
internal fun DesktopSettingsPanel(
    deps: MainScreenDependencies,
    state: MainScreenState,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    autoSyncOnLaunch: Boolean,
    onAutoSyncOnLaunchChange: (Boolean) -> Unit,
    sendWithCtrlEnter: Boolean,
    onSendWithCtrlEnterChange: (Boolean) -> Unit,
    confirmBeforeDelete: Boolean,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    drawerWidth: Dp,
    modifier: Modifier = Modifier
) {
    var settingsApiExpanded by remember { mutableStateOf(false) }
    var settingsSyncExpanded by remember { mutableStateOf(false) }
    var settingsAppExpanded by remember { mutableStateOf(false) }
    var settingsSecurityExpanded by remember { mutableStateOf(false) }
    Box(modifier.width(drawerWidth).fillMaxHeight()) {
        SettingsPanelContent(
            apiSettingsRepository = deps.apiSettingsRepository,
            pricingRepository = deps.pricingRepository,
            apiKeyCipher = deps.apiKeyCipher,
            syncService = deps.syncService,
            syncRepository = deps.syncRepository,
            syncDiscovery = deps.syncDiscovery,
            chatClient = deps.chatClient,
            onEnsureSyncRunning = deps.onEnsureSyncRunning,
            isDarkMode = isDarkMode,
            onToggleDarkMode = onToggleDarkMode,
            onApiChanged = { state.refreshApiProfile() },
            autoSyncOnLaunch = autoSyncOnLaunch,
            onAutoSyncOnLaunchChange = onAutoSyncOnLaunchChange,
            sendWithCtrlEnter = sendWithCtrlEnter,
            onSendWithCtrlEnterChange = onSendWithCtrlEnterChange,
            confirmBeforeDelete = confirmBeforeDelete,
            onConfirmBeforeDeleteChange = onConfirmBeforeDeleteChange,
            apiSectionExpanded = settingsApiExpanded,
            onApiSectionExpandedChange = { settingsApiExpanded = it },
            syncSectionExpanded = settingsSyncExpanded,
            onSyncSectionExpandedChange = { settingsSyncExpanded = it },
            appSettingsSectionExpanded = settingsAppExpanded,
            onAppSettingsSectionExpandedChange = { settingsAppExpanded = it },
            securitySectionExpanded = settingsSecurityExpanded,
            onSecuritySectionExpandedChange = { settingsSecurityExpanded = it }
        )
    }
}

@Composable
internal fun DesktopCharactersPanel(
    state: MainScreenState,
    personas: List<Persona>,
    activePersonaId: String?,
    characters: List<Character>,
    drawerWidth: Dp,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onImportCharacters: (BatchImportResult) -> Unit,
    onExportSelected: (Set<String>) -> Unit,
    onCharacterCreate: (String) -> Unit,
    exportCharacterFromList: (Character) -> Unit,
    confirmBeforeDelete: Boolean = true,
    modifier: Modifier = Modifier
) {
    var personasExpanded by remember { mutableStateOf(true) }
    var charactersExpanded by remember { mutableStateOf(true) }
    Box(modifier.width(drawerWidth).fillMaxHeight()) {
        CharactersPanelContent(
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onPersonaSelect,
            onPersonaAdd = onPersonaAdd,
            onPersonaUpdate = onPersonaUpdate,
            onPersonaDelete = onPersonaDelete,
            characters = characters,
            onCharacterSelect = { character -> state.activeCharacter = character },
            onCharactersDelete = { ids -> if (state.activeCharacter?.id in ids) state.activeCharacter = null; onCharactersDelete(ids) },
            onImportCharacters = onImportCharacters,
            onExportSelected = onExportSelected,
            onCharacterCreate = { name -> state.pendingCreationName = name; onCharacterCreate(name) },
            onCharacterEdit = { character -> state.openEditor(character) },
            onCharacterExport = exportCharacterFromList,
            autoEditDefaultPersona = state.autoEditPersonaTrigger,
            onAutoEditConsumed = { state.autoEditPersonaTrigger = false },
            autoShowNewCharacterMenu = state.autoShowCharacterMenuTrigger,
            onAutoShowMenuConsumed = { state.autoShowCharacterMenuTrigger = false },
            personasExpanded = personasExpanded,
            onPersonasExpandedChange = { personasExpanded = it },
            charactersExpanded = charactersExpanded,
            onCharactersExpandedChange = { charactersExpanded = it },
            confirmBeforeDelete = confirmBeforeDelete
        )
    }
}

@Composable
internal fun ChatScaffold(
    state: MainScreenState,
    deps: MainScreenDependencies,
    chatState: ChatUiState,
    activePersona: Persona?,
    hasApiProfile: Boolean,
    hasPersona: Boolean,
    hasCharacter: Boolean,
    onSendMessage: (String, List<ByteArray>) -> Boolean,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    sendWithCtrlEnter: Boolean = false,
    modifier: Modifier = Modifier
) {
    val messages = chatState.messages
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            if (state.isSelectMode) {
                MessageSelectTopBar(
                    selectedMessageIds = state.selectedMessageIds,
                    onCancel = { state.exitSelectMode() },
                    onDeleteSelected = {
                        state.activeSessionId?.let { deps.chatController.deleteMessagesRaw(it, state.selectedMessageIds.toList()) }
                        state.exitSelectMode()
                    }
                )
            } else {
                ChatTopBar(
                    activeCharacter = state.activeCharacter,
                    isDesktop = isDesktop,
                    onEditCharacter = { state.openEditor(state.activeCharacter) },
                    onCloseChat = { state.activeCharacter = null },
                    onOpenSettings = { onActiveDrawerChange(ActiveDrawer.Settings) },
                    onOpenCharacters = { onActiveDrawerChange(ActiveDrawer.Characters) }
                )
            }
        }
    ) { paddingValues ->
        // imePadding keeps the input bar (and the message list) above the
        // soft keyboard in edge-to-edge mode on Android.
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            ChatArea(
                chatState = chatState,
                activeCharacter = state.activeCharacter,
                activePersonaName = activePersona?.name.orEmpty(),
                activePersonaAvatar = activePersona?.avatarData,
                hasApiProfile = hasApiProfile,
                hasPersona = hasPersona,
                hasCharacter = hasCharacter,
                isSelectMode = state.isSelectMode,
                selectedMessageIds = state.selectedMessageIds,
                onSelectMessageToggle = { id ->
                    val index = messages.indexOfFirst { it.id == id }
                    if (index != -1) {
                        val rangeIds = messages.subList(index, messages.size).map { it.id }.toSet()
                        // Toggle: tapping an already-selected message
                        // deselects it (and everything below it).
                        state.selectedMessageIds = if (id in state.selectedMessageIds) {
                            state.selectedMessageIds - rangeIds
                        } else {
                            state.selectedMessageIds + rangeIds
                        }
                    }
                },
                onEnterSelectMode = { state.enterSelectMode() },
                sendWithCtrlEnter = sendWithCtrlEnter,
                actions = buildChatActions(
                    chatController = deps.chatController,
                    activeSessionId = state.activeSessionId,
                    activeCharacter = state.activeCharacter,
                    activePersona = activePersona,
                    messages = messages,
                    siblingsMap = chatState.siblingsMap,
                    isGenerating = chatState.isGenerating,
                    currentSessionDetails = chatState.currentSession,
                    scope = scope,
                    isDesktop = isDesktop,
                    onSendMessage = onSendMessage,
                    onSetActiveSession = { state.activeSessionId = it },
                    onManageChats = { state.showChatManagerDialog = true },
                    onRequestPersonaEdit = { state.autoEditPersonaTrigger = true },
                    onRequestCharacterMenu = { state.autoShowCharacterMenuTrigger = true },
                    onActiveDrawerChange = onActiveDrawerChange
                )
            )
        }
    }
}

@Composable
internal fun MobilePanels(
    activeDrawer: ActiveDrawer,
    drawerWidth: Dp,
    deps: MainScreenDependencies,
    state: MainScreenState,
    personas: List<Persona>,
    activePersonaId: String?,
    characters: List<Character>,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    autoSyncOnLaunch: Boolean,
    onAutoSyncOnLaunchChange: (Boolean) -> Unit,
    sendWithCtrlEnter: Boolean,
    onSendWithCtrlEnterChange: (Boolean) -> Unit,
    confirmBeforeDelete: Boolean,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onImportCharacters: (BatchImportResult) -> Unit,
    onExportSelected: (Set<String>) -> Unit,
    onCharacterCreate: (String) -> Unit,
    exportCharacterFromList: (Character) -> Unit
) {
    SidePanels(
        activeDrawer = activeDrawer,
        drawerWidth = drawerWidth,
        onClose = { onActiveDrawerChange(ActiveDrawer.None) },
        apiSettingsRepository = deps.apiSettingsRepository,
        pricingRepository = deps.pricingRepository,
        apiKeyCipher = deps.apiKeyCipher,
        syncService = deps.syncService,
        syncRepository = deps.syncRepository,
        syncDiscovery = deps.syncDiscovery,
        chatClient = deps.chatClient,
        onEnsureSyncRunning = deps.onEnsureSyncRunning,
        isDarkMode = isDarkMode,
        onToggleDarkMode = onToggleDarkMode,
        autoSyncOnLaunch = autoSyncOnLaunch,
        onAutoSyncOnLaunchChange = onAutoSyncOnLaunchChange,
        sendWithCtrlEnter = sendWithCtrlEnter,
        onSendWithCtrlEnterChange = onSendWithCtrlEnterChange,
        confirmBeforeDelete = confirmBeforeDelete,
        onConfirmBeforeDeleteChange = onConfirmBeforeDeleteChange,
        personas = personas,
        activePersonaId = activePersonaId,
        onPersonaSelect = onPersonaSelect,
        onPersonaAdd = onPersonaAdd,
        onPersonaUpdate = onPersonaUpdate,
        onPersonaDelete = onPersonaDelete,
        characters = characters,
        onCharacterSelect = { character -> state.activeCharacter = character; onActiveDrawerChange(ActiveDrawer.None) },
        onCharactersDelete = { ids -> if (state.activeCharacter?.id in ids) state.activeCharacter = null; onCharactersDelete(ids) },
        onImportCharacters = onImportCharacters,
        onExportSelected = onExportSelected,
        onCharacterCreate = { name -> state.pendingCreationName = name; onCharacterCreate(name); onActiveDrawerChange(ActiveDrawer.None) },
        onCharacterEdit = { character -> state.openEditor(character); onActiveDrawerChange(ActiveDrawer.None) },
        onCharacterExport = exportCharacterFromList,
        autoEditDefaultPersona = state.autoEditPersonaTrigger,
        onAutoEditConsumed = { state.autoEditPersonaTrigger = false },
        autoShowNewCharacterMenu = state.autoShowCharacterMenuTrigger,
        onAutoShowMenuConsumed = { state.autoShowCharacterMenuTrigger = false },
        onApiChanged = { state.refreshApiProfile() }
    )
}

@Composable
internal fun MainScreenOverlays(
    deps: MainScreenDependencies,
    state: MainScreenState,
    chatState: ChatUiState,
    activePersonaId: String?,
    exportCharacterFromList: (Character) -> Unit,
    onRefreshMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chatController = deps.chatController

    Box(modifier = modifier) {
        CharacterEditorOverlay(
            editingCharacter = state.editingCharacter,
            lastEditingCharacter = state.lastEditingCharacter,
            activeCharacter = state.activeCharacter,
            activeSessionId = state.activeSessionId,
            messages = chatState.messages,
            characterRepository = deps.characterRepository,
            sessionRepository = deps.sessionRepository,
            messageRepository = deps.messageRepository,
            onSetEditingCharacter = { state.openEditor(it) },
            onSetActiveCharacter = { state.activeCharacter = it },
            onExportCharacter = exportCharacterFromList,
            onRefreshMessages = onRefreshMessages
        )

        if (state.showChatManagerDialog && state.activeCharacter != null && activePersonaId != null) {
            ChatManagerDialog(
                characterId = state.activeCharacter!!.id,
                personaId = activePersonaId,
                activeSessionId = state.activeSessionId,
                sessionRepository = deps.sessionRepository,
                onDismissRequest = { state.showChatManagerDialog = false },
                onSessionSelected = { id -> state.activeSessionId = id.ifBlank { null }; state.showChatManagerDialog = false }
            )
        }

        ExportNotificationBubble(
            visible = state.showExportNotification,
            exportedDir = state.exportedDir,
            exportedCount = state.exportedCount,
            onDismiss = { state.showExportNotification = false },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        ErrorNotificationBubble(
            visible = chatState.errorMessage != null,
            message = chatState.errorMessage.orEmpty(),
            isWarning = chatState.errorIsWarning,
            onDismiss = { chatController.clearError() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

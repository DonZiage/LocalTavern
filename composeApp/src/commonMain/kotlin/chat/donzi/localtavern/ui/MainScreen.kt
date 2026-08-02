package chat.donzi.localtavern.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.components.SidePanels
import chat.donzi.localtavern.ui.components.SettingsPanelContent
import chat.donzi.localtavern.ui.components.CharactersPanelContent
import chat.donzi.localtavern.ui.components.ChatActions
import chat.donzi.localtavern.ui.components.ChatArea
import chat.donzi.localtavern.ui.components.ChatTopBar
import chat.donzi.localtavern.ui.components.CharacterDefinitionEditor
import chat.donzi.localtavern.ui.components.MessageSelectTopBar
import chat.donzi.localtavern.ui.components.ExportNotificationBubble
import chat.donzi.localtavern.ui.components.ErrorNotificationBubble
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import androidx.compose.ui.geometry.Offset
import chat.donzi.localtavern.isDesktop
import chat.donzi.localtavern.utils.CharacterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime

private val sessionDateTimeFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    monthNumber(Padding.NONE)
    char('/')
    day(Padding.NONE)
    char('/')
    yearTwoDigits(baseYear = 2000)
    char(' ')
    amPmHour(Padding.NONE)
    char(':')
    minute()
    amPmMarker("AM", "PM")
}

private fun formatTimestampToDateTime(timestamp: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
    return sessionDateTimeFormat.format(dateTime)
}

@Composable
fun MainScreen(
    chatController: ChatController,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    apiSettingsRepository: ApiSettingsRepository,
    chatClient: ChatClient,
    characters: List<Character>,
    personas: List<Persona>,
    activePersonaId: String?,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    activeDrawer: ActiveDrawer,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onCharacterImport: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCharacterCreate: (String) -> Unit
) {
    val drawerWidth = 300.dp
    var activeCharacter by remember { mutableStateOf<Character?>(null) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val chatState by chatController.state.collectAsState()
    val messages = chatState.messages
    val siblingsMap = chatState.siblingsMap
    val currentSessionDetails = chatState.currentSession
    val isGenerating = chatState.isGenerating
    val structuredErrorMessage = chatState.errorMessage.orEmpty()
    val structuredErrorIsWarning = chatState.errorIsWarning
    val showStructuredErrorNotification = chatState.errorMessage != null

    var isSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<String>()) }
    var editingCharacter by remember { mutableStateOf<Character?>(null) }

    var hasApiProfile by remember { mutableStateOf(false) }

    var autoEditPersonaTrigger by remember { mutableStateOf(false) }
    var autoShowCharacterMenuTrigger by remember { mutableStateOf(false) }
    var showChatManagerDialog by remember { mutableStateOf(false) }

    var showExportNotification by remember { mutableStateOf(false) }
    var exportedDir by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val hasPersona = remember(personas) {
        personas.any { it.name != "User" || !it.description.isNullOrBlank() || it.avatarData != null }
    }
    val hasCharacter = remember(characters) {
        characters.isNotEmpty()
    }

    LaunchedEffect(activeSessionId, characters, personas, activeDrawer) {
        hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
    }

    var lastEditingCharacter by remember { mutableStateOf<Character?>(null) }
    LaunchedEffect(editingCharacter) {
        if (editingCharacter != null) {
            lastEditingCharacter = editingCharacter
        }
    }

    var pendingCreationName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(characters) {
        pendingCreationName?.let { name ->
            val newChar = characters.findLast { it.name == name }
            if (newChar != null) {
                editingCharacter = newChar
                pendingCreationName = null
            }
        }
    }

    LaunchedEffect(showExportNotification) {
        if (showExportNotification) {
            delay(5000.milliseconds)
            showExportNotification = false
        }
    }

    LaunchedEffect(chatState.errorMessage) {
        if (chatState.errorMessage != null) {
            delay(5000.milliseconds)
            chatController.clearError()
        }
    }

    val activePersona = remember(personas, activePersonaId) {
        personas.find { it.id == activePersonaId }
    }

    val exportCharacterFromList = { targetChar: Character ->
        if (!isDesktop) onActiveDrawerChange(ActiveDrawer.None)
        coroutineScope.launch {
            try {
                // PNG re-encoding can be heavy; do it off the main thread. The
                // actual save (file dialog) must stay on the main thread.
                val (fileName, bytes) = withContext(Dispatchers.Default) {
                    CharacterManager.prepareExportBytes(targetChar)
                }
                val parentDir = CharacterManager.saveExportedFile(fileName, bytes)
                if (parentDir != null) {
                    exportedDir = parentDir
                    showExportNotification = true
                } else {
                    snackbarHostState.showSnackbar("Failed to export: Could not save file")
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Failed to export: ${e.message}")
            }
        }
    }

    fun refreshMessages() {
        coroutineScope.launch {
            hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
        }
        chatController.refresh(activeSessionId)
    }

    LaunchedEffect(activeCharacter, activePersonaId, activeSessionId, characters, personas) {
        isSelectMode = false
        selectedMessageIds = emptySet()
        hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
        if (activePersonaId != null && activeCharacter != null) {
            var sessionId = activeSessionId
            val currentSession = sessionId?.let { sessionRepository.getSessionById(it) }

            if (currentSession == null || currentSession.characterId != activeCharacter!!.id || currentSession.personaId != activePersonaId) {
                sessionId = sessionRepository.getOrCreateSession(activeCharacter!!.id, activePersonaId)
                activeSessionId = sessionId
            }

            sessionRepository.ensureInitialGreetings(sessionId, activeCharacter!!)
            chatController.refresh(sessionId)
        } else {
            activeSessionId = null
            chatController.refresh(null)
        }
    }

    val onSendMessage: (String, List<ByteArray>) -> Unit = { userMessage, imageList ->
        if (!isGenerating) {
            coroutineScope.launch {
                var currentActiveCharacter = activeCharacter
                if (currentActiveCharacter == null) {
                    var assistant = characterRepository.getAssistant()
                    if (assistant == null) {
                        characterRepository.createAssistant()
                        assistant = characterRepository.getAssistant()
                    }
                    if (assistant != null) {
                        activeCharacter = assistant
                        currentActiveCharacter = assistant
                    }
                }

                if (currentActiveCharacter != null && activePersonaId != null) {
                    val sessionId = sessionRepository.getOrCreateSession(currentActiveCharacter.id, activePersonaId)
                    activeSessionId = sessionId

                    sessionRepository.ensureInitialGreetings(sessionId, currentActiveCharacter)

                    val updatedSession = sessionRepository.getSessionById(sessionId)
                    sessionRepository.insertMessage(sessionId, "user", userMessage, updatedSession?.currentMessageId, imageList)
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
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isDesktop) {
                var settingsApiExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.width(drawerWidth).fillMaxHeight()) {
                    SettingsPanelContent(
                        apiSettingsRepository = apiSettingsRepository,
                        chatClient = chatClient,
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = onToggleDarkMode,
                        onApiChanged = {
                            coroutineScope.launch {
                                hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
                            }
                        },
                        apiSectionExpanded = settingsApiExpanded,
                        onApiSectionExpandedChange = { settingsApiExpanded = it }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Scaffold(
                    topBar = {
                        if (isSelectMode) {
                            MessageSelectTopBar(
                                selectedMessageIds = selectedMessageIds,
                                onCancel = { isSelectMode = false; selectedMessageIds = emptySet() },
                                onDeleteSelected = {
                                    activeSessionId?.let { chatController.deleteMessagesRaw(it, selectedMessageIds.toList()) }
                                    isSelectMode = false; selectedMessageIds = emptySet()
                                }
                            )
                        } else {
                            ChatTopBar(
                                activeCharacter = activeCharacter,
                                isDesktop = isDesktop,
                                onEditCharacter = { editingCharacter = activeCharacter },
                                onCloseChat = { activeCharacter = null },
                                onOpenSettings = { onActiveDrawerChange(ActiveDrawer.Settings) },
                                onOpenCharacters = { onActiveDrawerChange(ActiveDrawer.Characters) }
                            )
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        ChatArea(
                            chatState = chatState,
                            activeCharacter = activeCharacter,
                            activePersonaName = activePersona?.name.orEmpty(),
                            activePersonaAvatar = activePersona?.avatarData,
                            hasApiProfile = hasApiProfile,
                            hasPersona = hasPersona,
                            hasCharacter = hasCharacter,
                            isSelectMode = isSelectMode,
                            selectedMessageIds = selectedMessageIds,
                            onSelectMessageToggle = { id ->
                                val index = messages.indexOfFirst { it.id == id }
                                if (index != -1) {
                                    val rangeIds = messages.subList(index, messages.size).map { it.id }.toSet()
                                    // Toggle: tapping an already-selected message
                                    // deselects it (and everything below it).
                                    selectedMessageIds = if (id in selectedMessageIds) {
                                        selectedMessageIds - rangeIds
                                    } else {
                                        selectedMessageIds + rangeIds
                                    }
                                }
                            },
                            onEnterSelectMode = { isSelectMode = true; selectedMessageIds = emptySet() },
                            actions = ChatActions(
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
                                onManageChats = { showChatManagerDialog = true },
                                onBranchMessage = { message ->
                                    coroutineScope.launch {
                                        activeSessionId?.let { sessionId ->
                                            val newSessionId = chatController.branchSession(sessionId, message, activeCharacter?.name)
                                            activeSessionId = newSessionId
                                            chatController.refresh(newSessionId)
                                        }
                                    }
                                },
                                onGoToParentChat = currentSessionDetails?.parentSessionId?.let { parentId -> { activeSessionId = parentId; chatController.refresh(parentId) } },
                                onAddImageToMessage = { targetMessageId, pickedBytesList ->
                                    activeSessionId?.let { chatController.addImageToMessage(it, targetMessageId, pickedBytesList) }
                                },
                                onNavigateToSettings = { if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Settings) },
                                onNavigateToPersonas = { autoEditPersonaTrigger = true; if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) },
                                onNavigateToCharacters = { autoShowCharacterMenuTrigger = true; if (!isDesktop) onActiveDrawerChange(ActiveDrawer.Characters) }
                            )
                        )
                    }
                }
            }

            if (isDesktop) {
                var personasExpanded by remember { mutableStateOf(true) }
                var charactersExpanded by remember { mutableStateOf(true) }
                Box(modifier = Modifier.width(drawerWidth).fillMaxHeight()) {
                    CharactersPanelContent(
                        personas = personas,
                        activePersonaId = activePersonaId,
                        onPersonaSelect = onPersonaSelect,
                        onPersonaAdd = onPersonaAdd,
                        onPersonaUpdate = onPersonaUpdate,
                        onPersonaDelete = onPersonaDelete,
                        characters = characters,
                        onCharacterSelect = { character -> activeCharacter = character },
                        onCharactersDelete = { ids -> if (activeCharacter?.id in ids) activeCharacter = null; onCharactersDelete(ids) },
                        onCharacterImport = onCharacterImport,
                        onCharacterCreate = { name -> pendingCreationName = name; onCharacterCreate(name) },
                        onCharacterEdit = { character -> editingCharacter = character },
                        onCharacterExport = { character -> exportCharacterFromList(character) },
                        autoEditDefaultPersona = autoEditPersonaTrigger,
                        onAutoEditConsumed = { autoEditPersonaTrigger = false },
                        autoShowNewCharacterMenu = autoShowCharacterMenuTrigger,
                        onAutoShowMenuConsumed = { autoShowCharacterMenuTrigger = false },
                        personasExpanded = personasExpanded,
                        onPersonasExpandedChange = { personasExpanded = it },
                        charactersExpanded = charactersExpanded,
                        onCharactersExpandedChange = { charactersExpanded = it }
                    )
                }
            }
        }

        if (!isDesktop) {
            SidePanels(
                activeDrawer = activeDrawer, drawerWidth = drawerWidth, onClose = { onActiveDrawerChange(ActiveDrawer.None) },
                apiSettingsRepository = apiSettingsRepository, chatClient = chatClient, isDarkMode = isDarkMode, onToggleDarkMode = onToggleDarkMode,
                personas = personas, activePersonaId = activePersonaId, onPersonaSelect = onPersonaSelect, onPersonaAdd = onPersonaAdd, onPersonaUpdate = onPersonaUpdate, onPersonaDelete = onPersonaDelete,
                characters = characters, onCharacterSelect = { character -> activeCharacter = character; onActiveDrawerChange(ActiveDrawer.None) },
                onCharactersDelete = { ids -> if (activeCharacter?.id in ids) activeCharacter = null; onCharactersDelete(ids) }, onCharacterImport = onCharacterImport,
                onCharacterCreate = { name -> pendingCreationName = name; onCharacterCreate(name); onActiveDrawerChange(ActiveDrawer.None) }, onCharacterEdit = { character -> editingCharacter = character; onActiveDrawerChange(ActiveDrawer.None) },
                onCharacterExport = { character -> exportCharacterFromList(character) }, autoEditDefaultPersona = autoEditPersonaTrigger, onAutoEditConsumed = { autoEditPersonaTrigger = false },
                autoShowNewCharacterMenu = autoShowCharacterMenuTrigger, onAutoShowMenuConsumed = { autoShowCharacterMenuTrigger = false },
                onApiChanged = {
                    coroutineScope.launch {
                        hasApiProfile = apiSettingsRepository.getAllApiConnections().isNotEmpty()
                    }
                }
            )
        }

        AnimatedVisibility(visible = editingCharacter != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
            lastEditingCharacter?.let { targetCharacter ->
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    key(targetCharacter.id) {
                        CharacterDefinitionEditor(
                            character = targetCharacter, onClose = { editingCharacter = null },
                            onSave = { name, desc, personality, scenario, firstMes, mesExample, altGreetings, avatarData ->
                                coroutineScope.launch {
                                    characterRepository.updateCharacter(targetCharacter.id, name, personality, scenario, desc, firstMes, mesExample, altGreetings, avatarData)
                                    val freshCharacter = characterRepository.getCharacterById(targetCharacter.id)
                                    if (freshCharacter != null && editingCharacter?.id == targetCharacter.id) {
                                        editingCharacter = freshCharacter
                                    }

                                    if (activeCharacter?.id == targetCharacter.id) activeCharacter = freshCharacter

                                    val greetingsChanged = targetCharacter.firstMes != firstMes || targetCharacter.altGreetings != altGreetings
                                    if (greetingsChanged) {
                                        val textList = mutableListOf<String>()
                                        if (firstMes.isNotBlank()) textList.add(firstMes)
                                        altGreetings.filter { it.isNotBlank() }.forEach { textList.add(it) }

                                        // Sync the greeting roots across ALL of the
                                        // character's sessions, not just the first one.
                                        val targetSessions = sessionRepository.getSessionsForCharacter(targetCharacter.id)
                                        targetSessions.forEach { session ->
                                            val currentRoots = sessionRepository.getMessageSiblings(session.id, null)
                                            currentRoots.forEachIndexed { index, existingMessage ->
                                                if (index < textList.size) {
                                                    sessionRepository.updateMessageContent(existingMessage.id, textList[index])
                                                } else {
                                                    sessionRepository.deleteMessage(existingMessage.id)
                                                }
                                            }
                                            if (textList.size > currentRoots.size) {
                                                for (i in currentRoots.size until textList.size) {
                                                    sessionRepository.insertMessageRaw(session.id, "assistant", textList[i], null, false)
                                                }
                                            }
                                        }

                                        // Only the active session's view may be re-seeded.
                                        val currentActiveSessionId = activeSessionId
                                        if (currentActiveSessionId != null && activeCharacter?.id == targetCharacter.id) {
                                            val finalRoots = sessionRepository.getMessageSiblings(currentActiveSessionId, null)
                                            if (finalRoots.isNotEmpty() && finalRoots.none { it.id == messages.firstOrNull()?.id }) {
                                                finalRoots.firstOrNull()?.let { sessionRepository.selectVariation(currentActiveSessionId, it.id, null) }
                                            }
                                        }
                                    }
                                    refreshMessages()
                                }
                            },
                            onDelete = { coroutineScope.launch { characterRepository.deleteCharacters(setOf(targetCharacter.id)); if (activeCharacter?.id == targetCharacter.id) activeCharacter = null; editingCharacter = null } },
                            onExport = { updatedCharacter -> exportCharacterFromList(updatedCharacter) }
                        )
                    }
                }
            }
        }

        if (showChatManagerDialog && activeCharacter != null && activePersonaId != null) {
            ChatManagerDialog(
                characterId = activeCharacter!!.id,
                personaId = activePersonaId,
                activeSessionId = activeSessionId,
                sessionRepository = sessionRepository,
                onDismissRequest = { showChatManagerDialog = false },
                onSessionSelected = { id -> activeSessionId = id.ifBlank { null }; showChatManagerDialog = false }
            )
        }

        ExportNotificationBubble(
            visible = showExportNotification,
            exportedDir = exportedDir,
            onDismiss = { showExportNotification = false },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        ErrorNotificationBubble(
            visible = showStructuredErrorNotification,
            message = structuredErrorMessage,
            isWarning = structuredErrorIsWarning,
            onDismiss = { chatController.clearError() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
fun ChatManagerDialog(
    characterId: String,
    personaId: String,
    activeSessionId: String?,
    sessionRepository: SessionRepository,
    onDismissRequest: () -> Unit,
    onSessionSelected: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var expandedMenuSessionId by remember { mutableStateOf<String?>(null) }
    var sessionToRename by remember { mutableStateOf<Session?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }

    fun loadSessions() { coroutineScope.launch { sessions = sessionRepository.getSessionsForCharacter(characterId) } }
    LaunchedEffect(characterId) { loadSessions() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Saved Chats", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { coroutineScope.launch { val newSessionId = sessionRepository.createNewSession(characterId, personaId); onSessionSelected(newSessionId) } }) { Icon(Icons.Default.Add, contentDescription = "New Chat") }
            }
        },
        text = {
            Box(modifier = Modifier.sizeIn(maxHeight = 280.dp, minWidth = 280.dp)) {
                if (sessions.isEmpty()) {
                    Text("No alternative chats found.", modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(sessions) { session ->
                            val isActive = session.id == activeSessionId
                            Card(colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth().clickable { onSessionSelected(session.id) }) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(text = session.title ?: "#${session.id.take(6)} ${formatTimestampToDateTime(session.lastTimestamp)}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                        if (isActive) Text(text = "Active Conversation", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Box {
                                        IconButton(onClick = { expandedMenuSessionId = session.id }) { Icon(Icons.Default.MoreVert, contentDescription = "Chat Options", modifier = Modifier.size(20.dp)) }
                                        DropdownMenu(expanded = expandedMenuSessionId == session.id, onDismissRequest = { expandedMenuSessionId = null }) {
                                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }, onClick = { expandedMenuSessionId = null; sessionToRename = session; editTitleText = session.title ?: "" })
                                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }, onClick = { expandedMenuSessionId = null; sessionToDelete = session })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("Close") } }
    )

    if (sessionToRename != null) {
        val targetSession = sessionToRename!!
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Chat") },
            text = { Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { OutlinedTextField(value = editTitleText, onValueChange = { editTitleText = it }, label = { Text("Chat Title") }, placeholder = { Text("#${targetSession.id.take(6)}") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(onClick = { coroutineScope.launch { sessionRepository.updateSessionTitle(targetSession.id, editTitleText.ifBlank { null }); sessionToRename = null; loadSessions() } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { sessionToRename = null }) { Text("Cancel") } }
        )
    }

    if (sessionToDelete != null) {
        val targetSession = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("Are you sure you want to delete this chat session? All associated logs and swipe messages will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { coroutineScope.launch { sessionRepository.deleteSession(targetSession.id); if (targetSession.id == activeSessionId) { val remaining = sessions.filter { it.id != targetSession.id }; if (remaining.isNotEmpty()) onSessionSelected(remaining.first().id) else onSessionSelected("") } else { loadSessions() }; sessionToDelete = null } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") } }
        )
    }
}
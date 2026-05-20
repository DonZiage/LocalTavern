package chat.donzi.localtavern.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.database.MessageEntity
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.components.SidePanels
import chat.donzi.localtavern.ui.components.ChatArea
import chat.donzi.localtavern.ui.components.CharacterDefinitionEditor
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import androidx.compose.ui.geometry.Offset
import chat.donzi.localtavern.utils.ContextManager
import chat.donzi.localtavern.utils.ChatMessage
import chat.donzi.localtavern.utils.toDomain
import kotlinx.coroutines.launch

private suspend fun insertInitialGreetings(
    chatRepository: ChatRepository,
    sessionId: Long,
    character: CharacterEntity
) {
    val primaryGreeting = character.firstMes ?: ""
    val altGreetingsList = character.altGreetings?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
    val allGreetings = mutableListOf<String>()

    if (primaryGreeting.isNotBlank()) allGreetings.add(primaryGreeting)
    allGreetings.addAll(altGreetingsList)
    if (allGreetings.isEmpty()) allGreetings.add("Hello!")

    var primaryMessageId: Long? = null
    allGreetings.forEachIndexed { index, greeting ->
        val isActive = index == 0
        val msgId = chatRepository.insertMessageRaw(sessionId, "assistant", greeting, null, isActive)
        if (isActive) primaryMessageId = msgId
    }
    primaryMessageId?.let {
        chatRepository.updateSessionCurrentMessage(sessionId, it)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    chatRepository: ChatRepository,
    chatClient: ChatClient,
    characters: List<CharacterEntity>,
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    activeDrawer: ActiveDrawer,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    refreshData: () -> Unit,
    onPersonaSelect: (Long) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (Long, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (Long) -> Unit,
    onCharactersDelete: (Set<Long>) -> Unit,
    onCharacterImport: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCharacterCreate: (String) -> Unit
) {
    val drawerWidth = 300.dp
    var activeCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    var messages by remember { mutableStateOf(emptyList<MessageEntity>()) }
    var siblingsMap by remember { mutableStateOf(emptyMap<Long, List<MessageEntity>>()) }
    val coroutineScope = rememberCoroutineScope()

    var isSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<Long>()) }
    var editingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }

    var hasApiProfile by remember { mutableStateOf(false) }

    // Automated trigger tokens for interactive setup flow configurations
    var autoEditPersonaTrigger by remember { mutableStateOf(false) }
    var autoShowCharacterMenuTrigger by remember { mutableStateOf(false) }

    val hasPersona = remember(personas) {
        personas.any { it.name != "User" || !it.description.isNullOrBlank() || it.avatarData != null }
    }
    val hasCharacter = remember(characters) {
        characters.isNotEmpty()
    }

    var lastEditingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
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

    val activePersona = remember(personas, activePersonaId) {
        personas.find { it.id == activePersonaId }
    }

    fun refreshMessages() {
        coroutineScope.launch {
            hasApiProfile = chatRepository.getAllApiConnections().isNotEmpty()

            activeSessionId?.let { sessionId ->
                val activeTimeline = chatRepository.getMessagesForSession(sessionId)
                messages = activeTimeline

                val updatedSiblings = mutableMapOf<Long, List<MessageEntity>>()
                val rootGreetings = chatRepository.getMessageSiblings(sessionId, null)
                if (rootGreetings.isNotEmpty()) {
                    rootGreetings.forEach { updatedSiblings[it.id] = rootGreetings }
                }

                activeTimeline.lastOrNull()?.let { lastMsg ->
                    if (lastMsg.role == "assistant" && lastMsg.parentId != null) {
                        val lastSiblings = chatRepository.getMessageSiblings(sessionId, lastMsg.parentId)
                        lastSiblings.forEach { updatedSiblings[it.id] = lastSiblings }
                    }
                }
                siblingsMap = updatedSiblings
            } ?: run {
                messages = emptyList()
                siblingsMap = emptyMap()
            }
        }
    }

    LaunchedEffect(activeCharacter, activePersonaId, characters, personas) {
        isSelectMode = false
        selectedMessageIds = emptySet()
        if (activePersonaId != null && activeCharacter != null) {
            val sessionId = chatRepository.getOrCreateSession(activeCharacter!!.id, activePersonaId)
            activeSessionId = sessionId

            val currentMessages = chatRepository.getMessagesForSession(sessionId)
            if (currentMessages.isEmpty()) {
                insertInitialGreetings(chatRepository, sessionId, activeCharacter!!)
            }
            refreshMessages()
        } else {
            activeSessionId = null
            messages = emptyList()
            siblingsMap = emptyMap()
            hasApiProfile = chatRepository.getAllApiConnections().isNotEmpty()
        }
    }

    fun requestAiResponse(sessionId: Long, targetParentId: Long? = null) {
        coroutineScope.launch {
            val activeConnection = chatRepository.getActiveApiConnection()
            if (activeConnection != null) {
                val aiMessageId = chatRepository.insertMessage(sessionId, "assistant", "...", targetParentId)
                refreshMessages()

                var fullResponse = ""
                var receivedFirstToken = false
                val dbMessages = chatRepository.getMessagesForSession(sessionId)

                val chatHistory = dbMessages
                    .filter { it.id != aiMessageId }
                    .map { ChatMessage(role = it.role, content = it.content) }

                val blocks = chatRepository.getAllPromptBlocks().map { it.toDomain() }
                val messagesPayload = ContextManager.buildPayload(
                    blocks = blocks,
                    character = activeCharacter,
                    persona = activePersona,
                    chatHistory = chatHistory,
                    contextLimit = activeConnection.contextLimit,
                    responseLimit = activeConnection.responseLimit
                )

                try {
                    chatClient.streamChatRequest(
                        baseUrl = activeConnection.baseUrl ?: "",
                        apiKey = activeConnection.apiKey ?: "",
                        model = activeConnection.model ?: "gpt-3.5-turbo",
                        messages = messagesPayload,
                        isChatCompletion = activeConnection.isChatCompletion == 1L
                    ).collect { token ->
                        if (!receivedFirstToken) {
                            receivedFirstToken = true
                            fullResponse = token
                        } else {
                            fullResponse += token
                        }

                        messages = messages.map { msg ->
                            if (msg.id == aiMessageId) msg.copy(content = fullResponse) else msg
                        }
                        chatRepository.updateMessageContent(aiMessageId, fullResponse)
                    }
                    refreshMessages()
                } catch (e: Exception) {
                    val errorText = "Error: ${e.message}"
                    messages = messages.map { msg ->
                        if (msg.id == aiMessageId) msg.copy(content = errorText) else msg
                    }
                    chatRepository.updateMessageContent(aiMessageId, errorText)
                    refreshMessages()
                }
            } else {
                activeSessionId?.let { chatRepository.insertMessage(it, "assistant", "No active connection.", targetParentId) }
                refreshMessages()
            }
        }
    }

    val onSendMessage: (String) -> Unit = { userMessage ->
        coroutineScope.launch {
            var currentActiveCharacter = activeCharacter
            if (currentActiveCharacter == null) {
                var assistant = chatRepository.getAssistant()
                if (assistant == null) {
                    chatRepository.createAssistant()
                    assistant = chatRepository.getAssistant()
                    refreshData()
                }
                if (assistant != null) {
                    activeCharacter = assistant
                    currentActiveCharacter = assistant
                }
            }

            if (currentActiveCharacter != null && activePersonaId != null) {
                val sessionId = chatRepository.getOrCreateSession(currentActiveCharacter.id, activePersonaId)
                activeSessionId = sessionId

                val currentMessages = chatRepository.getMessagesForSession(sessionId)
                if (currentMessages.isEmpty()) {
                    insertInitialGreetings(chatRepository, sessionId, currentActiveCharacter)
                }

                val sessionDetails = chatRepository.getSessionById(sessionId)
                chatRepository.insertMessage(sessionId, "user", userMessage, sessionDetails?.currentMessageId)
                refreshMessages()

                val updatedSession = chatRepository.getSessionById(sessionId)
                requestAiResponse(sessionId, updatedSession?.currentMessageId)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (isSelectMode) {
                    TopAppBar(
                        title = { Text(if (selectedMessageIds.isEmpty()) "Select Messages" else "${selectedMessageIds.size} Selected") },
                        actions = {
                            OutlinedButton(onClick = { isSelectMode = false; selectedMessageIds = emptySet() }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        for (id in selectedMessageIds) chatRepository.deleteMessage(id)
                                        isSelectMode = false
                                        selectedMessageIds = emptySet()
                                        refreshMessages()
                                    }
                                },
                                enabled = selectedMessageIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Text("Delete")
                            }
                            IconButton(onClick = { isSelectMode = false; selectedMessageIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(activeCharacter?.name ?: "LocalTavern")
                                if (activeCharacter != null) {
                                    IconButton(onClick = { editingCharacter = activeCharacter }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { onActiveDrawerChange(ActiveDrawer.Settings) }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Settings")
                            }
                        },
                        actions = {
                            if (activeCharacter != null) {
                                IconButton(onClick = { activeCharacter = null }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close Chat")
                                }
                            }
                            IconButton(onClick = { onActiveDrawerChange(ActiveDrawer.Characters) }) {
                                Icon(Icons.Filled.Person, contentDescription = "Characters")
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                ChatArea(
                    activeCharacter = activeCharacter,
                    activePersonaAvatar = activePersona?.avatarData,
                    messages = messages,
                    siblingsMap = siblingsMap,
                    hasApiProfile = hasApiProfile,
                    hasPersona = hasPersona,
                    hasCharacter = hasCharacter,
                    onNavigateToSettings = { onActiveDrawerChange(ActiveDrawer.Settings) },
                    onNavigateToPersonas = {
                        autoEditPersonaTrigger = true
                        onActiveDrawerChange(ActiveDrawer.Characters)
                    },
                    onNavigateToCharacters = {
                        autoShowCharacterMenuTrigger = true
                        onActiveDrawerChange(ActiveDrawer.Characters)
                    },
                    onSendMessage = onSendMessage,
                    onEditMessage = { id, content ->
                        coroutineScope.launch {
                            chatRepository.updateMessageContent(id, content)
                            messages = messages.map { msg ->
                                if (msg.id == id) msg.copy(content = content) else msg
                            }
                            refreshMessages()
                        }
                    },
                    onDeleteMessage = { id ->
                        coroutineScope.launch {
                            chatRepository.deleteMessage(id)
                            refreshMessages()
                        }
                    },
                    onRegenerate = {
                        coroutineScope.launch {
                            if (messages.isEmpty() || activeSessionId == null) return@launch
                            val lastMsg = messages.last()
                            val userMsg = if (lastMsg.role == "assistant") {
                                chatRepository.deleteMessage(lastMsg.id)
                                messages.dropLast(1).lastOrNull { it.role == "user" }
                            } else {
                                messages.lastOrNull { it.role == "user" }
                            }
                            if (userMsg != null) {
                                val session = chatRepository.getSessionById(activeSessionId!!)
                                requestAiResponse(activeSessionId!!, session?.currentMessageId)
                            } else {
                                refreshMessages()
                            }
                        }
                    },
                    onSelectVariation = { variationId ->
                        coroutineScope.launch {
                            activeSessionId?.let { sessionId ->
                                val msg = messages.find { it.id == variationId } ?: siblingsMap[variationId]?.find { it.id == variationId }
                                chatRepository.selectVariation(sessionId, variationId, msg?.parentId)
                                refreshMessages()
                            }
                        }
                    },
                    onGenerateNewVariation = { lastMessageId ->
                        coroutineScope.launch {
                            activeSessionId?.let { sessionId ->
                                val existingMsg = messages.find { it.id == lastMessageId }
                                requestAiResponse(sessionId, existingMsg?.parentId)
                            }
                        }
                    },
                    isSelectMode = isSelectMode,
                    selectedMessageIds = selectedMessageIds,
                    onSelectMessageToggle = { id ->
                        val index = messages.indexOfFirst { it.id == id }
                        if (index != -1) {
                            selectedMessageIds = messages.subList(index, messages.size).map { it.id }.toSet()
                        }
                    },
                    onEnterSelectMode = { isSelectMode = true; selectedMessageIds = emptySet() }
                )
            }
        }

        SidePanels(
            activeDrawer = activeDrawer,
            drawerWidth = drawerWidth,
            onClose = { onActiveDrawerChange(ActiveDrawer.None) },
            chatRepository = chatRepository,
            chatClient = chatClient,
            isDarkMode = isDarkMode,
            onToggleDarkMode = onToggleDarkMode,
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onPersonaSelect,
            onPersonaAdd = onPersonaAdd,
            onPersonaUpdate = onPersonaUpdate,
            onPersonaDelete = onPersonaDelete,
            characters = characters,
            onCharacterSelect = { character -> activeCharacter = character; onActiveDrawerChange(ActiveDrawer.None) },
            onCharactersDelete = { ids -> if (activeCharacter?.id in ids) activeCharacter = null; onCharactersDelete(ids) },
            onCharacterImport = onCharacterImport,
            onCharacterCreate = { name -> pendingCreationName = name; onCharacterCreate(name); onActiveDrawerChange(ActiveDrawer.None) },
            onCharacterEdit = { character -> editingCharacter = character; onActiveDrawerChange(ActiveDrawer.None) },
            autoEditDefaultPersona = autoEditPersonaTrigger,
            onAutoEditConsumed = { autoEditPersonaTrigger = false },
            autoShowNewCharacterMenu = autoShowCharacterMenuTrigger,
            onAutoShowMenuConsumed = { autoShowCharacterMenuTrigger = false }
        )

        AnimatedVisibility(
            visible = editingCharacter != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            lastEditingCharacter?.let { targetCharacter ->
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CharacterDefinitionEditor(
                        character = targetCharacter,
                        onClose = { editingCharacter = null },
                        onSave = { name, desc, personality, scenario, firstMes, mesExample, altGreetings, avatarData ->
                            coroutineScope.launch {
                                chatRepository.updateCharacter(
                                    id = targetCharacter.id, name = name, personality = personality,
                                    scenario = scenario, description = desc, firstMes = firstMes,
                                    mesExample = mesExample, altGreetings = altGreetings, avatarData = avatarData
                                )
                                refreshData()
                                if (activeCharacter?.id == targetCharacter.id) {
                                    activeCharacter = chatRepository.getCharacterById(targetCharacter.id)
                                }

                                activeSessionId?.let { sessionId ->
                                    val currentRoots = chatRepository.getMessageSiblings(sessionId, null)
                                    val textList = mutableListOf<String>()
                                    if (firstMes.isNotBlank()) textList.add(firstMes)
                                    altGreetings.filter { it.isNotBlank() }.forEach { textList.add(it) }
                                    if (textList.isEmpty()) textList.add("Hello!")

                                    currentRoots.forEachIndexed { index, existingMessage ->
                                        if (index < textList.size) {
                                            chatRepository.updateMessageContent(existingMessage.id, textList[index])
                                        } else {
                                            chatRepository.deleteMessage(existingMessage.id)
                                        }
                                    }
                                    if (textList.size > currentRoots.size) {
                                        for (i in currentRoots.size until textList.size) {
                                            chatRepository.insertMessageRaw(sessionId, "assistant", textList[i], null, false)
                                        }
                                    }

                                    val finalRoots = chatRepository.getMessageSiblings(sessionId, null)
                                    if (finalRoots.isNotEmpty() && finalRoots.none { it.id == messages.firstOrNull()?.id }) {
                                        finalRoots.firstOrNull()?.let { chatRepository.selectVariation(sessionId, it.id, null) }
                                    }
                                }
                                refreshMessages()
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                chatRepository.deleteCharacters(setOf(targetCharacter.id))
                                if (activeCharacter?.id == targetCharacter.id) activeCharacter = null
                                editingCharacter = null
                                refreshData()
                            }
                        }
                    )
                }
            }
        }
    }
}
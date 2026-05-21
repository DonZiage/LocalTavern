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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.database.MessageEntity
import chat.donzi.localtavern.data.database.ChatSession
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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

private fun formatTimestampToDateTime(timestamp: Long): String {
    val totalSeconds = timestamp / 1000
    val totalMinutes = totalSeconds / 60
    val totalHours = totalMinutes / 60
    val totalDays = totalHours / 24

    var year = 1970
    var daysRemaining = totalDays

    while (true) {
        val isLeap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
        val daysInYear = if (isLeap) 366 else 365
        if (daysRemaining >= daysInYear) {
            daysRemaining -= daysInYear
            year++
        } else {
            break
        }
    }

    val isLeap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    val daysInMonths = intArrayOf(31, if (isLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (month < 12) {
        val dim = daysInMonths[month]
        if (daysRemaining >= dim) {
            daysRemaining -= dim
            month++
        } else {
            break
        }
    }

    val monthNum = month + 1
    val dayNum = daysRemaining + 1
    val shortYear = year % 100

    val hour24 = (totalHours % 24).toInt()
    val minute = (totalMinutes % 60).toInt()

    val amPm = if (hour24 >= 12) "PM" else "AM"
    var hour12 = hour24 % 12
    if (hour12 == 0) hour12 = 12

    val minuteStr = if (minute < 10) "0$minute" else "$minute"

    return "$monthNum/$dayNum/$shortYear $hour12:${minuteStr}$amPm"
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
    var currentSessionDetails by remember { mutableStateOf<ChatSession?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var isSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(setOf<Long>()) }
    var editingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }

    var hasApiProfile by remember { mutableStateOf(false) }

    var isGenerating by remember { mutableStateOf(false) }
    var currentResponseJob by remember { mutableStateOf<Job?>(null) }

    var autoEditPersonaTrigger by remember { mutableStateOf(false) }
    var autoShowCharacterMenuTrigger by remember { mutableStateOf(false) }
    var showChatManagerDialog by remember { mutableStateOf(false) }

    val hasPersona = remember(personas) {
        personas.any { it.name != "User" || !it.description.isNullOrBlank() || it.avatarData != null }
    }
    val hasCharacter = remember(characters) {
        characters.isNotEmpty()
    }

    // FIXED: Query API profile configurations globally outside active conversation sessions
    // to guarantee Step 1 onboarding visibility sync blocks evaluate correctly at all times.
    LaunchedEffect(activeSessionId, characters, personas) {
        hasApiProfile = chatRepository.getAllApiConnections().isNotEmpty()
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
                val sessionDetails = chatRepository.getSessionById(sessionId)
                currentSessionDetails = sessionDetails

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
                currentSessionDetails = null
            }
        }
    }

    LaunchedEffect(activeCharacter, activePersonaId, activeSessionId, characters, personas) {
        isSelectMode = false
        selectedMessageIds = emptySet()
        hasApiProfile = chatRepository.getAllApiConnections().isNotEmpty()
        if (activePersonaId != null && activeCharacter != null) {
            var sessionId = activeSessionId
            val currentSession = sessionId?.let { chatRepository.getSessionById(it) }

            if (currentSession == null || currentSession.characterId != activeCharacter!!.id || currentSession.personaId != activePersonaId) {
                sessionId = chatRepository.getOrCreateSession(activeCharacter!!.id, activePersonaId)

                val currentMessages = chatRepository.getMessagesForSession(sessionId)
                if (currentMessages.isEmpty()) {
                    insertInitialGreetings(chatRepository, sessionId, activeCharacter!!)
                }
                activeSessionId = sessionId
            } else {
                val currentMessages = chatRepository.getMessagesForSession(sessionId)
                if (currentMessages.isEmpty()) {
                    insertInitialGreetings(chatRepository, sessionId, activeCharacter!!)
                }
                refreshMessages()
            }
        } else {
            activeSessionId = null
            messages = emptyList()
            siblingsMap = emptyMap()
        }
    }

    fun requestAiResponse(sessionId: Long, targetParentId: Long? = null) {
        currentResponseJob?.cancel()
        currentResponseJob = coroutineScope.launch {
            try {
                isGenerating = true
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

                    val timeoutLimitSeconds = activeConnection.timeoutLimit
                    val tokenChannel = Channel<String>(Channel.UNLIMITED)

                    val streamJob = launch {
                        try {
                            chatClient.streamChatRequest(
                                baseUrl = activeConnection.baseUrl ?: "",
                                apiKey = activeConnection.apiKey ?: "",
                                model = activeConnection.model ?: "gpt-3.5-turbo",
                                messages = messagesPayload,
                                isChatCompletion = activeConnection.isChatCompletion == 1L
                            ).collect { token ->
                                tokenChannel.send(token)
                            }
                        } catch (e: Exception) {
                            tokenChannel.close(e)
                            return@launch
                        }
                        tokenChannel.close()
                    }

                    try {
                        while (true) {
                            val token = if (timeoutLimitSeconds == 0L) {
                                tokenChannel.receiveCatching().getOrNull()
                            } else {
                                withTimeout(timeoutLimitSeconds * 1000L) {
                                    tokenChannel.receiveCatching().getOrNull()
                                }
                            } ?: break

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
                    } catch (_: TimeoutCancellationException) {
                        streamJob.cancel()
                        val errorSuffix = "\n\n[Error: Response timeout exceeded after ${timeoutLimitSeconds}s without token emissions.]"
                        val errorText = if (fullResponse.isBlank()) "Error: Response timeout exceeded after ${timeoutLimitSeconds}s without token emissions." else fullResponse + errorSuffix
                        messages = messages.map { msg ->
                            if (msg.id == aiMessageId) msg.copy(content = errorText) else msg
                        }
                        chatRepository.updateMessageContent(aiMessageId, errorText)
                        refreshMessages()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        streamJob.cancel()
                        refreshMessages()
                    } catch (e: Exception) {
                        streamJob.cancel()
                        val errorSuffix = "\n\n[Error: ${e.message}]"
                        val errorText = if (fullResponse.isBlank()) "Error: ${e.message}" else fullResponse + errorSuffix
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
            } finally {
                isGenerating = false
                currentResponseJob = null
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD32F2F),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                                Text("Delete", color = Color.White)
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
                    activePersonaName = activePersona?.name.orEmpty(),
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
                            activeSessionId?.let { sessionId ->
                                val activeTimeline = chatRepository.getMessagesForSession(sessionId)
                                val isCurrentActive = activeTimeline.any { it.id == id }

                                if (isCurrentActive) {
                                    val currentTimeline = chatRepository.getMessagesForSession(sessionId)
                                    val msg = currentTimeline.find { it.id == id }
                                    val parentId = msg?.parentId
                                    val siblings = chatRepository.getMessageSiblings(sessionId, parentId)
                                    val otherSibling = siblings.firstOrNull { it.id != id }
                                    if (otherSibling != null) {
                                        chatRepository.selectVariation(sessionId, otherSibling.id, parentId)
                                    } else {
                                        if (parentId != null) {
                                            chatRepository.updateSessionCurrentMessage(sessionId, parentId)
                                        }
                                    }
                                }
                            }
                            chatRepository.deleteMessage(id)
                            refreshMessages()
                        }
                    },
                    onDeleteMessages = { ids ->
                        coroutineScope.launch {
                            activeSessionId?.let { sessionId ->
                                val activeTimeline = chatRepository.getMessagesForSession(sessionId)
                                val activeDeleted = activeTimeline.find { it.id in ids }
                                if (activeDeleted != null) {
                                    val parentId = activeDeleted.parentId
                                    if (parentId != null) {
                                        val parentMsg = chatRepository.getMessagesForSession(sessionId).find { it.id == parentId }
                                        chatRepository.selectVariation(sessionId, parentId, parentMsg?.parentId)
                                    }
                                }
                            }
                            ids.forEach { chatRepository.deleteMessage(it) }
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
                    onEnterSelectMode = { isSelectMode = true; selectedMessageIds = emptySet() },
                    isGenerating = isGenerating,
                    onStopGeneration = { currentResponseJob?.cancel() },
                    onManageChats = { showChatManagerDialog = true },
                    onBranchMessage = { message ->
                        coroutineScope.launch {
                            activeSessionId?.let { sessionId ->
                                val currentSession = chatRepository.getSessionById(sessionId)
                                val baseTitle = currentSession?.title ?: "${activeCharacter?.name ?: "Chat"} #$sessionId"
                                val newTitle = "Branch of $baseTitle"

                                val newSessionId = chatRepository.branchSession(
                                    originalSessionId = sessionId,
                                    untilMessageId = message.id,
                                    messagesToCopy = messages,
                                    newTitle = newTitle
                                )
                                activeSessionId = newSessionId
                                refreshMessages()
                            }
                        }
                    },
                    onGoToParentChat = currentSessionDetails?.parentSessionId?.let { parentId ->
                        {
                            activeSessionId = parentId
                            refreshMessages()
                        }
                    }
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

        if (showChatManagerDialog && activeCharacter != null && activePersonaId != null) {
            ChatManagerDialog(
                characterId = activeCharacter!!.id,
                personaId = activePersonaId,
                activeSessionId = activeSessionId,
                chatRepository = chatRepository,
                onDismissRequest = { showChatManagerDialog = false },
                onSessionSelected = { id ->
                    if (id == -1L) {
                        activeSessionId = null
                    } else {
                        activeSessionId = id
                    }
                    showChatManagerDialog = false
                }
            )
        }
    }
}

@Composable
fun ChatManagerDialog(
    characterId: Long,
    personaId: Long,
    activeSessionId: Long?,
    chatRepository: ChatRepository,
    onDismissRequest: () -> Unit,
    onSessionSelected: (Long) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var expandedMenuSessionId by remember { mutableStateOf<Long?>(null) }
    var sessionToRename by remember { mutableStateOf<ChatSession?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }

    fun loadSessions() {
        coroutineScope.launch {
            sessions = chatRepository.getSessionsForCharacter(characterId)
        }
    }

    LaunchedEffect(characterId) {
        loadSessions()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved Chats", style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            val newSessionId = chatRepository.createNewSession(characterId, personaId)
                            onSessionSelected(newSessionId)
                        }
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Chat")
                }
            }
        },
        text = {
            Box(modifier = Modifier.sizeIn(maxHeight = 280.dp, minWidth = 280.dp)) {
                if (sessions.isEmpty()) {
                    Text(
                        "No alternative chats found.",
                        modifier = Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(sessions) { session ->
                            val isActive = session.id == activeSessionId
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSessionSelected(session.id)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = session.title ?: "#${session.id} ${formatTimestampToDateTime(session.lastTimestamp)}",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1
                                        )
                                        if (isActive) {
                                            Text(
                                                text = "Active Conversation",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Box {
                                        IconButton(onClick = { expandedMenuSessionId = session.id }) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Chat Options",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = expandedMenuSessionId == session.id,
                                            onDismissRequest = { expandedMenuSessionId = null }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    expandedMenuSessionId = null
                                                    sessionToRename = session
                                                    editTitleText = session.title ?: ""
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    expandedMenuSessionId = null
                                                    sessionToDelete = session
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )

    if (sessionToRename != null) {
        val targetSession = sessionToRename!!
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Chat") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = editTitleText,
                        onValueChange = { editTitleText = it },
                        label = { Text("Chat Title") },
                        placeholder = { Text("#${targetSession.id}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            chatRepository.updateSessionTitle(targetSession.id, editTitleText.ifBlank { null })
                            sessionToRename = null
                            loadSessions()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (sessionToDelete != null) {
        val targetSession = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("Are you sure you want to delete this chat session? All associated logs and swipe messages will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            chatRepository.deleteSession(targetSession.id)
                            if (targetSession.id == activeSessionId) {
                                val remaining = sessions.filter { it.id != targetSession.id }
                                if (remaining.isNotEmpty()) {
                                    onSessionSelected(remaining.first().id)
                                } else {
                                    onSessionSelected(-1L)
                                }
                            } else {
                                loadSessions()
                            }
                            sessionToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
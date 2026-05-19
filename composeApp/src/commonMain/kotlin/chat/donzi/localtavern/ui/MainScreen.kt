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
    val coroutineScope = rememberCoroutineScope()

    var editingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }

    // Retain a non-null reference to the character cache data to sustain the view tree safely while sliding out
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

    fun refreshMessages() {
        coroutineScope.launch {
            activeSessionId?.let { sessionId ->
                messages = chatRepository.getMessagesForSession(sessionId)
            } ?: run {
                messages = emptyList()
            }
        }
    }

    LaunchedEffect(activeCharacter, activePersonaId) {
        if (activePersonaId != null) {
            if (activeCharacter != null) {
                val sessionId = chatRepository.getOrCreateSession(activeCharacter!!.id, activePersonaId)
                activeSessionId = sessionId
                messages = chatRepository.getMessagesForSession(sessionId)
            } else {
                activeSessionId = null
                messages = emptyList()
            }
        } else {
            activeSessionId = null
            messages = emptyList()
        }
    }

    fun requestAiResponse(sessionId: Long) {
        coroutineScope.launch {
            val activeConnection = chatRepository.getActiveApiConnection()
            if (activeConnection != null) {
                val aiMessageId = chatRepository.insertMessage(sessionId, "assistant", "...")
                refreshMessages()

                var fullResponse = ""
                var receivedFirstToken = false

                // 1. Fetch entire history log from DB
                val dbMessages = chatRepository.getMessagesForSession(sessionId)

                // 2. Filter out the ongoing active placeholder message so it doesn't feed back into context
                val chatHistory = dbMessages
                    .filter { it.id != aiMessageId }
                    .map { ChatMessage(role = it.role, content = it.content) }

                // 3. Load active building blocks configuration
                val blocks = chatRepository.getAllPromptBlocks().map { it.toDomain() }

                // 4. Resolve the current persona entity
                val activePersona = personas.find { it.id == activePersonaId }

                // 5. Package the managed, budget-compliant context window sequence
                val messagesPayload = ContextManager.buildPayload(
                    blocks = blocks,
                    character = activeCharacter,
                    persona = activePersona,
                    chatHistory = chatHistory,
                    contextLimit = activeConnection.contextLimit,
                    responseLimit = activeConnection.responseLimit
                )

                chatClient.streamChatRequest(
                    baseUrl = activeConnection.baseUrl ?: "",
                    apiKey = activeConnection.apiKey ?: "",
                    model = activeConnection.model ?: "gpt-3.5-turbo",
                    messages = messagesPayload, // FIXED: Correctly passing token budget context payload list
                    isChatCompletion = activeConnection.isChatCompletion == 1L
                ).collect { token ->
                    if (!receivedFirstToken) {
                        receivedFirstToken = true
                        fullResponse = token
                    } else {
                        fullResponse += token
                    }

                    chatRepository.updateMessageContent(aiMessageId, fullResponse)
                    refreshMessages()
                }
            } else {
                val errorMsg = "No active API connection found. Please configure one in settings."
                chatRepository.insertMessage(sessionId, "assistant", errorMsg)
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

                chatRepository.insertMessage(sessionId, "user", userMessage)
                refreshMessages()

                requestAiResponse(sessionId)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(activeCharacter?.name ?: "LocalTavern")
                            if (activeCharacter != null) {
                                IconButton(onClick = { editingCharacter = activeCharacter }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit Character",
                                        modifier = Modifier.size(18.dp)
                                    )
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
                            IconButton(onClick = {
                                activeCharacter = null
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close Chat")
                            }
                        }
                        IconButton(onClick = { onActiveDrawerChange(ActiveDrawer.Characters) }) {
                            Icon(Icons.Filled.Person, contentDescription = "Characters")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                ChatArea(
                    activeCharacter = activeCharacter,
                    messages = messages,
                    onSendMessage = onSendMessage,
                    onEditMessage = { id, newContent ->
                        coroutineScope.launch {
                            chatRepository.updateMessageContent(id, newContent)
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
                            val userMsgToUse = if (lastMsg.role == "assistant") {
                                chatRepository.deleteMessage(lastMsg.id)
                                messages.dropLast(1).lastOrNull { it.role == "user" }
                            } else {
                                messages.lastOrNull { it.role == "user" }
                            }

                            if (userMsgToUse != null) {
                                requestAiResponse(activeSessionId!!)
                            } else {
                                refreshMessages()
                            }
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
            onCharacterSelect = { character ->
                activeCharacter = character
                onActiveDrawerChange(ActiveDrawer.None)
            },
            onCharactersDelete = { ids ->
                if (activeCharacter?.id in ids) {
                    activeCharacter = null
                }
                onCharactersDelete(ids)
            },
            onCharacterImport = onCharacterImport,
            onCharacterCreate = { name ->
                pendingCreationName = name
                onCharacterCreate(name)
                onActiveDrawerChange(ActiveDrawer.None)
            },
            onCharacterEdit = { character ->
                editingCharacter = character
                onActiveDrawerChange(ActiveDrawer.None)
            }
        )

        AnimatedVisibility(
            visible = editingCharacter != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            lastEditingCharacter?.let { targetCharacter ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CharacterDefinitionEditor(
                        character = targetCharacter,
                        onClose = { editingCharacter = null },
                        onSave = { name, desc, personality, scenario, firstMes, mesExample, altGreetings, avatarData ->
                            coroutineScope.launch {
                                // FIXED: Using safe named arguments matching the custom repository parameters order
                                chatRepository.updateCharacter(
                                    id = targetCharacter.id,
                                    name = name,
                                    personality = personality,
                                    scenario = scenario,
                                    description = desc,
                                    firstMes = firstMes,
                                    mesExample = mesExample,
                                    altGreetings = altGreetings,
                                    avatarData = avatarData
                                )
                                refreshData()
                                if (activeCharacter?.id == targetCharacter.id) {
                                    activeCharacter = chatRepository.getCharacterById(targetCharacter.id)
                                }
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                val idToDelete = targetCharacter.id
                                chatRepository.deleteCharacters(setOf(idToDelete))
                                if (activeCharacter?.id == idToDelete) {
                                    activeCharacter = null
                                }
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
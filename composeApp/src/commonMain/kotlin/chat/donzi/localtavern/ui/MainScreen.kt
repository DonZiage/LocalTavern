package chat.donzi.localtavern.ui

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

    fun refreshMessages() {
        coroutineScope.launch {
            activeSessionId?.let { sessionId ->
                messages = chatRepository.getMessagesForSession(sessionId)
            } ?: run {
                messages = emptyList() // Clear messages if no active session
            }
        }
    }

    // Load session and messages when activeCharacter or activePersonaId changes
    LaunchedEffect(activeCharacter, activePersonaId) {
        if (activePersonaId != null) {
            if (activeCharacter != null) { // Only load messages if a character is explicitly active
                val sessionId = chatRepository.getOrCreateSession(activeCharacter!!.id, activePersonaId)
                activeSessionId = sessionId
                messages = chatRepository.getMessagesForSession(sessionId)
            } else {
                // No character selected, so clear messages and session
                activeSessionId = null
                messages = emptyList()
            }
        } else {
            activeSessionId = null
            messages = emptyList()
        }
    }

    fun requestAiResponse(sessionId: Long, userPrompt: String) {
        coroutineScope.launch {
            val activeConnection = chatRepository.getActiveApiConnection()
            if (activeConnection != null) {
                val aiMessageId = chatRepository.insertMessage(sessionId, "assistant", "...")
                refreshMessages()

                var fullResponse = ""
                var receivedFirstToken = false

                chatClient.streamChatRequest(
                    baseUrl = activeConnection.baseUrl ?: "",
                    apiKey = activeConnection.apiKey ?: "",
                    model = activeConnection.model ?: "gpt-3.5-turbo",
                    prompt = userPrompt,
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
            var currentActiveCharacter = activeCharacter // Use a local var for the current send operation
            
            if (currentActiveCharacter == null) {
                // If no character is selected, default to the Assistant
                var assistant = chatRepository.getAssistant()
                if (assistant == null) {
                    chatRepository.createAssistant()
                    assistant = chatRepository.getAssistant()
                    refreshData() // Refresh character list to include new assistant
                }
                
                if (assistant != null) {
                    activeCharacter = assistant // Set activeCharacter to assistant for display
                    currentActiveCharacter = assistant
                }
            }

            if (currentActiveCharacter != null && activePersonaId != null) {
                val sessionId = chatRepository.getOrCreateSession(currentActiveCharacter.id, activePersonaId)
                activeSessionId = sessionId
                
                chatRepository.insertMessage(sessionId, "user", userMessage)
                refreshMessages() // Refresh messages to show user's message
                
                requestAiResponse(sessionId, userMessage)
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
                                requestAiResponse(activeSessionId!!, userMsgToUse.content)
                            } else {
                                // If no user message exists, maybe just prompt with current context?
                                // For now, we only regenerate if there's a user message to trigger from.
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
            // Settings
            chatRepository = chatRepository,
            chatClient = chatClient,
            isDarkMode = isDarkMode,
            onToggleDarkMode = onToggleDarkMode,
            // Personas
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onPersonaSelect,
            onPersonaAdd = onPersonaAdd,
            onPersonaUpdate = onPersonaUpdate,
            onPersonaDelete = onPersonaDelete,
            // Characters
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
            onCharacterCreate = onCharacterCreate,
            onCharacterEdit = { character ->
                editingCharacter = character
                onActiveDrawerChange(ActiveDrawer.None)
            }
        )

        if (editingCharacter != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                CharacterDefinitionEditor(
                    character = editingCharacter!!,
                    onClose = { editingCharacter = null },
                    onSave = { name, desc, personality, scenario, firstMes, systemPrompt, altGreetings, avatarData ->
                        coroutineScope.launch {
                            chatRepository.updateCharacter(
                                editingCharacter!!.id,
                                name,
                                personality,
                                scenario,
                                desc,
                                firstMes,
                                systemPrompt,
                                altGreetings,
                                avatarData
                            )
                            refreshData()
                            // Update active character reference if it's the one we edited
                            if (activeCharacter?.id == editingCharacter!!.id) {
                                activeCharacter = chatRepository.getCharacterById(editingCharacter!!.id)
                            }
                        }
                    },
                    onDelete = {
                        coroutineScope.launch {
                            val idToDelete = editingCharacter!!.id
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

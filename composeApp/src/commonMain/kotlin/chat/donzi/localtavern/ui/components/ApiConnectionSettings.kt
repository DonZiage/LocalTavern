package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.network.ChatClient
import kotlinx.coroutines.launch

@Composable
fun ApiConnectionSettings(
    chatRepository: ChatRepository,
    chatClient: ChatClient
) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ApiConnection>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<ApiConnection?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        connections = chatRepository.getAllApiConnections()
    }

    var activeConnection by remember { mutableStateOf<ApiConnection?>(null) }
    LaunchedEffect(refreshTrigger, connections) {
        activeConnection = chatRepository.getActiveApiConnection()
    }

    val activeIndex = remember(connections, activeConnection) {
        connections.indexOfFirst { it.id == activeConnection?.id }.coerceAtLeast(0)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        CardCarousel(
            title = "API Profiles",
            items = connections,
            key = { it.id },
            initialIndex = activeIndex,
            onReorder = { newList ->
                scope.launch {
                    chatRepository.updateApiConnectionDisplayOrders(newList.map { it.id })
                    refreshTrigger++
                }
            },
            onAddClick = { showAddDialog = true },
            onDelete = { connection ->
                scope.launch {
                    chatRepository.deleteApiConnection(connection.id)
                    refreshTrigger++
                }
            },
            itemContent = { connection, _, modifier, requestCenter, onDeleteRequest ->
                ApiConnectionItem(
                    connection = connection,
                    chatClient = chatClient,
                    onToggleActive = {
                        scope.launch {
                            chatRepository.setActiveApiConnection(connection.id)
                            refreshTrigger++
                        }
                    },
                    onToggleMode = { isChat ->
                        scope.launch {
                            chatRepository.updateApiConnection(
                                id = connection.id, provider = connection.provider, name = connection.name,
                                baseUrl = connection.baseUrl, apiKey = connection.apiKey, model = connection.model,
                                isActive = connection.isActive == 1L, isChatCompletion = isChat,
                                temperature = connection.temperature, topP = connection.topP, topK = connection.topK,
                                presencePenalty = connection.presencePenalty, frequencyPenalty = connection.frequencyPenalty,
                                contextLimit = connection.contextLimit, responseLimit = connection.responseLimit,
                                displayOrder = connection.displayOrder
                            )
                            refreshTrigger++
                        }
                    },
                    onDelete = onDeleteRequest,
                    onEdit = {
                        editingConnection = connection
                    },
                    onCenterRequest = requestCenter,
                    modifier = modifier
                )
            }
        )

        activeConnection?.let { currentActive ->
            Spacer(modifier = Modifier.height(24.dp))
            ParameterControls(
                connection = currentActive,
                onUpdate = { updated ->
                    scope.launch {
                        chatRepository.updateApiConnection(
                            id = updated.id,
                            provider = updated.provider,
                            name = updated.name,
                            baseUrl = updated.baseUrl,
                            apiKey = updated.apiKey,
                            model = updated.model,
                            isActive = updated.isActive == 1L,
                            isChatCompletion = updated.isChatCompletion == 1L,
                            temperature = updated.temperature,
                            topP = updated.topP,
                            topK = updated.topK,
                            presencePenalty = updated.presencePenalty,
                            frequencyPenalty = updated.frequencyPenalty,
                            contextLimit = updated.contextLimit,
                            responseLimit = updated.responseLimit,
                            displayOrder = updated.displayOrder
                        )
                        refreshTrigger++
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        ApiConnectionDialog(
            chatClient = chatClient,
            onDismiss = { showAddDialog = false },
            onSave = { provider, name, baseUrl, apiKey, model ->
                scope.launch {
                    chatRepository.insertApiConnection(
                        provider = provider, 
                        name = name, 
                        baseUrl = baseUrl, 
                        apiKey = apiKey, 
                        model = model,
                        isActive = connections.isEmpty(),
                        displayOrder = connections.size.toLong()
                    )
                    showAddDialog = false
                    refreshTrigger++
                }
            }
        )
    }

    if (editingConnection != null) {
        val connectionToEdit = editingConnection!!
        ApiConnectionDialog(
            chatClient = chatClient,
            initialConnection = connectionToEdit,
            onDismiss = { editingConnection = null },
            onSave = { provider, name, baseUrl, apiKey, model ->
                scope.launch {
                    chatRepository.updateApiConnection(
                        id = connectionToEdit.id,
                        provider = provider,
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey.ifBlank { connectionToEdit.apiKey ?: "" },
                        model = model,
                        isActive = connectionToEdit.isActive == 1L,
                        isChatCompletion = connectionToEdit.isChatCompletion == 1L,
                        temperature = connectionToEdit.temperature,
                        topP = connectionToEdit.topP,
                        topK = connectionToEdit.topK,
                        presencePenalty = connectionToEdit.presencePenalty,
                        frequencyPenalty = connectionToEdit.frequencyPenalty,
                        contextLimit = connectionToEdit.contextLimit,
                        responseLimit = connectionToEdit.responseLimit,
                        displayOrder = connectionToEdit.displayOrder
                    )
                    editingConnection = null
                    refreshTrigger++
                }
            }
        )
    }
}

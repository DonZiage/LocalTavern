package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.network.ChatClient
import kotlinx.coroutines.launch

@Composable
fun ApiConnectionSettings(
    apiSettingsRepository: ApiSettingsRepository,
    chatClient: ChatClient,
    onApiChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ApiConfig>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<ApiConfig?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        connections = apiSettingsRepository.getAllApiConnections()
    }

    var activeConnection by remember { mutableStateOf<ApiConfig?>(null) }
    LaunchedEffect(refreshTrigger, connections) {
        activeConnection = apiSettingsRepository.getActiveApiConnection()
    }

    val activeIndex = remember(connections, activeConnection) {
        connections.indexOfFirst { it.id == activeConnection?.id }.takeIf { it >= 0 }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        CardCarousel(
            title = "API Profiles",
            items = connections,
            key = { it.id },
            initialIndex = activeIndex,
            onReorder = { newList ->
                scope.launch {
                    apiSettingsRepository.updateApiConnectionDisplayOrders(newList.map { it.id })
                    refreshTrigger++
                    onApiChanged()
                }
            },
            onAddClick = { showAddDialog = true },
            onDelete = { connection ->
                scope.launch {
                    apiSettingsRepository.deleteApiConnection(connection.id)
                    refreshTrigger++
                    onApiChanged()
                }
            },
            itemContent = { connection, _, modifier, requestCenter, onDeleteRequest ->
                ApiConnectionItem(
                    connection = connection,
                    chatClient = chatClient,
                    onToggleActive = {
                        scope.launch {
                            apiSettingsRepository.setActiveApiConnection(connection.id)
                            refreshTrigger++
                            onApiChanged()
                        }
                    },
                    onToggleMode = { isChat ->
                        scope.launch {
                            apiSettingsRepository.updateApiConnection(
                                id = connection.id, provider = connection.provider, name = connection.name,
                                baseUrl = connection.baseUrl, apiKey = connection.apiKey, model = connection.model,
                                isActive = connection.isActive, isChatCompletion = isChat,
                                temperature = connection.temperature, topP = connection.topP, topK = connection.topK,
                                presencePenalty = connection.presencePenalty, frequencyPenalty = connection.frequencyPenalty,
                                contextLimit = connection.contextLimit, responseLimit = connection.responseLimit,
                                displayOrder = connection.displayOrder, timeoutLimit = connection.timeoutLimit
                            )
                            refreshTrigger++
                            onApiChanged()
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
                apiSettingsRepository = apiSettingsRepository,
                onUpdate = { _ ->
                    // ParameterControls persists the debounced write itself;
                    // this callback only refreshes the UI state afterwards.
                    refreshTrigger++
                    onApiChanged()
                }
            )
        }
    }

    if (showAddDialog) {
        ApiConnectionDialog(
            chatClient = chatClient,
            onDismiss = { showAddDialog = false },
            onSave = { provider, name, baseUrl, apiKey, model, isChatCompletion ->
                scope.launch {
                    // The repository derives the active flag and display order
                    // from fresh DB state, so a stale UI list cannot wrongly
                    // activate the new connection or collide on ordering.
                    apiSettingsRepository.insertApiConnection(
                        provider = provider,
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        isChatCompletion = isChatCompletion,
                        timeoutLimit = 60L
                    )
                    showAddDialog = false
                    refreshTrigger++
                    onApiChanged()
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
            onSave = { provider, name, baseUrl, apiKey, model, isChatCompletion ->
                scope.launch {
                    apiSettingsRepository.updateApiConnection(
                        id = connectionToEdit.id,
                        provider = provider,
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey.ifBlank { connectionToEdit.apiKey ?: "" },
                        model = model,
                        isActive = connectionToEdit.isActive,
                        isChatCompletion = isChatCompletion,
                        temperature = connectionToEdit.temperature,
                        topP = connectionToEdit.topP,
                        topK = connectionToEdit.topK,
                        presencePenalty = connectionToEdit.presencePenalty,
                        frequencyPenalty = connectionToEdit.frequencyPenalty,
                        contextLimit = connectionToEdit.contextLimit,
                        responseLimit = connectionToEdit.responseLimit,
                        displayOrder = connectionToEdit.displayOrder,
                        timeoutLimit = connectionToEdit.timeoutLimit
                    )
                    editingConnection = null
                    refreshTrigger++
                    onApiChanged()
                }
            }
        )
    }
}
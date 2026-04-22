package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.repository
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionSettings() {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ApiConnection>>(emptyList()) }
    val reorderableConnections = remember { mutableStateListOf<ApiConnection>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<ApiConnection?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val chatClient = remember {
        ChatClient(HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        })
    }

    LaunchedEffect(refreshTrigger) {
        val list: List<ApiConnection> = repository?.getAllApiConnections() ?: emptyList()
        connections = list
        reorderableConnections.clear()
        reorderableConnections.addAll(list)
    }

    var activeConnection by remember { mutableStateOf<ApiConnection?>(null) }
    LaunchedEffect(refreshTrigger) {
        activeConnection = repository?.getActiveApiConnection()
    }

    val listState = rememberLazyListState()
    var isCentering by remember { mutableStateOf(false) }

    // Reordering state
    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    var dragDisplacement by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "API Profiles", 
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val constraints = this
            val itemWidthDp = (constraints.maxWidth * 0.85f).coerceIn(200.dp, 400.dp)
            val spacingDp = 12.dp
            val horizontalPadding = (constraints.maxWidth - itemWidthDp) / 2
            
            val density = LocalDensity.current
            val itemWidthPx = with(density) { itemWidthDp.toPx() }
            val spacingPx = with(density) { spacingDp.toPx() }

            val snapToCenter = {
                if (!isCentering && draggedItemId == null) {
                    val layoutInfo = listState.layoutInfo
                    if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        val closestItem = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                            val itemCenter = item.offset + (item.size / 2)
                            kotlin.math.abs(itemCenter - layoutInfo.viewportSize.width / 2)
                        }
                        closestItem?.let { item ->
                            val itemCenter = item.offset + (item.size / 2)
                            val distance = kotlin.math.abs(itemCenter - layoutInfo.viewportSize.width / 2)
                            if (distance > 5) {
                                scope.launch {
                                    try {
                                        isCentering = true
                                        listState.animateScrollToItem(item.index, 0)
                                    } finally {
                                        isCentering = false
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LaunchedEffect(listState.isScrollInProgress) {
                if (!listState.isScrollInProgress && !isCentering && draggedItemId == null) {
                    delay(150.milliseconds)
                    snapToCenter()
                }
            }

            var lastActiveId by remember { mutableStateOf<Long?>(null) }
            val currentActive = activeConnection
            LaunchedEffect(currentActive?.id) {
                if (currentActive != null && currentActive.id != lastActiveId && draggedItemId == null) {
                    val index = connections.indexOfFirst { it.id == currentActive.id }
                    if (index != -1) {
                        scope.launch {
                            try {
                                isCentering = true
                                listState.animateScrollToItem(index, 0)
                            } finally {
                                isCentering = false
                            }
                        }
                    }
                    lastActiveId = currentActive.id
                }
            }

            LazyRow(
                state = listState,
                userScrollEnabled = !isCentering && draggedItemId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.first().scrollDelta.y
                        event.changes.forEach { it.consume() }
                        if (!isCentering && delta != 0f && draggedItemId == null) {
                            scope.launch {
                                listState.scrollBy(delta * 60f)
                                delay(350.milliseconds)
                                if (!listState.isScrollInProgress) snapToCenter()
                            }
                        }
                    }
                    // Enable drag to scroll on desktop with mouse
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                if (draggedItemId == null) {
                                    change.consume()
                                    scope.launch { listState.scrollBy(-dragAmount.x) }
                                }
                            },
                            onDragEnd = { if (draggedItemId == null) snapToCenter() }
                        )
                    },
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(spacingDp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(reorderableConnections, key = { _, connection -> connection.id }) { index, connection ->
                    val isDragging = connection.id == draggedItemId
                    val scale by animateFloatAsState(if (isDragging) 1.05f else 1f)
                    
                    // Capture current values in state to avoid stale closure issues during reordering
                    val currentItemWidthPx by rememberUpdatedState(itemWidthPx)
                    val currentSpacingPx by rememberUpdatedState(spacingPx)

                    ApiConnectionItem(
                        connection = connection,
                        chatClient = chatClient,
                        onToggleActive = {
                            scope.launch {
                                repository?.setActiveApiConnection(connection.id)
                                refreshTrigger++
                            }
                        },
                        onToggleMode = { isChat ->
                            scope.launch {
                                repository?.updateApiConnection(
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
                        onDelete = {
                            scope.launch {
                                repository?.deleteApiConnection(connection.id)
                                refreshTrigger++
                            }
                        },
                        onEdit = {
                            editingConnection = connection
                        },
                        onCenterRequest = {
                            scope.launch {
                                try {
                                    isCentering = true
                                    listState.animateScrollToItem(index, 0)
                                } finally {
                                    isCentering = false
                                }
                            }
                        },
                        modifier = Modifier
                            .width(itemWidthDp)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragDisplacement else 0f
                                scaleX = scale
                                scaleY = scale
                                alpha = if (isDragging) 0.8f else 1f
                            }
                            .pointerInput(connection.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { 
                                        draggedItemId = connection.id
                                        dragDisplacement = 0f
                                    },
                                    onDragEnd = {
                                        draggedItemId = null
                                        dragDisplacement = 0f
                                        scope.launch {
                                            repository?.updateApiConnectionDisplayOrders(reorderableConnections.map { it.id })
                                            refreshTrigger++
                                        }
                                    },
                                    onDragCancel = {
                                        draggedItemId = null
                                        dragDisplacement = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragDisplacement += dragAmount.x
                                        
                                        val currentDraggingIndex = reorderableConnections.indexOfFirst { it.id == connection.id }
                                        if (currentDraggingIndex != -1) {
                                            val targetIndex = (currentDraggingIndex + (dragDisplacement / (currentItemWidthPx + currentSpacingPx)).roundToInt())
                                                .coerceIn(0, reorderableConnections.size - 1)
                                            
                                            if (targetIndex != currentDraggingIndex) {
                                                reorderableConnections.add(targetIndex, reorderableConnections.removeAt(currentDraggingIndex))
                                                dragDisplacement = 0f
                                            }
                                        }
                                    }
                                )
                            }
                    )
                }

                item(key = "add_new_card") {
                    val addIndex = reorderableConnections.size
                    val cardShape = RoundedCornerShape(12.dp)
                    OutlinedCard(
                        onClick = {
                            showAddDialog = true
                            scope.launch {
                                try {
                                    isCentering = true
                                    listState.animateScrollToItem(addIndex, 0)
                                } finally {
                                    isCentering = false
                                }
                            }
                        },
                        modifier = Modifier
                            .width(itemWidthDp)
                            .height(115.dp)
                            .clip(cardShape),
                        shape = cardShape,
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add Connection",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "Add New",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        activeConnection?.let { currentActive: ApiConnection ->
            Spacer(modifier = Modifier.height(24.dp))
            ParameterControls(
                connection = currentActive,
                onUpdate = { updatedConnection: ApiConnection ->
                    scope.launch {
                        repository?.updateApiConnection(
                            id = updatedConnection.id,
                            provider = updatedConnection.provider,
                            name = updatedConnection.name,
                            baseUrl = updatedConnection.baseUrl,
                            apiKey = updatedConnection.apiKey,
                            model = updatedConnection.model,
                            isActive = updatedConnection.isActive == 1L,
                            isChatCompletion = updatedConnection.isChatCompletion == 1L,
                            temperature = updatedConnection.temperature,
                            topP = updatedConnection.topP,
                            topK = updatedConnection.topK,
                            presencePenalty = updatedConnection.presencePenalty,
                            frequencyPenalty = updatedConnection.frequencyPenalty,
                            contextLimit = updatedConnection.contextLimit,
                            responseLimit = updatedConnection.responseLimit,
                            displayOrder = updatedConnection.displayOrder
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
                    val isFirst = connections.isEmpty()
                    repository?.insertApiConnection(
                        provider = provider, 
                        name = name, 
                        baseUrl = baseUrl, 
                        apiKey = apiKey, 
                        model = model,
                        isActive = isFirst,
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
                    val finalApiKey = apiKey.ifBlank { connectionToEdit.apiKey ?: "" }
                    repository?.updateApiConnection(
                        id = connectionToEdit.id,
                        provider = provider,
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = finalApiKey,
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

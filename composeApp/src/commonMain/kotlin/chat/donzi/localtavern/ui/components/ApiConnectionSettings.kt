package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import chat.donzi.localtavern.database.ApiConnection
import chat.donzi.localtavern.network.ChatClient
import chat.donzi.localtavern.network.ModelInfo
import chat.donzi.localtavern.repository
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ApiConnectionSettings() {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(emptyList<ApiConnection>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val chatClient = remember {
        ChatClient(HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        })
    }

    LaunchedEffect(refreshTrigger) {
        connections = repository?.getAllApiConnections() ?: emptyList()
    }

    val activeConnection = connections.find { it.isActive == 1L }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "API Profiles", 
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        val listState = rememberLazyListState()
        
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .onPointerEvent(PointerEventType.Scroll) {
                    val delta = it.changes.first().scrollDelta.y
                    scope.launch {
                        listState.scrollBy(delta * 50f)
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            listState.scrollBy(-delta)
                        }
                    }
                ),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(connections, key = { it.id }) { connection ->
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
                                id = connection.id,
                                provider = connection.provider,
                                name = connection.name,
                                baseUrl = connection.baseUrl,
                                apiKey = connection.apiKey,
                                model = connection.model,
                                isActive = connection.isActive == 1L,
                                isChatCompletion = isChat,
                                temperature = connection.temperature,
                                topP = connection.topP,
                                topK = connection.topK,
                                presencePenalty = connection.presencePenalty,
                                frequencyPenalty = connection.frequencyPenalty,
                                contextLimit = connection.contextLimit,
                                responseLimit = connection.responseLimit
                            )
                            refreshTrigger++
                        }
                    },
                    onDelete = {
                        scope.launch {
                            repository?.deleteApiConnection(connection.id)
                            refreshTrigger++
                        }
                    }
                )
            }
            
            item {
                OutlinedCard(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .width(100.dp)
                        .height(100.dp),
                    shape = RoundedCornerShape(12.dp),
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
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (activeConnection != null) {
            Spacer(modifier = Modifier.height(24.dp))
            ParameterControls(
                connection = activeConnection,
                onUpdate = { updatedConnection ->
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
                            responseLimit = updatedConnection.responseLimit
                        )
                        refreshTrigger++
                    }
                }
            )
        }
    }

    if (showAddDialog) {
        AddApiConnectionDialog(
            chatClient = chatClient,
            onDismiss = { showAddDialog = false },
            onSave = { provider, name, baseUrl, apiKey, model ->
                scope.launch {
                    repository?.insertApiConnection(provider, name, baseUrl, apiKey, model)
                    showAddDialog = false
                    refreshTrigger++
                }
            }
        )
    }
}

@Composable
fun ParameterControls(
    connection: ApiConnection,
    onUpdate: (ApiConnection) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text("Generation Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        ContextLimitSlider(
            currentLimit = connection.contextLimit,
            onValueChange = { onUpdate(connection.copy(contextLimit = it)) }
        )

        ParameterSlider(
            label = "Response Limit",
            value = connection.responseLimit.toFloat(),
            range = 0f..4096f,
            steps = 63,
            format = { if (it < 1f) "Unlimited" else it.toInt().toString() },
            onValueChange = { onUpdate(connection.copy(responseLimit = it.toLong())) }
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.alpha(0.3f))
        Spacer(modifier = Modifier.height(8.dp))

        ParameterSlider(
            label = "Temperature",
            value = connection.temperature.toFloat(),
            range = 0f..2f,
            steps = 20,
            onValueChange = { onUpdate(connection.copy(temperature = it.toDouble())) }
        )

        ParameterSlider(
            label = "Top-P",
            value = connection.topP.toFloat(),
            range = 0f..1f,
            steps = 10,
            onValueChange = { onUpdate(connection.copy(topP = it.toDouble())) }
        )

        ParameterSlider(
            label = "Top-K",
            value = connection.topK.toFloat(),
            range = 0f..100f,
            steps = 100,
            format = { it.toInt().toString() },
            onValueChange = { onUpdate(connection.copy(topK = it.toLong())) }
        )

        ParameterSlider(
            label = "Presence Penalty",
            value = connection.presencePenalty.toFloat(),
            range = -2f..2f,
            steps = 40,
            onValueChange = { onUpdate(connection.copy(presencePenalty = it.toDouble())) }
        )

        ParameterSlider(
            label = "Frequency Penalty",
            value = connection.frequencyPenalty.toFloat(),
            range = -2f..2f,
            steps = 40,
            onValueChange = { onUpdate(connection.copy(frequencyPenalty = it.toDouble())) }
        )
    }
}

@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) },
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    var textValue by remember(value) { mutableStateOf(format(value)) }
    var isEditing by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            
            if (isEditing) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .width(60.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                val parsed = textValue.toFloatOrNull()
                                if (parsed != null) {
                                    val clamped = parsed.coerceIn(range.start, range.endInclusive)
                                    onValueChange(clamped)
                                }
                                isEditing = false
                                true
                            } else false
                        },
                    textStyle = TextStyle(
                        textAlign = TextAlign.End, 
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            } else {
                Text(
                    format(sliderValue), 
                    style = MaterialTheme.typography.labelMedium, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { isEditing = true }
                )
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { 
                sliderValue = it
                textValue = format(it)
            },
            onValueChangeFinished = { onValueChange(sliderValue) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
fun ContextLimitSlider(
    currentLimit: Long,
    onValueChange: (Long) -> Unit
) {
    val presets = remember { listOf(2048L, 4096L, 8192L, 16384L, 32768L, 65536L, 0L) }
    val labels = remember {
        mapOf(
            2048L to "2k",
            4096L to "4k",
            8192L to "8k",
            16384L to "16k",
            32768L to "32k",
            65536L to "64k",
            0L to "Unlimited"
        )
    }

    var sliderValue by remember(currentLimit) { 
        val idx = presets.indexOf(currentLimit).coerceAtLeast(0)
        mutableFloatStateOf(idx.toFloat())
    }
    
    var isEditing by remember { mutableStateOf(false) }
    var textValue by remember(currentLimit) { mutableStateOf(currentLimit.toString()) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Context Limit", style = MaterialTheme.typography.labelMedium)
            
            if (isEditing) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .width(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                val parsed = textValue.toLongOrNull()
                                if (parsed != null) {
                                    onValueChange(parsed)
                                }
                                isEditing = false
                                true
                            } else false
                        },
                    textStyle = TextStyle(
                        textAlign = TextAlign.End, 
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            } else {
                Text(
                    labels[presets[sliderValue.toInt().coerceIn(0, presets.size - 1)]] ?: "Custom (${currentLimit})", 
                    style = MaterialTheme.typography.labelMedium, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { isEditing = true }
                )
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { 
                val finalIdx = sliderValue.toInt().coerceIn(0, presets.size - 1)
                onValueChange(presets[finalIdx]) 
            },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2
        )
    }
}

@Composable
fun ApiConnectionItem(
    connection: ApiConnection,
    chatClient: ChatClient,
    onToggleActive: () -> Unit,
    onToggleMode: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var status by remember { mutableStateOf<Boolean?>(null) }
    
    LaunchedEffect(connection.baseUrl, connection.apiKey) {
        if (!connection.baseUrl.isNullOrBlank() && !connection.apiKey.isNullOrBlank()) {
            status = chatClient.checkStatus(connection.baseUrl, connection.apiKey)
        }
    }

    Card(
        modifier = Modifier
            .width(220.dp)
            .height(100.dp)
            .clickable { onToggleActive() },
        colors = CardDefaults.cardColors(
            containerColor = if (connection.isActive == 1L) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(status, modifier = Modifier.size(20.dp))
                
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        connection.name, 
                        style = MaterialTheme.typography.labelLarge, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        connection.provider, 
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete", 
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
            
            Text(
                "Model: ${connection.model ?: "None"}", 
                style = MaterialTheme.typography.labelSmall, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp)
            )

            if (status == true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chat", style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = connection.isChatCompletion == 1L,
                        onCheckedChange = { onToggleMode(it) },
                        modifier = Modifier.padding(horizontal = 8.dp).scale(0.7f)
                    )
                    Text("Text", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(isAlive: Boolean?, modifier: Modifier = Modifier) {
    val color = when (isAlive) {
        true -> Color(0xFF1B5E20) // Darker Green
        false -> Color(0xFFB71C1C) // Darker Red
        null -> Color(0xFF616161) // Darker Grey
    }
    Box(
        modifier = modifier
            .background(color, shape = CircleShape)
    )
}

fun String.fuzzyScore(query: String): Int {
    if (query.isBlank()) return 100
    val target = this.lowercase()
    val q = query.lowercase()
    
    if (target == q) return 1000
    if (target.startsWith(q)) return 500
    if (target.contains(q)) return 200
    
    val cleanTarget = target.filter { it.isLetterOrDigit() }
    val cleanQ = q.filter { it.isLetterOrDigit() }
    if (cleanTarget.contains(cleanQ)) return 150

    var score = 0
    var qIdx = 0
    for (char in cleanTarget) {
        if (qIdx < cleanQ.length && char == cleanQ[qIdx]) {
            score += 10
            qIdx++
        }
    }
    return if (qIdx == cleanQ.length) score else 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddApiConnectionDialog(
    chatClient: ChatClient,
    onDismiss: () -> Unit,
    onSave: (provider: String, name: String, baseUrl: String, apiKey: String, model: String) -> Unit
) {
    val providers = listOf(
        "OpenAI", "xAI", "Gemini", "Anthropic", "Mistral", "DeepSeek", "AI21",
        "Cohere", "Perplexity", "Fireworks AI", "OpenRouter", "TogetherAI",
        "NovelAI", "Mancer", "DreamGen"
    )
    
    val defaultUrls = mapOf(
        "OpenAI" to "https://api.openai.com/v1",
        "Anthropic" to "https://api.anthropic.com/v1",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "DeepSeek" to "https://api.deepseek.com",
        "TogetherAI" to "https://api.together.xyz/v1",
        "Mistral" to "https://api.mistral.ai/v1",
        "xAI" to "https://api.x.ai/v1",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
        "AI21" to "https://api.ai21.com/studio/v1",
        "Cohere" to "https://api.cohere.ai/v1",
        "Perplexity" to "https://api.perplexity.ai",
        "Fireworks AI" to "https://api.fireworks.ai/inference/v1",
        "NovelAI" to "https://api.novelai.net/v1",
        "Mancer" to "https://api.mancer.tech/v1",
        "DreamGen" to "https://dreamgen.com/api/v1"
    )

    var selectedProvider by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    
    var allModels by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var isKeyValid by remember { mutableStateOf(false) }
    
    var modelSearch by remember { mutableStateOf("") }
    var selectedModelFullId by remember { mutableStateOf("") }
    var modelProviderFilter by remember { mutableStateOf("") }
    
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val currentBaseUrl = defaultUrls[selectedProvider] ?: ""

    LaunchedEffect(apiKey, selectedProvider) {
        if (apiKey.length > 5 && currentBaseUrl.isNotBlank()) {
            isLoadingModels = true
            isKeyValid = chatClient.checkStatus(currentBaseUrl, apiKey)
            allModels = if (isKeyValid) {
                chatClient.fetchModels(currentBaseUrl, apiKey)
            } else {
                emptyList()
            }
            isLoadingModels = false
        } else {
            isKeyValid = false
            allModels = emptyList()
        }
    }

    // Reset model selection if provider filter changes and doesn't match
    LaunchedEffect(modelProviderFilter) {
        if (selectedModelFullId.isNotEmpty()) {
            val currentModel = allModels.find { it.id == selectedModelFullId }
            if (currentModel != null) {
                // If filter is not "All" and doesn't match current model's provider
                if (modelProviderFilter.isNotEmpty() && modelProviderFilter != currentModel.provider) {
                    selectedModelFullId = ""
                    modelSearch = ""
                }
            }
        }
    }

    val uniqueModelProviders = remember(allModels) {
        allModels.map { it.provider }.distinct().sorted()
    }

    val providerSuggestions = remember(uniqueModelProviders, modelProviderFilter) {
        if (modelProviderFilter.isEmpty()) {
            uniqueModelProviders
        } else {
            uniqueModelProviders
                .map { it to it.fuzzyScore(modelProviderFilter) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }

    val filteredModels = remember(allModels, modelSearch, modelProviderFilter) {
        allModels.asSequence()
            .map { model ->
                val idScore = model.id.fuzzyScore(modelSearch)
                val nameScore = model.displayName.fuzzyScore(modelSearch)
                var finalScore = maxOf(idScore, nameScore)
                
                val matchesFilter = modelProviderFilter.isEmpty() || model.provider == modelProviderFilter
                
                if (modelSearch.isNotEmpty()) {
                    if (matchesFilter) finalScore += 50
                } else {
                    if (!matchesFilter) finalScore = 0
                }
                
                model to finalScore
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(50)
            .toList()
    }

    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Setup API Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var mainProviderExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = mainProviderExpanded,
                    onExpandedChange = { mainProviderExpanded = !mainProviderExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.ifEmpty { "Select Provider" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("1. Select Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mainProviderExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = mainProviderExpanded, onDismissRequest = { mainProviderExpanded = false }) {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider) },
                                onClick = {
                                    selectedProvider = provider
                                    name = "$provider Connection"
                                    mainProviderExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedProvider.isNotEmpty()) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("2. Enter API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = apiKey.isNotEmpty() && !isKeyValid && !isLoadingModels,
                        trailingIcon = {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    )
                }

                if (isKeyValid) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (selectedModelFullId.isEmpty()) "3. Select Model (Required)" else "3. Model Selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedModelFullId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        
                        // Model Provider List
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val interactionSource = remember { MutableInteractionSource() }
                            LaunchedEffect(interactionSource) {
                                interactionSource.interactions.collectLatest { interaction ->
                                    if (interaction is PressInteraction.Release) providerDropdownExpanded = true
                                }
                            }
                            
                            OutlinedTextField(
                                value = modelProviderFilter,
                                onValueChange = { 
                                    modelProviderFilter = it
                                    providerDropdownExpanded = true
                                },
                                label = { Text("Model Provider") },
                                placeholder = { Text("All Providers") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Tab)) {
                                            if (providerDropdownExpanded && providerSuggestions.isNotEmpty()) {
                                                modelProviderFilter = providerSuggestions.first()
                                                providerDropdownExpanded = false
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    },
                                interactionSource = interactionSource,
                                trailingIcon = {
                                    if (modelProviderFilter.isNotEmpty()) {
                                        IconButton(onClick = { modelProviderFilter = "" }) {
                                            Icon(Icons.Default.Clear, "Clear filter")
                                        }
                                    }
                                }
                            )
                            
                            DropdownMenu(
                                expanded = providerDropdownExpanded && (providerSuggestions.isNotEmpty() || modelProviderFilter.isEmpty()),
                                onDismissRequest = { providerDropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 200.dp),
                                properties = PopupProperties(focusable = false)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Providers") },
                                    onClick = {
                                        modelProviderFilter = ""
                                        providerDropdownExpanded = false
                                    }
                                )
                                providerSuggestions.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider) },
                                        onClick = {
                                            modelProviderFilter = provider
                                            providerDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Model Search & List
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val interactionSource = remember { MutableInteractionSource() }
                            LaunchedEffect(interactionSource) {
                                interactionSource.interactions.collectLatest { interaction ->
                                    if (interaction is PressInteraction.Release) modelDropdownExpanded = true
                                }
                            }

                            OutlinedTextField(
                                value = modelSearch,
                                onValueChange = { 
                                    modelSearch = it
                                    modelDropdownExpanded = true
                                },
                                label = { Text("Model Name") },
                                trailingIcon = { 
                                    IconButton(onClick = { modelDropdownExpanded = !modelDropdownExpanded }) {
                                        Icon(Icons.Default.Search, null)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Tab)) {
                                            if (modelDropdownExpanded && filteredModels.isNotEmpty()) {
                                                val model = filteredModels.first()
                                                selectedModelFullId = model.id
                                                modelSearch = model.displayName
                                                modelProviderFilter = model.provider
                                                modelDropdownExpanded = false
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    },
                                isError = selectedModelFullId.isEmpty(),
                                interactionSource = interactionSource
                            )
                            DropdownMenu(
                                expanded = modelDropdownExpanded && filteredModels.isNotEmpty(),
                                onDismissRequest = { modelDropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 250.dp),
                                properties = PopupProperties(focusable = false)
                            ) {
                                filteredModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { 
                                            Column {
                                                Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                                                Text("${model.provider} | ${model.id}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                            }
                                        },
                                        onClick = {
                                            selectedModelFullId = model.id
                                            modelSearch = model.displayName
                                            modelProviderFilter = model.provider
                                            modelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("4. Connection Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedProvider, name, currentBaseUrl, apiKey, selectedModelFullId) },
                enabled = isKeyValid && selectedModelFullId.isNotBlank() && name.isNotBlank()
            ) {
                Text("Complete Setup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
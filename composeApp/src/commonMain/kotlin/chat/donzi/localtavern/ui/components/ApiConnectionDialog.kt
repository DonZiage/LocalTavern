package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.network.ModelInfo
import chat.donzi.localtavern.utils.fuzzyScore
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionDialog(
    chatClient: ChatClient,
    initialConnection: ApiConnection? = null,
    onDismiss: () -> Unit,
    onSave: (provider: String, name: String, baseUrl: String, apiKey: String, model: String) -> Unit
) {
    val providers = remember {
        listOf(
            "OpenAI", "xAI", "Gemini", "Anthropic", "Mistral", "DeepSeek", "AI21",
            "Cohere", "Perplexity", "Fireworks AI", "OpenRouter", "TogetherAI",
            "NovelAI", "Mancer", "DreamGen"
        )
    }
    
    val defaultUrls = remember {
        mapOf(
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
    }

    var selectedProvider by remember { mutableStateOf(initialConnection?.provider ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialConnection?.name ?: "") }
    
    val maskedApiKey = remember(initialConnection?.apiKey) {
        val key = initialConnection?.apiKey ?: ""
        if (key.length >= 6) {
            "${key.take(2)}-••••${key.takeLast(4)}"
        } else if (key.isNotEmpty()) {
            "••••"
        } else {
            ""
        }
    }

    var allModels by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var isKeyValid by remember { mutableStateOf(false) }
    
    var modelSearch by remember { mutableStateOf(initialConnection?.model ?: "") }
    var selectedModelFullId by remember { mutableStateOf(initialConnection?.model ?: "") }
    var modelProviderFilter by remember { mutableStateOf("") }
    
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val currentBaseUrl = remember(selectedProvider) { defaultUrls[selectedProvider] ?: "" }

    LaunchedEffect(apiKey, selectedProvider) {
        val keyToTest = apiKey.ifBlank { initialConnection?.apiKey ?: "" }
        if (keyToTest.length > 5 && currentBaseUrl.isNotBlank()) {
            isLoadingModels = true
            isKeyValid = chatClient.checkStatus(currentBaseUrl, keyToTest)
            allModels = if (isKeyValid) {
                chatClient.fetchModels(currentBaseUrl, keyToTest)
            } else {
                emptyList()
            }
            isLoadingModels = false
        } else {
            isKeyValid = false
            allModels = emptyList()
        }
    }

    // Load initial model's provider into filter when models are loaded
    LaunchedEffect(allModels) {
        if (initialConnection != null && modelProviderFilter.isEmpty() && selectedModelFullId.isNotEmpty()) {
            val currentModel = allModels.find { it.id == selectedModelFullId }
            if (currentModel != null) {
                modelProviderFilter = currentModel.provider
            }
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
        title = { Text(if (initialConnection == null) "Setup API Connection" else "Edit API Connection") },
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
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = mainProviderExpanded, onDismissRequest = { mainProviderExpanded = false }) {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider) },
                                onClick = {
                                    selectedProvider = provider
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
                        label = { Text(if (initialConnection == null) "2. Enter API Key" else "2. Update API Key") },
                        placeholder = { Text(maskedApiKey) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = apiKey.isNotEmpty() && !isKeyValid && !isLoadingModels,
                        trailingIcon = {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        },
                        singleLine = true
                    )
                }

                if (isKeyValid || selectedModelFullId.isNotEmpty()) {
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
                                },
                                singleLine = true
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
                                interactionSource = interactionSource,
                                singleLine = true
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
                            label = { Text("4. Profile Name (Optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    onSave(selectedProvider, name, currentBaseUrl, apiKey, selectedModelFullId)
                },
                enabled = (isKeyValid || (initialConnection != null && selectedModelFullId.isNotEmpty())) && selectedModelFullId.isNotBlank()
            ) {
                Text(if (initialConnection == null) "Complete Setup" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

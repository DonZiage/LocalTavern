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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    onSave: (provider: String, name: String, baseUrl: String, apiKey: String, model: String, isChatCompletion: Boolean) -> Unit
) {
    val cloudInferenceProviders = remember {
        listOf(
            "AI21", "Anthropic", "Cohere", "DeepSeek", "DreamGen", "Fireworks AI", "Gemini",
            "Mancer", "Mistral", "NovelAI", "OpenAI", "OpenRouter", "Perplexity",
            "TogetherAI", "xAI"
        )
    }

    val localInferenceProviders = remember {
        listOf(
            "LM Studio", "KoboldCPP", "TabbyAPI", "Oobabooga", "Ollama", "llama.cpp", "vLLM", "OAI-Compatible"
        )
    }

    val providerSections = remember {
        listOf(
            "Cloud Inference" to cloudInferenceProviders,
            "Local Inference" to localInferenceProviders
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
            "DreamGen" to "https://dreamgen.com/api/v1",
            "LM Studio" to "http://localhost:1234/v1",
            "KoboldCPP" to "http://localhost:5001/v1",
            "Oobabooga" to "http://localhost:5000/v1",
            "Ollama" to "http://localhost:11434/v1",
            "TabbyAPI" to "http://localhost:5000/v1",
            "llama.cpp" to "http://localhost:8080/v1",
            "vLLM" to "http://localhost:8000/v1"
        )
    }

    var selectedProvider by remember { mutableStateOf(initialConnection?.provider ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialConnection?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initialConnection?.baseUrl ?: "") }
    
    val isCloudInference = remember(selectedProvider) {
        cloudInferenceProviders.contains(selectedProvider)
    }

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

    LaunchedEffect(selectedProvider) {
        val newDefaultUrl = defaultUrls[selectedProvider]
        if (newDefaultUrl != null) {
            baseUrl = newDefaultUrl
        } else {
            if (initialConnection == null) {
                baseUrl = ""
            }
        }
    }

    LaunchedEffect(apiKey, baseUrl) {
        val keyToTest = apiKey.ifBlank { initialConnection?.apiKey ?: "" }
        val effectiveBaseUrl = if (isCloudInference) defaultUrls[selectedProvider] ?: baseUrl else baseUrl
        
        if (selectedProvider.isNotEmpty() && (keyToTest.length > 5 || !isCloudInference) && effectiveBaseUrl.isNotBlank()) {
            isLoadingModels = true
            isKeyValid = chatClient.checkStatus(effectiveBaseUrl, keyToTest)
            allModels = if (isKeyValid) {
                chatClient.fetchModels(effectiveBaseUrl, keyToTest)
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
                    ExposedDropdownMenu(
                        expanded = mainProviderExpanded,
                        onDismissRequest = { mainProviderExpanded = false },
                        modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 240.dp)
                    ) {
                        providerSections.forEach { (sectionName, providersInSection) ->
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        sectionName, 
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) 
                                },
                                enabled = false,
                                onClick = {}
                            )
                            providersInSection.forEach { provider ->
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
                }

                if (selectedProvider.isNotEmpty()) {
                    if (!isCloudInference) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("2. Enter Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = baseUrl.isBlank()
                        )
                    }

                    val labelStep = if (isCloudInference) "2" else "3"
                    val optionalText = if (!isCloudInference) " (Optional)" else ""
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("$labelStep. ${if (initialConnection == null) "Enter API Key$optionalText" else "Update API Key"}") },
                        placeholder = { Text(maskedApiKey) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = isCloudInference && apiKey.isNotEmpty() && !isKeyValid && !isLoadingModels,
                        trailingIcon = {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        },
                        singleLine = true
                    )

                    if (isKeyValid || !isCloudInference || selectedModelFullId.isNotEmpty()) {
                        val modelLabelStep = if (isCloudInference) "3" else "4"
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (selectedModelFullId.isEmpty()) "$modelLabelStep. Select Model (Required)" else "$modelLabelStep. Model Selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedModelFullId.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )

                            // Model Provider List
                            if (allModels.isNotEmpty()) {
                                ExposedDropdownMenuBox(
                                    expanded = providerDropdownExpanded && (providerSuggestions.isNotEmpty() || modelProviderFilter.isEmpty()),
                                    onExpandedChange = { providerDropdownExpanded = it }
                                ) {
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
                                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
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
                                            } else {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded)
                                            }
                                        },
                                        singleLine = true
                                    )

                                    ExposedDropdownMenu(
                                        expanded = providerDropdownExpanded && (providerSuggestions.isNotEmpty() || modelProviderFilter.isEmpty()),
                                        onDismissRequest = { providerDropdownExpanded = false },
                                        modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 240.dp)
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
                            }

                            // Model Search & List
                            ExposedDropdownMenuBox(
                                expanded = modelDropdownExpanded && filteredModels.isNotEmpty(),
                                onExpandedChange = { modelDropdownExpanded = it }
                            ) {
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
                                        if (!isCloudInference) {
                                            selectedModelFullId = it
                                        }
                                        modelDropdownExpanded = true
                                    },
                                    label = { Text("Model Name") },
                                    trailingIcon = {
                                        IconButton(onClick = { modelDropdownExpanded = !modelDropdownExpanded }) {
                                            Icon(Icons.Default.Search, null)
                                        }
                                    },
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
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
                                if (filteredModels.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = modelDropdownExpanded,
                                        onDismissRequest = { modelDropdownExpanded = false },
                                        modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 280.dp)
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
                            }

                            val profileNameLabelStep = if (isCloudInference) "4" else "5"
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("$profileNameLabelStep. Profile Name (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    val effectiveBaseUrl = if (isCloudInference) defaultUrls[selectedProvider] ?: baseUrl else baseUrl
                    // Default Local to Text (isChatCompletion = false) and Cloud to Chat (isChatCompletion = true)
                    val defaultChatCompletion = isCloudInference
                    onSave(selectedProvider, name, effectiveBaseUrl, apiKey, selectedModelFullId, initialConnection?.isChatCompletion == 1L || (initialConnection == null && defaultChatCompletion))
                },
                enabled = selectedProvider.isNotEmpty() && (isKeyValid || !isCloudInference) && selectedModelFullId.isNotBlank() && (isCloudInference || baseUrl.isNotBlank())
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

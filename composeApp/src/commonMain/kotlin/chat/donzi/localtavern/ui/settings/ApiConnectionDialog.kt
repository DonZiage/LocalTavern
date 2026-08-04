package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.network.ConnectionProbe
import chat.donzi.localtavern.data.network.ModelEndpointInfo
import chat.donzi.localtavern.data.network.ModelInfo
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.utils.fuzzyScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private data class ValidationRequest(val baseUrl: String, val apiKey: String, val provider: String)

// OpenRouter quantization options (sent as provider.quantizations).
private val quantizationOptions = listOf(
    null to "Default",
    "int4" to "int4 (fastest, lowest quality)",
    "int8" to "int8",
    "fp8" to "fp8",
    "fp16" to "fp16",
    "bf16" to "bf16 (highest quality)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionDialog(
    chatClient: ChatClient,
    initialConnection: ApiConfig? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, model: String, inferenceProvider: String?, quantization: String?, isChatCompletion: Boolean) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialConnection?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initialConnection?.baseUrl ?: "") }

    val isLocal = remember(baseUrl) {
        ProviderCatalog.isLocalEndpoint(baseUrl)
    }
    // The API provider is derived from the endpoint URL (pricing and request
    // format need it); the user never picks it directly anymore.
    val detectedProvider = remember(baseUrl) {
        ProviderCatalog.detectProviderFromBaseUrl(baseUrl)
    }
    val isOpenRouter = detectedProvider.equals("OpenRouter", ignoreCase = true)

    // Cloud endpoints use a two-step flow (endpoint+key, then model); local
    // endpoints keep everything on one screen since they need no key.
    var step by remember { mutableStateOf(0) }

    val maskedApiKey = remember(initialConnection?.apiKey) {
        val key = initialConnection?.apiKey ?: ""
        // Never reveal part of a stored secret: show only masking dots so the
        // key fragment cannot be inferred from the field.
        if (key.isNotEmpty()) {
            "••••••••"
        } else {
            ""
        }
    }

    var allModels by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var isKeyValid by remember { mutableStateOf(false) }
    var lastValidatedRequest by remember { mutableStateOf<ValidationRequest?>(null) }
    // Last probe outcome, so the UI can tell an auth rejection apart from an
    // unreachable endpoint instead of blaming the key for a network failure.
    var probeResult by remember { mutableStateOf<ConnectionProbe?>(null) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    var modelSearch by remember { mutableStateOf(initialConnection?.model ?: "") }
    var selectedModelFullId by remember { mutableStateOf(initialConnection?.model ?: "") }
    // Multi-select of host providers, stored comma-separated on the profile
    // (OpenRouter routes among all selected providers).
    var selectedHostProviders by remember {
        mutableStateOf(
            initialConnection?.inferenceProvider
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        )
    }
    var quantization by remember { mutableStateOf(initialConnection?.quantization ?: "") }
    var validationRequestId by remember { mutableStateOf(0) }
    // OpenRouter only: the cloud providers actually serving the selected model
    // (from GET /models/{id}/endpoints). The catalog's id prefix is the model
    // publisher, which cannot be used as a routing provider.
    var modelEndpoints by remember { mutableStateOf(emptyList<ModelEndpointInfo>()) }
    var isLoadingProviders by remember { mutableStateOf(false) }
    // OpenRouter ZDR registry (GET /endpoints/zdr): which (model, provider)
    // pairs keep no prompt data. zdrLoaded distinguishes "fetched and empty"
    // from "registry unreachable", so unknown providers are never labeled as
    // non-ZDR.
    var zdrEndpoints by remember { mutableStateOf(emptyList<ModelEndpointInfo>()) }
    var zdrLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(apiKey, baseUrl) {
        val requestId = ++validationRequestId
        val keyToTest = apiKey.ifBlank { initialConnection?.apiKey ?: "" }
        val effectiveBaseUrl = baseUrl.trim()

        if (effectiveBaseUrl.isBlank() || (keyToTest.length <= 5 && !isLocal)) {
            isKeyValid = false
            probeResult = null
            connectionError = null
            allModels = emptyList()
            lastValidatedRequest = null
            isLoadingModels = false
            return@LaunchedEffect
        }

        val request = ValidationRequest(effectiveBaseUrl, keyToTest, detectedProvider)
        if (request == lastValidatedRequest) {
            isLoadingModels = false
            return@LaunchedEffect
        }

        // A new (possibly edited) key or URL is being validated: the stale
        // result of the previous request must not keep the Next button
        // enabled in the meantime.
        isKeyValid = false
        probeResult = null
        connectionError = null

        delay(800)
        isLoadingModels = true
        try {
            val probe = chatClient.probeConnection(effectiveBaseUrl, keyToTest, detectedProvider)
            probeResult = probe.outcome
            if (probe.outcome == ConnectionProbe.Ok) {
                // The key is confirmed working even if the model list fetch
                // fails below; that failure must not block the flow (the
                // model can still be typed in manually).
                isKeyValid = true
                allModels = chatClient.fetchModels(effectiveBaseUrl, keyToTest, detectedProvider)
            } else {
                isKeyValid = false
                val detail = probe.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                connectionError = when (probe.outcome) {
                    ConnectionProbe.AuthFailed -> "The API key was rejected. Check the key and try again.$detail"
                    else -> "Could not reach the API endpoint. Check the URL and your network connection.$detail"
                }
                allModels = emptyList()
            }
            lastValidatedRequest = request
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Status check failed (endpoint unreachable, network hiccup): the
            // key cannot be confirmed valid. isKeyValid was already reset
            // above for this request.
            probeResult = ConnectionProbe.Unreachable
            connectionError = "Could not reach the API endpoint. Check the URL and your network connection."
            allModels = emptyList()
        } finally {
            // Only the newest validation request may clear the spinner; a
            // cancelled older request must not turn it off while a newer
            // validation is already running.
            if (requestId == validationRequestId) {
                isLoadingModels = false
            }
        }
    }

    // The serving providers depend on the selected model, so fetch them once a
    // model is picked (OpenRouter only; other endpoints have a single backend).
    LaunchedEffect(selectedModelFullId, baseUrl, apiKey) {
        if (selectedModelFullId.isEmpty() || !isOpenRouter) {
            modelEndpoints = emptyList()
            isLoadingProviders = false
            return@LaunchedEffect
        }
        isLoadingProviders = true
        modelEndpoints = chatClient.fetchModelEndpoints(
            baseUrl.trim(),
            apiKey.ifBlank { initialConnection?.apiKey ?: "" },
            selectedModelFullId,
            detectedProvider
        )
        isLoadingProviders = false
    }

    // The ZDR registry is model-independent; fetch it once per dialog so the
    // provider list can be labeled with which backends keep prompts private.
    LaunchedEffect(isOpenRouter, baseUrl, apiKey) {
        if (!isOpenRouter) {
            zdrEndpoints = emptyList()
            zdrLoaded = false
            return@LaunchedEffect
        }
        val result = chatClient.fetchZdrEndpoints(
            baseUrl.trim(),
            apiKey.ifBlank { initialConnection?.apiKey ?: "" },
            detectedProvider
        )
        if (result != null) {
            zdrEndpoints = result
            zdrLoaded = true
        }
    }

    val providerSuggestions = remember(modelEndpoints) {
        modelEndpoints.mapNotNull { it.providerName }.distinct().sorted()
    }

    // ZDR providers among those serving the selected model. An endpoint is
    // ZDR only when both APIs agree on the same (model, provider) pair; both
    // report the canonical model id, so dated catalog ids still match.
    val zdrProviders = remember(modelEndpoints, zdrEndpoints, zdrLoaded) {
        if (!zdrLoaded) {
            emptySet()
        } else {
            val zdrKeys = zdrEndpoints.mapNotNull { endpoint ->
                val modelId = endpoint.modelId?.lowercase() ?: return@mapNotNull null
                val provider = endpoint.providerName?.lowercase() ?: return@mapNotNull null
                "$modelId|$provider"
            }.toSet()
            modelEndpoints.mapNotNull { endpoint ->
                val modelId = endpoint.modelId?.lowercase() ?: return@mapNotNull null
                val provider = endpoint.providerName ?: return@mapNotNull null
                if ("$modelId|${provider.lowercase()}" in zdrKeys) provider else null
            }.toSet()
        }
    }

    // The user's provider choices are kept as-is even when a selected provider
    // does not serve the selected model: the app informs instead of silently
    // rewriting the config. OpenRouter then routes (or fails) based on the
    // saved preference. Only reported when the endpoint list actually loaded.
    val servingProviders = remember(modelEndpoints) {
        modelEndpoints.mapNotNull { it.providerName }.toSet()
    }
    val unservedProviders = remember(selectedHostProviders, servingProviders) {
        if (servingProviders.isEmpty()) emptySet()
        else selectedHostProviders.filterNot { it in servingProviders }.toSet()
    }

    val showHostProviders = (providerSuggestions.size > 1 || selectedHostProviders.isNotEmpty()) && selectedModelFullId.isNotEmpty()

    val filteredModels = remember(allModels, modelSearch) {
        allModels.asSequence()
            .map { model ->
                val idScore = model.id.fuzzyScore(modelSearch)
                val nameScore = model.displayName.fuzzyScore(modelSearch)
                var finalScore = maxOf(idScore, nameScore)

                if (modelSearch.isNotEmpty()) {
                    finalScore += 50
                }

                model to finalScore
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(50)
            .toList()
    }

    LaunchedEffect(filteredModels, modelSearch) {
        if (modelSearch.isBlank()) {
            selectedModelFullId = ""
        } else {
            val exactMatch = filteredModels.firstOrNull { it.id == modelSearch || it.displayName == modelSearch }
            if (exactMatch != null) {
                selectedModelFullId = exactMatch.id
            } else {
                // The typed text no longer matches the previously selected
                // model (models may have loaded after the search was typed), so
                // the selection must not silently persist.
                val selectedModel = allModels.find { it.id == selectedModelFullId }
                if (selectedModelFullId.isNotEmpty() &&
                    modelSearch != selectedModelFullId &&
                    modelSearch != selectedModel?.displayName
                ) {
                    selectedModelFullId = ""
                }
            }
        }
    }

    // Cloud endpoints: step 0 (endpoint + key) before step 1 (model).
    val showStepOne = !isLocal
    val onStepOne = showStepOne && step == 1

    val baseTitle = if (initialConnection == null) "Setup API Connection" else "Edit API Connection"
    val title = when {
        showStepOne && step == 0 -> "$baseTitle — Step 1 of 2"
        showStepOne -> "$baseTitle — Step 2 of 2"
        else -> baseTitle
    }

    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showStepOne) {
                    Text(
                        text = if (step == 0) "Provider & API key" else "Model, providers & profile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!onStepOne) {
                    BaseUrlPicker(
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it }
                    )

                    val optionalText = if (isLocal) " (Optional for local endpoints)" else ""
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("2. ${if (initialConnection == null) "Enter API Key$optionalText" else "Update API Key"}") },
                        placeholder = { Text(maskedApiKey) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = apiKey.isNotEmpty() && probeResult == ConnectionProbe.AuthFailed,
                        trailingIcon = {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        },
                        singleLine = true
                    )

                    // Distinguish a rejected key from an unreachable endpoint
                    // so the user fixes the right thing.
                    if (connectionError != null) {
                        Text(
                            text = connectionError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (onStepOne || isLocal) {
                    if (isKeyValid || isLocal || selectedModelFullId.isNotEmpty()) {
                        ModelPicker(
                            labelStep = "3",
                            allModels = allModels,
                            filteredModels = filteredModels,
                            modelSearch = modelSearch,
                            selectedModelFullId = selectedModelFullId,
                            onModelSearchChange = { value ->
                                modelSearch = value
                                // The typed text no longer matches the previously
                                // selected model, so the selection must not silently
                                // persist (which would save the wrong model).
                                val selectedModel = allModels.find { it.id == selectedModelFullId }
                                if (selectedModelFullId.isNotEmpty() &&
                                    value != selectedModelFullId &&
                                    value != selectedModel?.displayName
                                ) {
                                    selectedModelFullId = ""
                                }
                            },
                            onModelSelected = { model ->
                                selectedModelFullId = model.id
                                modelSearch = model.id
                            }
                        )
                    }

                    if (showHostProviders) {
                        HostProvidersPicker(
                            labelStep = "4",
                            providers = providerSuggestions,
                            selectedProviders = selectedHostProviders,
                            zdrKnown = zdrLoaded,
                            zdrProviders = zdrProviders,
                            isLoading = isLoadingProviders,
                            unservedProviders = unservedProviders,
                            onToggleProvider = { provider ->
                                selectedHostProviders =
                                    if (provider in selectedHostProviders) selectedHostProviders - provider
                                    else selectedHostProviders + provider
                            },
                            onClearAll = { selectedHostProviders = emptySet() }
                        )
                    }

                    if (isOpenRouter || quantization.isNotBlank()) {
                        QuantizationPicker(
                            quantization = quantization,
                            onQuantizationChange = { quantization = it }
                        )
                    }
                }

                if (onStepOne || isLocal) {
                    // The profile name stays editable even when validation
                    // fails (e.g. the endpoint is down): hiding it would make
                    // the dialog a dead end for renaming an existing profile.
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("6. Profile Name (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            if (showStepOne && !onStepOne) {
                // Step 0: proceed to the model step once the endpoint works.
                Button(
                    onClick = { step = 1 },
                    enabled = baseUrl.isNotBlank() && (isKeyValid || selectedModelFullId.isNotBlank())
                ) {
                    Text("Next")
                }
            } else {
                Button(
                    onClick = {
                        // The stored provider is derived from the endpoint URL
                        // so pricing and API-format detection keep working.
                        onSave(
                            name,
                            baseUrl.trim(),
                            apiKey,
                            selectedModelFullId,
                            selectedHostProviders.sorted().joinToString(",").takeIf { it.isNotEmpty() },
                            quantization.takeIf { it.isNotBlank() },
                            initialConnection?.isChatCompletion == true || (initialConnection == null && !isLocal)
                        )
                    },
                    enabled = baseUrl.isNotBlank() && (isKeyValid || isLocal) && selectedModelFullId.isNotBlank() && modelSearch.isNotBlank()
                ) {
                    Text(if (initialConnection == null) "Complete Setup" else "Save Changes")
                }
            }
        },
        dismissButton = {
            Row {
                if (onStepOne) {
                    TextButton(onClick = { step = 0 }) {
                        Text("Back")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseUrlPicker(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it }
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("1. Endpoint URL (Required)") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
            trailingIcon = {
                if (baseUrl.isBlank()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                } else {
                    IconButton(onClick = { onBaseUrlChange("") }) {
                        Icon(Icons.Filled.Clear, "Clear URL")
                    }
                }
            },
            isError = baseUrl.isBlank(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 360.dp)
        ) {
            ProviderCatalog.providerSections.forEach { (sectionName, providersInSection) ->
                Text(
                    text = sectionName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                providersInSection.forEach { provider ->
                    ProviderCatalog.defaultUrls[provider]?.let { url ->
                        DropdownMenuItem(
                            text = { Text(provider, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onBaseUrlChange(url)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuantizationPicker(
    quantization: String,
    onQuantizationChange: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it }
    ) {
        OutlinedTextField(
            value = quantization.ifBlank { "Default" },
            onValueChange = { },
            readOnly = true,
            label = { Text("5. Quantization (OpenRouter)") },
            placeholder = { Text("Default") },
            supportingText = {
                Text(
                    "Which precision serves the model. int4 is fastest; bf16 has the highest quality.",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            trailingIcon = {
                if (quantization.isNotBlank()) {
                    IconButton(onClick = { onQuantizationChange("") }) {
                        Icon(Icons.Filled.Clear, "Clear quantization")
                    }
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                }
            },
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 280.dp)
        ) {
            quantizationOptions.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onQuantizationChange(value.orEmpty())
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

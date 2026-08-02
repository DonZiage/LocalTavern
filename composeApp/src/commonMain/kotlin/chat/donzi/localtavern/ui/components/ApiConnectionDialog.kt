package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.network.ConnectionProbe
import chat.donzi.localtavern.data.network.ModelInfo
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.utils.fuzzyScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private data class ValidationRequest(val baseUrl: String, val apiKey: String, val provider: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionDialog(
    chatClient: ChatClient,
    initialConnection: ApiConfig? = null,
    onDismiss: () -> Unit,
    onSave: (provider: String, name: String, baseUrl: String, apiKey: String, model: String, isChatCompletion: Boolean) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(initialConnection?.provider ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialConnection?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initialConnection?.baseUrl ?: "") }

    val isCloudInference = remember(selectedProvider) {
        ProviderCatalog.cloudInferenceProviders.contains(selectedProvider)
    }

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
    var modelProviderFilter by remember { mutableStateOf("") }
    var validationRequestId by remember { mutableStateOf(0) }

    // Remembers the last provider so switching providers only auto-fills the
    // default URL when the field was untouched (blank or still the previous
    // provider's auto-filled default), never over a user-typed custom URL.
    var lastProvider by remember { mutableStateOf(selectedProvider) }

    LaunchedEffect(selectedProvider) {
        if (initialConnection == null) {
            val previousDefault = ProviderCatalog.defaultUrls[lastProvider]
            if (baseUrl.isBlank() || (previousDefault != null && baseUrl == previousDefault)) {
                baseUrl = ProviderCatalog.defaultUrls[selectedProvider] ?: ""
            }
        }
        // A model picked for the previous provider must not survive a provider
        // switch, otherwise Save persists a model id that does not exist for
        // the newly selected provider.
        if (selectedProvider != lastProvider) {
            modelSearch = ""
            selectedModelFullId = ""
            modelProviderFilter = ""
        }
        lastProvider = selectedProvider
    }

    LaunchedEffect(apiKey, baseUrl, selectedProvider) {
        val requestId = ++validationRequestId
        val keyToTest = apiKey.ifBlank { initialConnection?.apiKey ?: "" }
        val effectiveBaseUrl = if (isCloudInference) ProviderCatalog.defaultUrls[selectedProvider] ?: baseUrl else baseUrl

        if (selectedProvider.isEmpty() || (keyToTest.length <= 5 && isCloudInference) || effectiveBaseUrl.isBlank()) {
            isKeyValid = false
            probeResult = null
            connectionError = null
            allModels = emptyList()
            lastValidatedRequest = null
            isLoadingModels = false
            return@LaunchedEffect
        }

        val request = ValidationRequest(effectiveBaseUrl, keyToTest, selectedProvider)
        if (request == lastValidatedRequest) {
            isLoadingModels = false
            return@LaunchedEffect
        }

        // A new (possibly edited) key is being validated: the stale result of
        // the previous request must not keep Save enabled in the meantime.
        isKeyValid = false
        probeResult = null
        connectionError = null

        delay(800)
        isLoadingModels = true
        try {
            val probe = chatClient.probeConnection(effectiveBaseUrl, keyToTest, selectedProvider)
            probeResult = probe
            if (probe == ConnectionProbe.Ok) {
                // The key is confirmed working even if the model list fetch
                // fails below; that failure must not disable Save for a valid
                // key (the model can still be typed in manually).
                isKeyValid = true
                allModels = chatClient.fetchModels(effectiveBaseUrl, keyToTest, selectedProvider)
            } else {
                isKeyValid = false
                connectionError = when (probe) {
                    ConnectionProbe.AuthFailed -> "The API key was rejected. Check the key and try again."
                    else -> "Could not reach the API endpoint. Check the URL and your network connection."
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

    LaunchedEffect(allModels) {
        if (initialConnection != null && modelProviderFilter.isEmpty() && selectedModelFullId.isNotEmpty()) {
            val currentModel = allModels.find { it.id == selectedModelFullId }
            if (currentModel != null) {
                modelProviderFilter = currentModel.provider
            }
        }
    }

    LaunchedEffect(modelProviderFilter) {
        if (selectedModelFullId.isNotEmpty()) {
            val currentModel = allModels.find { it.id == selectedModelFullId }
            if (currentModel != null) {
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

                // The provider filter must be enforced regardless of the
                // search text; otherwise a model from another provider could
                // be selected and saved.
                if (!matchesFilter) {
                    finalScore = 0
                } else if (modelSearch.isNotEmpty()) {
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
            if (isCloudInference) {
                selectedModelFullId = ""
            }
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

    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (initialConnection == null) "Setup API Connection" else "Edit API Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderPicker(
                    selectedProvider = selectedProvider,
                    onProviderSelected = { selectedProvider = it }
                )

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
                        isError = isCloudInference && apiKey.isNotEmpty() && probeResult == ConnectionProbe.AuthFailed,
                        trailingIcon = {
                            if (isLoadingModels) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        },
                        singleLine = true
                    )

                    // Distinguish a rejected key from an unreachable endpoint so
                    // the user fixes the right thing.
                    if (connectionError != null) {
                        Text(
                            text = connectionError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isKeyValid || !isCloudInference || selectedModelFullId.isNotEmpty()) {
                        ModelPicker(
                            isCloudInference = isCloudInference,
                            labelStep = if (isCloudInference) "3" else "4",
                            allModels = allModels,
                            providerSuggestions = providerSuggestions,
                            filteredModels = filteredModels,
                            modelProviderFilter = modelProviderFilter,
                            modelSearch = modelSearch,
                            selectedModelFullId = selectedModelFullId,
                            onProviderFilterChange = { modelProviderFilter = it },
                            onModelSearchChange = { value ->
                                modelSearch = value
                                if (!isCloudInference) {
                                    selectedModelFullId = value
                                } else {
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
                                }
                            },
                            onModelSelected = { model ->
                                selectedModelFullId = model.id
                                modelSearch = model.displayName
                                modelProviderFilter = model.provider
                            }
                        )
                    }

                    // The profile name stays editable even when validation
                    // fails (e.g. the endpoint is down): hiding it would make
                    // the dialog a dead end for renaming an existing profile.
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveBaseUrl = if (isCloudInference) ProviderCatalog.defaultUrls[selectedProvider] ?: baseUrl else baseUrl
                    val defaultChatCompletion = isCloudInference
                    onSave(selectedProvider, name, effectiveBaseUrl, apiKey, selectedModelFullId, initialConnection?.isChatCompletion == true || (initialConnection == null && defaultChatCompletion))
                },
                enabled = selectedProvider.isNotEmpty() && (isKeyValid || !isCloudInference) && selectedModelFullId.isNotBlank() && modelSearch.isNotBlank() && (isCloudInference || baseUrl.isNotBlank())
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

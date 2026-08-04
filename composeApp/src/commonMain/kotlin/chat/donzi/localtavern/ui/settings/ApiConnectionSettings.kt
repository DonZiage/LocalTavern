package chat.donzi.localtavern.ui.settings
import chat.donzi.localtavern.ui.common.CardCarousel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.pricing.CostEstimator
import chat.donzi.localtavern.data.pricing.LivePricingCatalog
import chat.donzi.localtavern.data.pricing.PricingCatalog
import chat.donzi.localtavern.data.security.ApiKeyCipher
import kotlinx.coroutines.launch

@Composable
fun ApiConnectionSettings(
    apiSettingsRepository: ApiSettingsRepository,
    chatClient: ChatClient,
    apiKeyCipher: ApiKeyCipher,
    onApiChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<ApiConfig>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<ApiConfig?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    // One-time nudge on desktop (passphrase backend): the first saved API
    // key is plaintext unless the user opts in, so offer protection right
    // when the decision is most relevant.
    var showPassphraseNudge by remember { mutableStateOf(false) }
    var showProtectDialog by remember { mutableStateOf(false) }
    // A key the user typed could not be encrypted/stored (security store
    // unavailable while protection is configured). The dialog stays open so
    // the input is not lost; this alert explains why nothing was saved.
    var keySaveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTrigger) {
        connections = apiSettingsRepository.getAllApiConnections()
    }

    var activeConnection by remember { mutableStateOf<ApiConfig?>(null) }
    LaunchedEffect(refreshTrigger, connections) {
        activeConnection = apiSettingsRepository.getActiveApiConnection()
    }

    // Live snapshot of the active connection's parameters: updated instantly
    // while the user drags a parameter slider (ParameterControls.onLiveUpdate)
    // and re-synced whenever the persisted active connection reloads, so the
    // max prompt cost banner tracks every change in real time.
    var liveConnection by remember { mutableStateOf<ApiConfig?>(null) }
    LaunchedEffect(activeConnection) {
        liveConnection = activeConnection
    }
    val displayedConnection = liveConnection ?: activeConnection

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
                    onDelete = onDeleteRequest,
                    onEdit = {
                        editingConnection = connection
                    },
                    onCenterRequest = requestCenter,
                    modifier = modifier
                )
            }
        )

        // Worst-case request cost for the active connection, right below the
        // API cards so even casual users see it. Hidden entirely when the
        // active connection runs local inference.
        displayedConnection?.let { currentActive ->
            Spacer(modifier = Modifier.height(16.dp))
            MaxPromptCostBanner(connection = currentActive)
        }

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
                },
                onLiveUpdate = { liveConnection = it }
            )
        }
    }

    if (showAddDialog) {
        ApiConnectionDialog(
            chatClient = chatClient,
            onDismiss = { showAddDialog = false },
            onSave = { name, baseUrl, apiKey, model, inferenceProvider, quantization ->
                scope.launch {
                    // A new key cannot be encrypted while the security store
                    // is unavailable (lost keystore/keychain key, corrupt
                    // passphrase file): inserting would store a keyless row
                    // and the typed key would silently vanish. Refuse the
                    // save instead and let the user retry after a restart.
                    if (apiKey.isNotBlank() && apiKeyCipher.isProtected && !apiKeyCipher.isAvailable) {
                        keySaveError = "Your API key could not be encrypted and was not saved: the security store is unavailable. Restart the app and try again."
                        return@launch
                    }
                    // The repository derives the active flag and display order
                    // from fresh DB state, so a stale UI list cannot wrongly
                    // activate the new connection or collide on ordering.
                    val isFirstConnection = connections.isEmpty()
                    apiSettingsRepository.insertApiConnection(
                        provider = ProviderCatalog.detectProviderFromBaseUrl(baseUrl),
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        inferenceProvider = inferenceProvider,
                        quantization = quantization,
                        timeoutLimit = 60L
                    )
                    showAddDialog = false
                    refreshTrigger++
                    onApiChanged()
                    if (isFirstConnection &&
                        apiKeyCipher.backendName.contains("passphrase", ignoreCase = true) &&
                        !apiKeyCipher.isProtected
                    ) {
                        showPassphraseNudge = true
                    }
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
            onSave = { name, baseUrl, apiKey, model, inferenceProvider, quantization ->
                scope.launch {
                    val stored = apiSettingsRepository.updateApiConnection(
                        id = connectionToEdit.id,
                        provider = ProviderCatalog.detectProviderFromBaseUrl(baseUrl),
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey.ifBlank { connectionToEdit.apiKey ?: "" },
                        model = model,
                        inferenceProvider = inferenceProvider,
                        quantization = quantization,
                        isActive = connectionToEdit.isActive,
                        chatCompletionMode = connectionToEdit.chatCompletionMode,
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
                    if (!stored) {
                        // The row was updated but the NEW key could not be
                        // encrypted, so the previous key was kept. Never let
                        // the dialog close silently: the typed key would be
                        // lost and the UI would claim a save that did not
                        // happen. The dialog stays open with the input intact.
                        keySaveError = "Your new API key could not be encrypted and was not saved: the security store is unavailable. Restart the app and try again."
                        return@launch
                    }
                    editingConnection = null
                    refreshTrigger++
                    onApiChanged()
                }
            }
        )
    }

    if (showPassphraseNudge) {
        AlertDialog(
            onDismissRequest = { showPassphraseNudge = false },
            title = { Text("Protect your API keys?") },
            text = {
                Text(
                    "The desktop build stores API keys as plaintext unless you set a passphrase. " +
                        "You can do this now, or anytime in Settings → API Key Security."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPassphraseNudge = false
                    showProtectDialog = true
                }) {
                    Text("Protect…")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPassphraseNudge = false }) {
                    Text("Not now")
                }
            }
        )
    }

    if (showProtectDialog) {
        PassphraseDialog(
            title = "Protect API Keys",
            message = "Keys will be encrypted with a passphrase-derived key. Remember the passphrase — without it the keys cannot be recovered. Store it in your password manager.",
            confirmLabel = "Protect",
            requireConfirmation = true,
            enforcePolicy = true,
            onDismiss = { showProtectDialog = false },
            onConfirm = { input ->
                scope.launch {
                    // Failure (e.g. data dir not writable) leaves the keys
                    // plaintext; the security section below reflects the real
                    // state on refresh and offers the full flow to retry.
                    runCatching {
                        apiKeyCipher.protect(input.value)
                        apiSettingsRepository.reencryptAllApiKeys()
                    }
                    showProtectDialog = false
                    refreshTrigger++
                    onApiChanged()
                }
            }
        )
    }

    keySaveError?.let { message ->
        AlertDialog(
            onDismissRequest = { keySaveError = null },
            title = { Text("API key not saved") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { keySaveError = null }) { Text("OK") }
            }
        )
    }
}

// Worst-case cost of one request for the given connection: the whole context
// budget filled with prompt tokens plus a full response, priced live. Hidden
// entirely for local inference models (no price info exists for them).
@Composable
private fun MaxPromptCostBanner(connection: ApiConfig) {
    if (PricingCatalog.isLocalProvider(connection.provider)) return

    // Recompute when fresh live prices land (revision) or when any
    // cost-relevant setting changes — without remember-key staleness.
    val pricingRevision by LivePricingCatalog.revision.collectAsState()
    val cost = remember(
        pricingRevision,
        connection.provider, connection.model,
        connection.contextLimit, connection.responseLimit
    ) {
        CostEstimator.estimateMaxPromptCost(
            provider = connection.provider,
            model = connection.model,
            contextLimit = connection.contextLimit,
            responseLimit = connection.responseLimit
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Max prompt cost",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Estimated worst case for one request — heuristic tokenizer and current list prices, may differ from the actual bill.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Text(
                text = cost?.let { "~${CostEstimator.formatUsd(it.totalUsd)}" } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
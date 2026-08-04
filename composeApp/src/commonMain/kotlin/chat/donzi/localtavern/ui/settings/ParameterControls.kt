package chat.donzi.localtavern.ui.settings
import chat.donzi.localtavern.ui.layout.CollapsibleSettingsSection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.domain.PromptBlock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ParameterControls(
    connection: ApiConfig,
    apiSettingsRepository: ApiSettingsRepository,
    onUpdate: (ApiConfig) -> Unit,
    // Reports every parameter change immediately (before the debounced DB
    // write), so parent-level readouts — like the max prompt cost banner —
    // update in real time while the user drags a slider.
    onLiveUpdate: (ApiConfig) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var promptBlocks by remember { mutableStateOf<List<PromptBlock>>(emptyList()) }
    // Tracks the in-flight display-order write so reloads never race it and
    // snap the list back to the pre-drag order.
    var pendingOrderWrite by remember { mutableStateOf<Job?>(null) }

    // Working copy accumulates slider changes so rapid successive commits are
    // persisted as one write carrying the *latest* snapshot. Without it, a
    // second commit based on the pre-first-write connection would silently
    // revert the first change (out-of-order DB writes).
    var workingConnection by remember(connection.id) { mutableStateOf(connection) }
    var paramWriteJob by remember { mutableStateOf<Job?>(null) }

    // Re-seed the working copy whenever the parent supplies a new connection
    // object — whether from this component's own write or an external edit
    // (e.g. the connection dialog). A pending debounced write was computed
    // from the stale copy and must be cancelled, otherwise it would clobber
    // the fresh values once it lands.
    LaunchedEffect(connection) {
        paramWriteJob?.cancel()
        workingConnection = connection
    }

    fun persistParams() {
        onLiveUpdate(workingConnection)
        paramWriteJob?.cancel()
        paramWriteJob = coroutineScope.launch {
            delay(250)
            apiSettingsRepository.updateApiConnection(workingConnection)
            onUpdate(workingConnection)
        }
    }

    LaunchedEffect(Unit) {
        promptBlocks = apiSettingsRepository.getAllPromptBlocks()
    }

    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text("Generation Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        ContextLimitSlider(
            currentLimit = workingConnection.contextLimit,
            onValueChange = {
                workingConnection = workingConnection.copy(contextLimit = it)
                persistParams()
            }
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            ParameterSlider(
                label = "Response Limit",
                // 0 means "Unlimited"; keep the slider on the real 64..4096
                // range so every position maps to a real value (no dead zone).
                value = workingConnection.responseLimit.coerceIn(64L, 4096L).toFloat(),
                range = 64f..4096f,
                steps = 63,
                format = { it.toInt().toString() },
                enabled = workingConnection.responseLimit != 0L,
                onValueChange = {
                    workingConnection = workingConnection.copy(responseLimit = it.toLong())
                    persistParams()
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Unlimited response length",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = workingConnection.responseLimit == 0L,
                    onCheckedChange = { unlimited ->
                        workingConnection = workingConnection.copy(
                            responseLimit = if (unlimited) 0L else 1024L
                        )
                        persistParams()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.alpha(0.3f))
        Spacer(modifier = Modifier.height(8.dp))

        CollapsibleSettingsSection(title = "Advanced", initialExpanded = false) {
            Column(modifier = Modifier.fillMaxWidth()) {

                SystemPromptSettings(
                    blocks = promptBlocks,
                    onBlocksChange = { updatedList ->
                        promptBlocks = updatedList
                        pendingOrderWrite?.cancel()
                        pendingOrderWrite = coroutineScope.launch {
                            apiSettingsRepository.updatePromptBlockDisplayOrders(updatedList.map { it.id })
                        }
                    },
                    onBlockMutate = { mutatedBlock ->
                        coroutineScope.launch {
                            pendingOrderWrite?.join()
                            apiSettingsRepository.savePromptBlock(
                                id = mutatedBlock.id,
                                name = mutatedBlock.name,
                                template = mutatedBlock.template,
                                isEnabled = mutatedBlock.isEnabled
                            )
                            promptBlocks = apiSettingsRepository.getAllPromptBlocks()
                        }
                    },
                    onBlockAdd = { name, template ->
                        coroutineScope.launch {
                            pendingOrderWrite?.join()
                            apiSettingsRepository.insertCustomPromptBlock(name, template)
                            promptBlocks = apiSettingsRepository.getAllPromptBlocks()
                        }
                    },
                    onBlockDelete = { id ->
                        coroutineScope.launch {
                            pendingOrderWrite?.join()
                            apiSettingsRepository.deletePromptBlock(id)
                            promptBlocks = apiSettingsRepository.getAllPromptBlocks()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.alpha(0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                ParameterSlider(
                    label = "Temperature",
                    value = workingConnection.temperature.toFloat(),
                    range = 0f..2f,
                    steps = 20,
                    onValueChange = {
                        workingConnection = workingConnection.copy(temperature = it.toDouble())
                        persistParams()
                    }
                )

                ParameterSlider(
                    label = "Top-P",
                    value = workingConnection.topP.toFloat(),
                    range = 0f..1f,
                    steps = 10,
                    onValueChange = {
                        workingConnection = workingConnection.copy(topP = it.toDouble())
                        persistParams()
                    }
                )

                ParameterSlider(
                    label = "Top-K",
                    value = workingConnection.topK.toFloat(),
                    range = 0f..100f,
                    steps = 100,
                    format = { it.toInt().toString() },
                    onValueChange = {
                        workingConnection = workingConnection.copy(topK = it.toLong())
                        persistParams()
                    }
                )

                ParameterSlider(
                    label = "Presence Penalty",
                    value = workingConnection.presencePenalty.toFloat(),
                    range = -2f..2f,
                    steps = 40,
                    onValueChange = {
                        workingConnection = workingConnection.copy(presencePenalty = it.toDouble())
                        persistParams()
                    }
                )

                ParameterSlider(
                    label = "Frequency Penalty",
                    value = workingConnection.frequencyPenalty.toFloat(),
                    range = -2f..2f,
                    steps = 40,
                    onValueChange = {
                        workingConnection = workingConnection.copy(frequencyPenalty = it.toDouble())
                        persistParams()
                    }
                )

                ParameterSlider(
                    label = "Response Timeout",
                    value = workingConnection.timeoutLimit.toFloat(),
                    range = 0f..120f,
                    steps = 5,
                    format = { if (it == 0f) "No Timer" else "${it.toInt()}s" },
                    onValueChange = { floatValue ->
                        workingConnection = workingConnection.copy(timeoutLimit = floatValue.roundToInt().toLong())
                        persistParams()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.alpha(0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Reasoning Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Auto detects o-series / R1 / reasoner models. On captures and stores the chain of thought.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val reasoningOptions = listOf(
                        "Auto" to 0,
                        "On" to 1,
                        "Off" to 2
                    )
                    reasoningOptions.forEachIndexed { index, (label, value) ->
                        val selected = workingConnection.reasoningOverride == value
                        FilterChip(
                            selected = selected,
                            onClick = {
                                workingConnection = workingConnection.copy(reasoningOverride = value)
                                persistParams()
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.alpha(0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Chat Completion Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Auto decides between the chat and legacy completions endpoints from the model name. Only needed for providers stuck on the older completions API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chatCompletionOptions = listOf(
                        "Auto" to 0,
                        "Chat" to 1,
                        "Completions" to 2
                    )
                    chatCompletionOptions.forEachIndexed { index, (label, value) ->
                        val selected = workingConnection.chatCompletionMode == value
                        FilterChip(
                            selected = selected,
                            onClick = {
                                workingConnection = workingConnection.copy(chatCompletionMode = value)
                                persistParams()
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
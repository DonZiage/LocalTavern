package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    onUpdate: (ApiConfig) -> Unit
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
    var hasPendingParamWrite by remember { mutableStateOf(false) }

    // Re-seed the working copy from the DB once the pending write has landed.
    LaunchedEffect(connection) {
        if (!hasPendingParamWrite) {
            workingConnection = connection
        }
    }

    fun persistParams() {
        hasPendingParamWrite = true
        paramWriteJob?.cancel()
        paramWriteJob = coroutineScope.launch {
            delay(250)
            apiSettingsRepository.updateApiConnection(workingConnection)
            hasPendingParamWrite = false
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

        ParameterSlider(
            label = "Response Limit",
            value = if (workingConnection.responseLimit == 0L) 4160f else workingConnection.responseLimit.toFloat().coerceAtLeast(64f),
            range = 64f..4160f,
            steps = 63,
            format = { if (it > 4096f) "Unlimited" else it.toInt().toString() },
            onValueChange = {
                val newValue = if (it > 4096f) 0L else it.toLong()
                workingConnection = workingConnection.copy(responseLimit = newValue)
                persistParams()
            }
        )

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
            }
        }
    }
}
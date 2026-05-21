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
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.utils.PromptBlock
import chat.donzi.localtavern.utils.toDomain
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ParameterControls(
    connection: ApiConnection,
    repository: ChatRepository,
    onUpdate: (ApiConnection) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var promptBlocks by remember { mutableStateOf<List<PromptBlock>>(emptyList()) }

    LaunchedEffect(Unit) {
        promptBlocks = repository.getAllPromptBlocks().map { it.toDomain() }
    }

    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text("Generation Parameters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        ContextLimitSlider(
            currentLimit = connection.contextLimit,
            onValueChange = { onUpdate(connection.copy(contextLimit = it)) }
        )

        ParameterSlider(
            label = "Response Limit",
            value = if (connection.responseLimit == 0L) 4160f else connection.responseLimit.toFloat().coerceAtLeast(64f),
            range = 64f..4160f,
            steps = 63,
            format = { if (it > 4096f) "Unlimited" else it.toInt().toString() },
            onValueChange = {
                val newValue = if (it > 4096f) 0L else it.toLong()
                onUpdate(connection.copy(responseLimit = newValue))
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
                        coroutineScope.launch {
                            repository.updatePromptBlockDisplayOrders(updatedList.map { it.id })
                        }
                    },
                    onBlockMutate = { mutatedBlock ->
                        coroutineScope.launch {
                            repository.savePromptBlock(
                                id = mutatedBlock.id,
                                name = mutatedBlock.name,
                                template = mutatedBlock.template,
                                isEnabled = mutatedBlock.isEnabled
                            )
                            promptBlocks = repository.getAllPromptBlocks().map { it.toDomain() }
                        }
                    },
                    onBlockAdd = { name, template ->
                        coroutineScope.launch {
                            repository.insertCustomPromptBlock(name, template)
                            promptBlocks = repository.getAllPromptBlocks().map { it.toDomain() }
                        }
                    },
                    onBlockDelete = { id ->
                        coroutineScope.launch {
                            repository.deletePromptBlock(id)
                            promptBlocks = repository.getAllPromptBlocks().map { it.toDomain() }
                        }
                    }
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

                ParameterSlider(
                    label = "Response Timeout",
                    value = connection.timeoutLimit.toFloat(),
                    range = 0f..120f,
                    steps = 5,
                    format = { if (it == 0f) "No Timer" else "${it.toInt()}s" },
                    onValueChange = { floatValue ->
                        onUpdate(connection.copy(timeoutLimit = floatValue.roundToInt().toLong()))
                    }
                )
            }
        }
    }
}
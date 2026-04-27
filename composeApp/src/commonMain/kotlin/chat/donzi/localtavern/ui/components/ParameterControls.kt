package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ApiConnection

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

package chat.donzi.localtavern.ui.settings
import chat.donzi.localtavern.ui.common.StatusIndicator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.data.network.ChatClient
import kotlinx.coroutines.delay

// Model ids often carry a publisher prefix ("openai/gpt-4o", "deepseek/
// deepseek-chat"); the card shows just the model name.
private fun displayModel(model: String?): String {
    if (model.isNullOrBlank()) return "None"
    return model.substringAfter('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionItem(
    connection: ApiConfig,
    chatClient: ChatClient,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCenterRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf<Boolean?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }
    val cardShape = RoundedCornerShape(12.dp)

    LaunchedEffect(connection.id, connection.baseUrl, connection.apiKey, connection.provider, retryTrigger) {
        status = null
        // Local providers (Ollama, LM Studio, KoboldCPP, ...) need no API key,
        // so a base URL alone is enough to probe the health status.
        if (!connection.baseUrl.isNullOrBlank()) {
            val stagger = ((connection.id.hashCode() % 6) + 6) % 6 * 300L
            delay(stagger)
            status = chatClient.checkStatus(connection.baseUrl, connection.apiKey.orEmpty(), connection.provider)
        }
    }

    Card(
        modifier = modifier.clip(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (connection.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .clickable {
                    onToggleActive()
                    onCenterRequest()
                }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(
                status,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { retryTrigger++ }
                    .padding(8.dp)
                    .clip(CircleShape),
                size = 16.dp
            )

            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    connection.name.ifBlank { connection.provider },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    displayModel(connection.model),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

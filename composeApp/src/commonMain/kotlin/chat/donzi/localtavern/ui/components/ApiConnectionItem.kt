package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.network.ChatClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConnectionItem(
    connection: ApiConnection,
    chatClient: ChatClient,
    onToggleActive: () -> Unit,
    onToggleMode: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCenterRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf<Boolean?>(null) }
    val cardShape = RoundedCornerShape(12.dp)
    
    LaunchedEffect(connection.baseUrl, connection.apiKey) {
        if (!connection.baseUrl.isNullOrBlank() && !connection.apiKey.isNullOrBlank()) {
            status = chatClient.checkStatus(connection.baseUrl, connection.apiKey)
        }
    }

    Card(
        modifier = modifier.clip(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (connection.isActive == 1L) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .clickable {
                    onToggleActive()
                    onCenterRequest()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIndicator(status, modifier = Modifier.size(16.dp))
                
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        connection.name, 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        connection.provider, 
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            
            Text(
                connection.model ?: "None", 
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp)
            )

            val blueColor = Color(0xFF2196F3)
            val primaryColor = MaterialTheme.colorScheme.primary
            val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.Bottom
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleMode(connection.isChatCompletion != 1L) }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Text", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = connection.isChatCompletion == 1L,
                        onCheckedChange = { onToggleMode(it) },
                        modifier = Modifier.padding(horizontal = 8.dp).scale(0.65f),
                        thumbContent = { Box(Modifier) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryContainerColor,
                            checkedBorderColor = primaryColor,
                            uncheckedThumbColor = blueColor,
                            uncheckedTrackColor = blueColor.copy(alpha = 0.2f),
                            uncheckedBorderColor = blueColor,
                        )
                    )
                    Text("Chat", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

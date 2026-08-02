package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.Session
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val sessionDateTimeFormat: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
    monthNumber(Padding.NONE)
    char('/')
    day(Padding.NONE)
    char('/')
    yearTwoDigits(baseYear = 2000)
    char(' ')
    amPmHour(Padding.NONE)
    char(':')
    minute()
    amPmMarker("AM", "PM")
}

internal fun formatTimestampToDateTime(timestamp: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
    return sessionDateTimeFormat.format(dateTime)
}

@Composable
fun ChatManagerDialog(
    characterId: String,
    personaId: String,
    activeSessionId: String?,
    sessionRepository: SessionRepository,
    onDismissRequest: () -> Unit,
    onSessionSelected: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var expandedMenuSessionId by remember { mutableStateOf<String?>(null) }
    var sessionToRename by remember { mutableStateOf<Session?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }

    fun loadSessions() { coroutineScope.launch { sessions = sessionRepository.getSessionsForCharacter(characterId) } }
    LaunchedEffect(characterId) {
        // Clear the previous character's sessions immediately; otherwise the
        // dialog briefly shows the old list until the reload completes.
        sessions = emptyList()
        loadSessions()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Saved Chats", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { coroutineScope.launch { val newSessionId = sessionRepository.createNewSession(characterId, personaId); onSessionSelected(newSessionId) } }) { Icon(Icons.Default.Add, contentDescription = "New Chat") }
            }
        },
        text = {
            Box(modifier = Modifier.sizeIn(maxHeight = 280.dp, minWidth = 280.dp)) {
                if (sessions.isEmpty()) {
                    Text("No alternative chats found.", modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(sessions) { session ->
                            val isActive = session.id == activeSessionId
                            Card(colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth().clickable { onSessionSelected(session.id) }) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(text = session.title ?: "#${session.id.take(6)} ${formatTimestampToDateTime(session.lastTimestamp)}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                        if (isActive) Text(text = "Active Conversation", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary)
                                    }
                                    Box {
                                        IconButton(onClick = { expandedMenuSessionId = session.id }) { Icon(Icons.Default.MoreVert, contentDescription = "Chat Options", modifier = Modifier.size(20.dp)) }
                                        DropdownMenu(expanded = expandedMenuSessionId == session.id, onDismissRequest = { expandedMenuSessionId = null }) {
                                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }, onClick = { expandedMenuSessionId = null; sessionToRename = session; editTitleText = session.title ?: "" })
                                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }, onClick = { expandedMenuSessionId = null; sessionToDelete = session })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("Close") } }
    )

    if (sessionToRename != null) {
        val targetSession = sessionToRename!!
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Chat") },
            text = { Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { OutlinedTextField(value = editTitleText, onValueChange = { editTitleText = it }, label = { Text("Chat Title") }, placeholder = { Text("#${targetSession.id.take(6)}") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(onClick = { coroutineScope.launch { sessionRepository.updateSessionTitle(targetSession.id, editTitleText.ifBlank { null }); sessionToRename = null; loadSessions() } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { sessionToRename = null }) { Text("Cancel") } }
        )
    }

    if (sessionToDelete != null) {
        val targetSession = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Conversation?") },
            text = { Text("Are you sure you want to delete this chat session? All associated logs and swipe messages will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { coroutineScope.launch { sessionRepository.deleteSession(targetSession.id); if (targetSession.id == activeSessionId) { val remaining = sessions.filter { it.id != targetSession.id }; if (remaining.isNotEmpty()) onSessionSelected(remaining.first().id) else onSessionSelected("") } else { loadSessions() }; sessionToDelete = null } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") } }
        )
    }
}

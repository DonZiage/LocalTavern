package chat.donzi.localtavern.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.data.sync.SyncService
import kotlinx.coroutines.launch
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsSection(
    syncService: SyncService,
    syncRepository: SyncRepository,
    syncDiscovery: SyncDiscovery,
    onEnsureSyncRunning: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val syncState by syncService.state.collectAsState()
    val peers by syncService.observePeers().collectAsState(initial = emptyList())
    val deviceName by syncService.deviceName.collectAsState()

    var showSyncDialog by remember { mutableStateOf(false) }
    var showRotateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This device: $deviceName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "ID ${syncService.identity.deviceId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = "Encrypted P2P sync over your local network, with per-session keys and PIN-authenticated pairing. Pair two devices to share characters, chats and settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Pairing is two devices and a PIN: the host shows a QR code, the receiver scans it and types the PIN from the host's screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onEnsureSyncRunning()
                showSyncDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sync")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isNotEmpty()) {
            Text("Paired Devices", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                peers.forEach { peer ->
                    PeerRow(
                        peer = peer,
                        isSyncing = syncState.isSyncing,
                        onSync = {
                            onEnsureSyncRunning()
                            syncService.syncNowAsync(peer.deviceId)
                        },
                        onRemove = { scope.launch { syncRepository.deletePeer(peer.deviceId) } }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    onEnsureSyncRunning()
                    syncService.syncAllPeersAsync()
                },
                enabled = !syncState.isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (syncState.isSyncing) "Syncing…" else "Sync All")
            }
        }

        syncState.blobProgress?.let { progress ->
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (progress.totalBytes > 0L) progress.doneBytes.toFloat() / progress.totalBytes.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Syncing images… ${formatBytes(progress.doneBytes)} / ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Sync Key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "Rotating generates a fresh device key and invalidates all pairings — re-pair every device afterwards. Do this if a device was lost or compromised.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        OutlinedButton(
            onClick = { showRotateDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Rotate Sync Key…")
        }

        syncState.statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        syncState.syncError?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showSyncDialog) {
        SyncFlowDialog(
            syncService = syncService,
            syncDiscovery = syncDiscovery,
            onDismiss = { showSyncDialog = false }
        )
    }

    if (showRotateDialog) {
        RotateKeyDialog(
            syncService = syncService,
            onDismiss = { showRotateDialog = false }
        )
    }
}

@Composable
private fun PeerRow(
    peer: SyncPeer,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                val lastSync = peer.lastSyncAt
                Text(
                    text = if (lastSync > 0L) {
                        "Last synced ${relativeTime(lastSync)}"
                    } else {
                        peer.lastKnownAddress?.let { "Address: $it" } ?: "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSync, enabled = !isSyncing, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Sync, contentDescription = "Sync now", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove peer",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diffMs = (now - timestamp).coerceAtLeast(0)
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb >= 1024.0 -> "${(kb / 1024.0 * 10).toLong() / 10.0} MB"
        bytes >= 1024 -> "${(kb * 10).toLong() / 10.0} KB"
        else -> "$bytes B"
    }
}

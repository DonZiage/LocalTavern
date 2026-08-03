package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

    var showHostDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var showRotateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(modifier = Modifier.alpha(0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Device Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "This device: ${syncService.identity.deviceName}",
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                onEnsureSyncRunning()
                showHostDialog = true
            }) {
                Text("Host Pairing…")
            }
            OutlinedButton(onClick = {
                onEnsureSyncRunning()
                showConnectDialog = true
            }) {
                Text("Connect…")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (peers.isNotEmpty()) {
            Text("Paired Devices", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(peers, key = { it.deviceId }) { peer ->
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

    if (showHostDialog) {
        HostPairingDialog(
            syncService = syncService,
            syncState = syncState,
            onDismiss = { showHostDialog = false }
        )
    }

    if (showConnectDialog) {
        ConnectDialog(
            syncService = syncService,
            syncDiscovery = syncDiscovery,
            onDismiss = { showConnectDialog = false }
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

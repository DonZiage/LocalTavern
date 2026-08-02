package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.data.sync.SyncUiState
import kotlinx.coroutines.launch

// Shows the fingerprint of the peer that just paired. The SAME string must be
// visible on the other device; if they differ, someone intercepted the
// pairing and it must be cancelled.
@Composable
private fun FingerprintConfirmation(
    fingerprint: String,
    peerName: String,
    onConfirm: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Verify $peerName's fingerprint",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Compare this with the fingerprint shown on the other device. If they match, pairing is secure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Fingerprints match")
            }
        }
    }
}

@Composable
internal fun HostPairingDialog(
    syncService: SyncService,
    syncState: SyncUiState,
    onDismiss: () -> Unit
) {
    val pin = syncState.pairingPin

    LaunchedEffect(Unit) {
        if (syncState.pairingPin == null) {
            syncService.startPairing()
        }
    }

    AlertDialog(
        onDismissRequest = {
            syncService.cancelPairing()
            onDismiss()
        },
        title = { Text("Host Pairing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pendingFingerprint = syncState.pendingPeerFingerprint
                if (pendingFingerprint != null) {
                    FingerprintConfirmation(
                        fingerprint = pendingFingerprint,
                        peerName = syncState.pendingPeerName ?: "the other device",
                        onConfirm = { syncService.confirmFingerprint() }
                    )
                } else {
                    Text(
                        text = "Enter this PIN and the device address on the other device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pin != null) {
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Port: 47324",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    syncState.localAddresses.forEach { address ->
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (syncState.localAddresses.isEmpty()) {
                        Text(
                            text = "Address unknown — check your device's network settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    syncService.cancelPairing()
                    onDismiss()
                }
            ) { Text("Done") }
        }
    )
}

@Composable
internal fun ConnectDialog(
    syncService: SyncService,
    syncDiscovery: SyncDiscovery,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val syncState by syncService.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47324") }
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }

    val discoveredPeers by syncDiscovery.state.collectAsState()
    val discoveryActive = syncDiscovery.isActive

    val pendingFingerprint = syncState.pendingPeerFingerprint

    // Set when pairing succeeds; used to run the initial sync once the
    // fingerprint has been confirmed.
    var pairedPeerId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!isConnecting) onDismiss()
        },
        title = { Text("Connect to Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pendingFingerprint != null) {
                    FingerprintConfirmation(
                        fingerprint = pendingFingerprint,
                        peerName = syncState.pendingPeerName ?: "the other device",
                        onConfirm = {
                            syncService.confirmFingerprint()
                            val peerId = pairedPeerId
                            pairedPeerId = null
                            scope.launch {
                                status = if (peerId != null) {
                                    syncService.syncNow(peerId).fold(
                                        onSuccess = { "Paired and synced." },
                                        onFailure = { "Paired, but initial sync failed: ${it.message}" }
                                    )
                                } else {
                                    "Paired."
                                }
                            }
                            onDismiss()
                        }
                    )
                } else {
                    if (discoveryActive && discoveredPeers.isNotEmpty()) {
                        Text(
                            text = "Devices found on this network:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 140.dp)) {
                            items(discoveredPeers, key = { it.deviceId }) { peer ->
                                OutlinedButton(
                                    onClick = {
                                        host = peer.address
                                        port = peer.syncPort.toString()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text(peer.deviceName, style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            "${peer.address}:${peer.syncPort}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "Enter the host device's address and the pairing PIN it shows.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host (IP or hostname)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("Pairing PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("failed", ignoreCase = true) || it.contains("rejected", ignoreCase = true) || it.contains("Invalid", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (pendingFingerprint == null) {
                TextButton(
                    onClick = {
                        isConnecting = true
                        status = null
                        scope.launch {
                            val result = syncService.connectToDevice(host.trim(), port.trim().toIntOrNull() ?: 47324, pin.trim())
                            isConnecting = false
                            result.fold(
                                onSuccess = { peerId ->
                                    pairedPeerId = peerId
                                    // The dialog now shows the fingerprint for
                                    // out-of-band verification; the initial
                                    // sync runs after confirmation.
                                },
                                onFailure = { status = "Pairing failed: ${it.message}" }
                            )
                        }
                    },
                    enabled = host.isNotBlank() && pin.isNotBlank() && !isConnecting
                ) {
                    Text(if (isConnecting) "Connecting…" else "Pair & Verify")
                }
            } else {
                TextButton(
                    onClick = {
                        syncService.confirmFingerprint()
                        onDismiss()
                    },
                    enabled = !isConnecting
                ) { Text("Cancel") }
            }
        },
        dismissButton = {
            if (pendingFingerprint == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
internal fun RotateKeyDialog(
    syncService: SyncService,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isRotating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isRotating) onDismiss() },
        title = { Text("Rotate Sync Key?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A new device key will be generated. All current pairings become invalid and every paired device must be paired again. The fingerprint shown during each new pairing is the only way to be sure no one intercepted it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isRotating = true
                    scope.launch {
                        syncService.rotateIdentityKey()
                        isRotating = false
                        onDismiss()
                    }
                },
                enabled = !isRotating
            ) { Text("Rotate Key") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRotating) { Text("Cancel") }
        }
    )
}

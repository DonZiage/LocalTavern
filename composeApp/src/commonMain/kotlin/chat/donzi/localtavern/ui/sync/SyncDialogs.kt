package chat.donzi.localtavern.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.sync.DiscoveredPeer
import chat.donzi.localtavern.data.sync.DeviceName
import chat.donzi.localtavern.data.sync.PairPayload
import chat.donzi.localtavern.data.sync.SYNC_PORT
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.data.sync.launchQrScanner
import chat.donzi.localtavern.data.sync.supportsQrScanning
import kotlinx.coroutines.launch

// The two roles a device can take during pairing. The host advertises and
// shows the QR/PIN; the receiver scans or picks it and types the PIN.
private enum class SyncRole { Host, Receiver }

// Receiver-side sub-steps: find a host (scan QR / pick from the list), enter
// its info manually, then enter the PIN shown on the host's screen.
private enum class ReceiverStep { Find, Manual, Pin }

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

// Step 1 of the sync dialog: pick which role this device plays. Host on top,
// Receiver below.
@Composable
private fun RoleChoiceContent(
    onPickHost: () -> Unit,
    onPickReceiver: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Pick a role for this device: the host shares characters and settings, the receiver joins the host.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onPickHost,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Host", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("Show a QR code the other device scans", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = onPickReceiver,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Receiver", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("Scan the host's QR code to pair", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// The sync dialog: step 1 asks for the role, then the Host shows its QR/PIN
// (with a "Not connecting?" toggle for the manual info) and the Receiver
// goes through Find (scan QR / pick a device) -> Manual entry -> PIN.
@Composable
internal fun SyncFlowDialog(
    syncService: SyncService,
    syncDiscovery: SyncDiscovery,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val syncState by syncService.state.collectAsState()
    var step by remember { mutableStateOf<SyncRole?>(null) }

    val discoveredPeers by syncDiscovery.state.collectAsState()
    val discoveryActive = syncDiscovery.isActive

    val pendingFingerprint = syncState.pendingPeerFingerprint

    // This device's display name: editable here so it can be set the very
    // first time the user pairs. All pairing surfaces (QR, discovery,
    // hello, pair request/response) read it live.
    val deviceName by syncService.deviceName.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    // Receiver-side form state.
    var receiverStep by remember { mutableStateOf(ReceiverStep.Find) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(SYNC_PORT.toString()) }
    var pin by remember { mutableStateOf("") }
    var selectedDeviceName by remember { mutableStateOf<String?>(null) }
    var cameFromManual by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var pairedPeerId by remember { mutableStateOf<String?>(null) }

    // Host-side toggle: reveals the manual connection info next to the QR.
    var showHostInfo by remember { mutableStateOf(false) }

    LaunchedEffect(step) {
        if (step == SyncRole.Host && syncState.pairingPin == null) {
            syncService.startPairing()
        }
    }

    fun close() {
        syncService.cancelPairing()
        onDismiss()
    }

    fun backToRoleChoice() {
        syncService.cancelPairing()
        step = null
    }

    // Steps back one level depending on where we are: Host/Find -> role
    // choice, Manual -> Find, Pin -> wherever the Pin step came from.
    fun goBack() {
        when (step) {
            SyncRole.Host, null -> backToRoleChoice()
            SyncRole.Receiver -> when (receiverStep) {
                ReceiverStep.Find -> backToRoleChoice()
                ReceiverStep.Manual -> receiverStep = ReceiverStep.Find
                ReceiverStep.Pin -> receiverStep = if (cameFromManual) ReceiverStep.Manual else ReceiverStep.Find
            }
        }
    }

    fun resetReceiverForm() {
        receiverStep = ReceiverStep.Find
        host = ""
        port = SYNC_PORT.toString()
        pin = ""
        selectedDeviceName = null
        cameFromManual = false
        status = null
        statusIsError = false
        isConnecting = false
        pairedPeerId = null
        showHostInfo = false
    }

    fun applyPayload(payload: PairPayload) {
        host = payload.host
        port = payload.port.toString()
        selectedDeviceName = payload.deviceName.ifBlank { payload.deviceId }
        cameFromManual = false
        receiverStep = ReceiverStep.Pin
        status = "Host found: $selectedDeviceName. Now enter the PIN shown on its screen."
        statusIsError = false
    }

    fun selectDiscoveredPeer(peer: DiscoveredPeer) {
        host = peer.address
        port = peer.syncPort.toString()
        selectedDeviceName = peer.deviceName
        cameFromManual = false
        receiverStep = ReceiverStep.Pin
        status = "Selected: ${peer.deviceName}. Enter the PIN shown on its screen."
        statusIsError = false
    }

    fun pair() {
        isConnecting = true
        status = null
        statusIsError = false
        scope.launch {
            val result = syncService.connectToDevice(host.trim(), port.trim().toIntOrNull() ?: SYNC_PORT, pin.trim())
            isConnecting = false
            result.fold(
                onSuccess = { peerId -> pairedPeerId = peerId },
                onFailure = {
                    status = "Pairing failed: ${it.message}"
                    statusIsError = true
                }
            )
        }
    }

    // Set when pairing succeeds; used to run the initial sync once the
    // fingerprint has been confirmed.
    fun confirmFingerprintAndSync() {
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

    AlertDialog(
        onDismissRequest = { if (!isConnecting) close() },
        title = {
            Text(
                when (step) {
                    null -> "Sync"
                    SyncRole.Host -> "You are the Host"
                    SyncRole.Receiver -> when (receiverStep) {
                        ReceiverStep.Find -> "You are the Receiver"
                        ReceiverStep.Manual -> "Enter the host's info"
                        ReceiverStep.Pin -> "Enter the pairing PIN"
                    }
                }
            )
        },
        text = {
            when (step) {
                null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoleChoiceContent(
                        onPickHost = {
                            resetReceiverForm()
                            step = SyncRole.Host
                        },
                        onPickReceiver = {
                            resetReceiverForm()
                            step = SyncRole.Receiver
                        }
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "This device's name",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = deviceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        nameDraft = deviceName
                                        editingName = true
                                    }
                                ) { Text("Rename") }
                            }
                            if (editingName) {
                                OutlinedTextField(
                                    value = nameDraft,
                                    onValueChange = { nameDraft = it.take(DeviceName.MAX_LENGTH) },
                                    label = { Text("Device name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { editingName = false }) { Text("Cancel") }
                                    Button(
                                        onClick = {
                                            scope.launch { syncService.renameDevice(nameDraft.trim()) }
                                            editingName = false
                                        },
                                        enabled = nameDraft.isNotBlank()
                                    ) { Text("Save") }
                                }
                            }
                            Text(
                                text = "Shown to other devices while pairing and in the paired-device list. Changing it never affects pairing or sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                SyncRole.Host -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pin = syncState.pairingPin
                    val qrPayload = remember(syncState.pairingPin) { syncService.pairingQrPayload() }
                    if (pendingFingerprint != null) {
                        FingerprintConfirmation(
                            fingerprint = pendingFingerprint,
                            peerName = syncState.pendingPeerName ?: "the other device",
                            onConfirm = { syncService.confirmFingerprint() }
                        )
                    } else if (pin != null && qrPayload != null) {
                        Text(
                            text = "On the other device choose \u201cReceiver\u201d, scan this QR code, then enter the PIN below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        QrCodeImage(text = qrPayload)
                        Text(
                            text = "The QR and PIN expire after 5 minutes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        TextButton(onClick = { showHostInfo = !showHostInfo }) {
                            Text(if (showHostInfo) "Hide connection info" else "Not connecting?")
                        }
                        if (showHostInfo) {
                            if (syncState.localAddresses.isEmpty()) {
                                Text(
                                    text = "Address unknown — check your device's network settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            } else {
                                Text(
                                    text = "To connect manually, enter one of these addresses on the other device:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                syncState.localAddresses.forEach { address ->
                                    Text(
                                        text = address,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "Port: $SYNC_PORT",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
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
                        if (syncState.localAddresses.isEmpty()) {
                            Text(
                                text = "Address unknown — check your device's network settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else {
                            syncState.localAddresses.forEach { address ->
                                Text(
                                    text = address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                SyncRole.Receiver -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pendingFingerprint != null) {
                        FingerprintConfirmation(
                            fingerprint = pendingFingerprint,
                            peerName = syncState.pendingPeerName ?: "the other device",
                            onConfirm = { confirmFingerprintAndSync() }
                        )
                    } else {
                        when (receiverStep) {
                            // Step 2: find the host — scan its QR or pick it
                            // from the list of devices found on the network.
                            ReceiverStep.Find -> {
                                if (supportsQrScanning) {
                                    Button(
                                        onClick = {
                                            status = null
                                            launchQrScanner { scanned ->
                                                if (scanned == null) {
                                                    status = "Scan cancelled."
                                                    statusIsError = false
                                                } else {
                                                    val payload = PairPayload.fromQrText(scanned)
                                                    if (payload == null) {
                                                        status = "Not a LocalTavern pairing QR code."
                                                        statusIsError = true
                                                    } else {
                                                        applyPayload(payload)
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Scan the host's QR code")
                                    }
                                } else {
                                    Text(
                                        text = "Scan this host's QR code with your phone, or pick the host below.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (discoveryActive && discoveredPeers.isNotEmpty()) {
                                    Text(
                                        text = "Or select a device on this network:",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 130.dp)) {
                                        items(discoveredPeers, key = { it.deviceId }) { peer ->
                                            OutlinedButton(
                                                onClick = { selectDiscoveredPeer(peer) },
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

                                TextButton(onClick = { receiverStep = ReceiverStep.Manual }) {
                                    Text("Can't find the device?")
                                }
                            }
                            // Step 2b: the host was not found automatically —
                            // type its address and port by hand.
                            ReceiverStep.Manual -> {
                                Text(
                                    text = "Enter the host's address and port as shown on its screen:",
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
                            }
                            // Step 3: enter the PIN shown on the host's screen.
                            ReceiverStep.Pin -> {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "Connecting to",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        selectedDeviceName?.let {
                                            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text(
                                            text = "$host:$port",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = pin,
                                    onValueChange = { pin = it },
                                    label = { Text("Pairing PIN from host's screen") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                null -> TextButton(onClick = { close() }) { Text("Cancel") }
                SyncRole.Host -> TextButton(onClick = { close() }) { Text("Done") }
                SyncRole.Receiver -> {
                    if (pendingFingerprint != null) {
                        TextButton(onClick = { close() }) { Text("Cancel") }
                    } else {
                        when (receiverStep) {
                            ReceiverStep.Find -> {
                                // Picking a device or scanning moves on; no
                                // confirm action needed here.
                            }
                            ReceiverStep.Manual -> TextButton(
                                onClick = {
                                    cameFromManual = true
                                    receiverStep = ReceiverStep.Pin
                                },
                                enabled = host.isNotBlank()
                            ) { Text("Continue") }
                            ReceiverStep.Pin -> TextButton(
                                onClick = { pair() },
                                enabled = host.isNotBlank() && pin.isNotBlank() && !isConnecting
                            ) {
                                Text(if (isConnecting) "Pairing…" else "Pair")
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (step != null && !isConnecting) {
                TextButton(onClick = { goBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back")
                }
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

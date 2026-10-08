package chat.donzi.localtavern.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.sync.DiscoveredPeer
import chat.donzi.localtavern.data.sync.PairPayload
import chat.donzi.localtavern.data.sync.PairingStage
import chat.donzi.localtavern.data.sync.SYNC_PORT
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.data.sync.launchQrScanner
import chat.donzi.localtavern.data.sync.supportsQrScanning
import chat.donzi.localtavern.data.sync.validateFetchAddress
import chat.donzi.localtavern.utils.KeepScreenOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PIN_LENGTH = 6

// The two roles a device can take during pairing. The host advertises and
// shows the QR/PIN; the receiver scans or picks it and types the PIN.
private enum class SyncRole { Host, Receiver }

// Receiver-side sub-steps: find a host (scan QR / pick from the list), enter
// its info manually, then enter the PIN shown on the host's screen.
private enum class ReceiverStep { Find, Manual, Pin }

// Receiver-side state after the fingerprint was confirmed: the initial sync
// runs inside the dialog (with a spinner) instead of the dialog silently
// closing and the sync failing in the background.
private enum class PostPairState { Syncing, Success, Failed }

// An animated "waiting" line: the app never sits silently while something is
// in flight — every wait shows a spinner so the user knows the screen is
// live.
@Composable
private fun WaitingRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// A spinner card shown while the app is doing something the user waits on
// (initial sync): a full-width surface so the state change is unmissable.
@Composable
private fun WaitingCard(title: String, detail: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Column(horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Reveals the raw addresses a receiver can type in manually, next to the
// QR/PIN. Hidden by default so the QR-first host screen stays clean.
@Composable
private fun HostConnectionInfoToggle(
    show: Boolean,
    onToggle: () -> Unit,
    addresses: List<String>
) {
    TextButton(onClick = onToggle) {
        Text(if (show) "Hide connection info" else "Show connection info")
    }
    if (show) {
        if (addresses.isEmpty()) {
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
            addresses.forEach { address ->
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
}

// Shows the fingerprint of the peer that just paired. The SAME string must be
// visible on the other device; if they differ, someone intercepted the
// pairing and it must be cancelled.
@Composable
private fun FingerprintConfirmation(
    fingerprint: String,
    peerName: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit
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
                text = "Compare this with the fingerprint shown on the other device. Only continue when both screens show the same code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Fingerprints match")
            }
            TextButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text("Don't match — remove this device")
            }
        }
    }
}

// Step 1 of the sync dialog: pick which role this device plays. Host on top,
// Receiver below. The two devices must pick OPPOSITE roles.
@Composable
private fun RoleChoiceContent(
    onPickHost: () -> Unit,
    onPickReceiver: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Pick opposite roles on your two devices: one shows a code, the other enters it.",
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
                Text("Show pairing code", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("The other device scans or types it", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = onPickReceiver,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Enter pairing code", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("Scan the other device's code", style = MaterialTheme.typography.bodySmall)
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

    // Display-only device name (renaming lives in App Settings — the dialog
    // stays focused on pairing).
    val deviceName by syncService.deviceName.collectAsState()

    // Receiver-side form state. Manual entry is ONE field ("192.168.1.5" or
    // "192.168.1.5:47324") — the port defaults and rarely needs changing.
    var receiverStep by remember { mutableStateOf(ReceiverStep.Find) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(SYNC_PORT.toString()) }
    var manualAddress by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var selectedDeviceName by remember { mutableStateOf<String?>(null) }
    var cameFromManual by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var pairedPeerId by remember { mutableStateOf<String?>(null) }

    // Post-fingerprint state: the initial sync runs INSIDE the dialog with a
    // spinner, so pairing never ends in a silent background failure.
    var postPair by remember { mutableStateOf<PostPairState?>(null) }
    var postPairError by remember { mutableStateOf<String?>(null) }
    var postPairPeerId by remember { mutableStateOf<String?>(null) }

    // Host-side toggle: reveals the manual connection info next to the QR.
    var showHostInfo by remember { mutableStateOf(false) }

    // Pairing crypto and the initial bulk sync can run for minutes on a big
    // library: keep the screen on so the OS cannot sleep the app mid-process.
    KeepScreenOn(isConnecting || postPair == PostPairState.Syncing)

    LaunchedEffect(step) {
        if (step == SyncRole.Host && syncState.pairingPin == null) {
            syncService.startPairing()
        }
    }

    fun close() {
        scope.launch {
            syncService.dismissPairing()
            onDismiss()
        }
    }

    fun backToRoleChoice() {
        // Leaving the fingerprint step removes the unverified peer (see
        // SyncService.cancelPairing): a device that was never verified must
        // not linger as trusted.
        scope.launch { syncService.dismissPairing() }
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
        manualAddress = ""
        pin = ""
        selectedDeviceName = null
        cameFromManual = false
        status = null
        statusIsError = false
        isConnecting = false
        pairedPeerId = null
        postPair = null
        postPairError = null
        postPairPeerId = null
        showHostInfo = false
    }

    // Parses the single manual-address field: "192.168.1.5" or
    // "192.168.1.5:47324". Returns null when it is not a bare host[:port].
    fun parseManualAddress(input: String): Pair<String, Int>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        // validateFetchAddress expects "host:port" and rejects schemes,
        // paths, credentials and whitespace — reuse it as the single gate.
        val withPort = if (':' in trimmed) trimmed else "$trimmed:$SYNC_PORT"
        return validateFetchAddress(withPort)
    }

    fun applyPayload(payload: PairPayload) {
        host = payload.host
        port = payload.port.toString()
        selectedDeviceName = payload.deviceName.ifBlank { payload.deviceId }
        cameFromManual = false
        receiverStep = ReceiverStep.Pin
        status = "Host found: $selectedDeviceName. Now enter the PIN shown on its screen."
        statusIsError = false
        // Tell the host a receiver has arrived so its QR is replaced by the
        // PIN immediately — both users stop juggling two things at once.
        syncService.announceConnection(payload.host, payload.port)
    }

    fun selectDiscoveredPeer(peer: DiscoveredPeer) {
        host = peer.address
        port = peer.syncPort.toString()
        selectedDeviceName = peer.deviceName
        cameFromManual = false
        receiverStep = ReceiverStep.Pin
        status = "Selected: ${peer.deviceName}. Enter the PIN shown on its screen."
        statusIsError = false
        syncService.announceConnection(peer.address, peer.syncPort)
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

    fun runInitialSync() {
        postPairError = null
        postPair = PostPairState.Syncing
        val peerId = postPairPeerId
        scope.launch {
            val result = if (peerId != null) syncService.syncNow(peerId) else Result.success("Paired.")
            if (result.isSuccess) {
                postPair = PostPairState.Success
                delay(1200)
                close()
            } else {
                postPair = PostPairState.Failed
                postPairError = result.exceptionOrNull()?.message
            }
        }
    }

    // Set when pairing succeeds; used to run the initial sync once the
    // fingerprint has been confirmed. The dialog STAYS OPEN while the sync
    // runs (spinner), shows the outcome, and only closes on success — a
    // failed initial sync can no longer happen silently in the background.
    fun confirmFingerprintAndSync() {
        syncService.confirmFingerprint()
        postPairPeerId = pairedPeerId ?: postPairPeerId
        pairedPeerId = null
        runInitialSync()
    }

    fun retryInitialSync() {
        runInitialSync()
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting && postPair != PostPairState.Syncing) close() },
        title = {
            Text(
                when (step) {
                    null -> "Pair devices"
                    SyncRole.Host -> "Show pairing code"
                    SyncRole.Receiver -> when (receiverStep) {
                        ReceiverStep.Find -> "Enter pairing code"
                        ReceiverStep.Manual -> "Enter address manually"
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
                    Text(
                        text = "This device ($deviceName) appears under this name on the other screen. To rename it, use App Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SyncRole.Host -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pin = syncState.pairingPin
                    val qrPayload = remember(syncState.pairingPin) { syncService.pairingQrPayload() }
                    when {
                        // The receiver submitted the PIN: compare fingerprints.
                        // The other side may complete the pairing on its own —
                        // then this card is replaced by the success screen live.
                        pendingFingerprint != null -> {
                            FingerprintConfirmation(
                                fingerprint = pendingFingerprint,
                                peerName = syncState.pendingPeerName ?: "the other device",
                                onConfirm = { syncService.confirmFingerprint() },
                                onReject = { scope.launch { syncService.rejectPendingPairing() } }
                            )
                            Text(
                                text = "The other device entered the PIN. Once the fingerprints are compared, either device can complete the pairing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Fingerprint confirmed (either side) or the receiver's
                        // first authenticated exchange arrived: pairing done.
                        syncState.pairingStage == PairingStage.Paired -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Paired with ${syncState.pairedPeerName ?: "the other device"}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Pairing complete — syncing continues in the background.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { close() }) { Text("Done") }
                        }
                        // A receiver announced itself: the QR is replaced by
                        // the PIN — never both on screen at once.
                        syncState.pairingStage == PairingStage.ReceiverConnected -> {
                            val receiver = syncState.connectedPeerName
                            Text(
                                text = if (receiver != null)
                                    "Receiver connected ($receiver) — enter this PIN on the other device:"
                                else
                                    "Receiver connected — enter this PIN on the other device:",
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
                                text = "The PIN expires after 5 minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            HostConnectionInfoToggle(
                                show = showHostInfo,
                                onToggle = { showHostInfo = !showHostInfo },
                                addresses = syncState.localAddresses
                            )
                        }
                        // Waiting for a receiver: QR only, PIN stays hidden.
                        pin != null && qrPayload != null -> {
                            Text(
                                text = "On the other device choose \u201cEnter pairing code\u201d and scan this QR code.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            QrCodeImage(text = qrPayload)
                            WaitingRow("Waiting for the other device to connect…")
                            Text(
                                text = "The QR expires after 5 minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            HostConnectionInfoToggle(
                                show = showHostInfo,
                                onToggle = { showHostInfo = !showHostInfo },
                                addresses = syncState.localAddresses
                            )
                        }
                        // No LAN address is known, so no QR can be shown: the
                        // PIN and addresses are the only way in.
                        else -> {
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
                }
                SyncRole.Receiver -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // After the fingerprint is confirmed the initial sync runs
                    // HERE, with a spinner — never silently in the background.
                    when (postPair) {
                        PostPairState.Syncing -> WaitingCard(
                            title = "Syncing with ${selectedDeviceName ?: "the host"}…",
                            detail = "Exchanging data for the first time."
                        )
                        PostPairState.Success -> Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Paired and synced with ${selectedDeviceName ?: "the host"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "All devices are up to date. This screen closes automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        PostPairState.Failed -> Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Initial sync failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = postPairError ?: "Unknown error.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { retryInitialSync() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Retry sync")
                                }
                            }
                        }
                        null -> {
                            if (pendingFingerprint != null) {
                                FingerprintConfirmation(
                                    fingerprint = pendingFingerprint,
                                    peerName = syncState.pendingPeerName ?: "the other device",
                                    onConfirm = { confirmFingerprintAndSync() },
                                    onReject = { scope.launch { syncService.rejectPendingPairing() } }
                                )
                                Text(
                                    text = "Confirming also starts the initial sync with the host.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        Text("Scan the pairing code")
                                    }
                                } else {
                                    Text(
                                        text = "Scan the pairing code with your phone, or pick the device below.",
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

                                TextButton(onClick = {
                                    manualAddress = if (host.isNotBlank()) "$host:$port" else ""
                                    receiverStep = ReceiverStep.Manual
                                }) {
                                    Text("Enter address manually")
                                }
                            }
                            // Step 2b: the code was not scanned — type the
                            // address shown on the other screen by hand.
                            ReceiverStep.Manual -> {
                                Text(
                                    text = "Enter the address shown on the other screen (port is optional, default $SYNC_PORT):",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = manualAddress,
                                    onValueChange = { manualAddress = it },
                                    label = { Text("Address, e.g. 192.168.1.5") },
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
                                    // Digits only, exactly PIN_LENGTH: the PIN
                                    // is a 6-digit number, and the numeric
                                    // keypad (NumberPassword) is what deploys
                                    // on mobile for it.
                                    onValueChange = { pin = it.filter { char -> char.isDigit() }.take(PIN_LENGTH) },
                                    label = { Text("Pairing PIN from host's screen") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
                }
            }
        },
        confirmButton = {
            when (step) {
                null -> TextButton(onClick = { close() }) { Text("Cancel") }
                SyncRole.Host -> TextButton(onClick = { close() }) { Text("Done") }
                SyncRole.Receiver -> {
                    when {
                        // Initial sync in flight: no confirm action, dialog is
                        // locked until it settles.
                        postPair == PostPairState.Syncing -> Unit
                        // Success/failure reached: a Done button closes it.
                        postPair != null -> TextButton(onClick = { close() }) { Text("Done") }
                        pendingFingerprint != null -> TextButton(onClick = { close() }) { Text("Cancel") }
                        else -> when (receiverStep) {
                            ReceiverStep.Find -> {
                                // Picking a device or scanning moves on; no
                                // confirm action needed here.
                            }
                            ReceiverStep.Manual -> TextButton(
                                onClick = {
                                    val parsed = parseManualAddress(manualAddress)
                                    if (parsed == null) {
                                        status = "Enter a valid address, e.g. 192.168.1.5."
                                        statusIsError = true
                                    } else {
                                        host = parsed.first
                                        port = parsed.second.toString()
                                        selectedDeviceName = "$host:$port"
                                        cameFromManual = true
                                        receiverStep = ReceiverStep.Pin
                                        status = null
                                        statusIsError = false
                                        // Tell the host a receiver is on its way so
                                        // its QR is replaced by the PIN.
                                        syncService.announceConnection(host, parsed.second)
                                    }
                                },
                                enabled = manualAddress.isNotBlank()
                            ) { Text("Continue") }
                            ReceiverStep.Pin -> TextButton(
                                onClick = { pair() },
                                enabled = host.isNotBlank() && pin.isNotBlank() && !isConnecting
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isConnecting) "Pairing…" else "Pair")
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (step != null && !isConnecting && postPair != PostPairState.Syncing) {
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

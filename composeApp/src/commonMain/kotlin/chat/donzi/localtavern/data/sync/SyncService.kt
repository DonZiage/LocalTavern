package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.SyncPeer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

data class SyncUiState(
    val isServerRunning: Boolean = false,
    val pairingPin: String? = null,
    val pairingExpiresAt: Long? = null,
    val localAddresses: List<String> = emptyList(),
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val syncError: String? = null,
    // Fingerprint of the peer that just paired (host and client side), shown
    // for out-of-band comparison until the user confirms it.
    val pendingPeerFingerprint: String? = null,
    val pendingPeerName: String? = null,
    // Progress of an in-flight image-blob transfer (null when idle).
    val blobProgress: BlobTransferProgress? = null
)

// Bytes transferred so far vs. total bytes of the current blob transfer.
data class BlobTransferProgress(
    val doneBytes: Long,
    val totalBytes: Long
)

const val SYNC_PORT = 47324
const val SYNC_DISCOVERY_PORT = 47325
const val CHUNK_BYTES = 512 * 1024
internal const val PAIRING_TTL_MS = 5 * 60 * 1000L
internal const val MAX_PAIRING_ATTEMPTS = 5

// The sync server winds down when it goes quiet: after this long without any
// sync traffic, pairing attempt or explicit engagement it stops listening
// (and the app stops broadcasting on the LAN). A stopped server is invisible
// to the user — every sync UI action restarts it via ensureSyncRunning().
private const val SYNC_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
private const val IDLE_WATCHDOG_INTERVAL_MS = 30_000L

// Facade over the sync stack: owns the server lifecycle (start/stop, idle
// watchdog), the device identity (rename/rotate), the pairing flow and the
// delta exchanges. The protocol mechanics live in SyncPairing (PIN pairing),
// SyncExchange (delta round trips) and SyncBlobTransfer (out-of-band image
// blobs); this class wires them together and exposes the SyncUiState.
class SyncService(
    var identity: SyncIdentity,
    private val crypto: SyncCrypto,
    private val repository: SyncRepository,
    private val identityStore: SyncIdentityStore,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val port: Int = SYNC_PORT,
    private val localAddressesProvider: () -> List<String> = { emptyList() },
    private val onServerStopped: () -> Unit = {},
    // Content-addressed blob store: serves image blobs to peers and holds
    // blobs fetched from them. Null (tests) disables out-of-band transfers;
    // envelope refs still sync, but no bytes move.
    private val blobStore: BlobStore? = null
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    // Live display name of this device: renamed via renameDevice(), read by
    // the UI (settings + pairing) so it recomposes on change.
    private val _deviceName = MutableStateFlow(identity.deviceName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    // Marks sync activity so the idle watchdog keeps the server alive.
    private fun noteActivity() {
        lastActivityAt = Clock.System.now().toEpochMilliseconds()
    }

    private val pairing = SyncPairing(
        crypto = crypto,
        repository = repository,
        state = _state,
        identityProvider = { identity },
        port = port,
        localAddressesProvider = localAddressesProvider,
        httpClient = httpClient,
        ensureServerRunning = { startServer() },
        noteActivity = { noteActivity() }
    )

    private val channelKeys = SyncChannelKeys(crypto) { identity }

    private val blobTransfer = SyncBlobTransfer(
        crypto = crypto,
        repository = repository,
        state = _state,
        identityProvider = { identity },
        httpClient = httpClient,
        blobStore = blobStore,
        channelKeys = channelKeys,
        noteActivity = { noteActivity() }
    )

    private val exchange = SyncExchange(
        crypto = crypto,
        repository = repository,
        identityProvider = { identity },
        httpClient = httpClient,
        scope = scope,
        blobStore = blobStore,
        channelKeys = channelKeys,
        blobTransfer = blobTransfer,
        advertisedFetchAddress = { pairing.advertisedFetchAddress() },
        noteActivity = { noteActivity() }
    )

    // High-water mark of the most recent sync/pairing activity; the idle
    // watchdog stops the server when this goes quiet for SYNC_IDLE_TIMEOUT_MS.
    private var lastActivityAt = 0L
    private var idleWatchdog: Job? = null

    val server = SyncServer(
        port = port,
        hello = { HelloResponse(deviceId = identity.deviceId, deviceName = identity.deviceName) },
        onPair = { request, remoteHost -> pairing.handlePairRequest(request, remoteHost) },
        onExchange = { fromDeviceId, payload, ephemeralPublicKey -> exchange.handleExchange(fromDeviceId, payload, ephemeralPublicKey) },
        onBlobFetch = { fromDeviceId, payload, ephemeralPublicKey -> blobTransfer.handleBlobFetch(fromDeviceId, payload, ephemeralPublicKey) }
    )

    fun startServer() {
        lastActivityAt = Clock.System.now().toEpochMilliseconds()
        server.start()
        _state.update { it.copy(isServerRunning = true, localAddresses = localAddressesProvider()) }
        startIdleWatchdog()
    }

    fun stopServer() {
        idleWatchdog?.cancel()
        idleWatchdog = null
        server.stop()
        pairing.resetOnServerStop()
        _state.update { it.copy(isServerRunning = false, pairingPin = null) }
        onServerStopped()
    }

    // While the server runs, periodically checks whether it has been idle
    // (and no pairing PIN is still valid — a host showing its QR/PIN is
    // actively waiting for a receiver). When both are quiet, the server
    // stops; the container's onServerStopped callback drops LAN discovery.
    private fun startIdleWatchdog() {
        if (idleWatchdog != null) return
        idleWatchdog = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(IDLE_WATCHDOG_INTERVAL_MS)
                val now = Clock.System.now().toEpochMilliseconds()
                val pinStillValid = pairing.hasActivePin(now)
                if (!pinStillValid && server.isRunning && now - lastActivityAt >= SYNC_IDLE_TIMEOUT_MS) {
                    _state.update {
                        it.copy(statusMessage = "Sync server stopped after being idle — it restarts automatically when you sync.")
                    }
                    stopServer()
                }
            }
        }
    }

    fun observePeers(): Flow<List<SyncPeer>> = repository.observePeers()

    // ---------- Pairing (delegated) ----------

    fun startPairing() = pairing.startPairing()

    fun cancelPairing() = pairing.cancelPairing()

    /**
     * The text to encode in the pairing QR code: this device's address, id and
     * name. Null when no LAN address is known (no QR can be shown then).
     */
    fun pairingQrPayload(): String? = pairing.pairingQrPayload()

    /** Acknowledges the out-of-band fingerprint comparison after pairing. */
    fun confirmFingerprint() = pairing.confirmFingerprint()

    suspend fun connectToDevice(host: String, port: Int, pin: String): Result<String> =
        pairing.connectToDevice(host, port, pin)

    // ---------- Sync exchange (delegated) ----------

    suspend fun syncNow(peerId: String): Result<String> = exchange.syncNow(peerId)

    // ---------- Display name ----------

    /**
     * Renames THIS device (display-only). The device id and X25519 keypair
     * are untouched, so pairings, shared secrets and sync cursors stay
     * valid. The name is persisted locally and propagates to paired devices
     * inside the authenticated envelope of the next sync (see SyncEnvelope
     * fromDeviceName); plaintext surfaces like /hello, the discovery
     * announcement and the pairing QR always reflect it immediately.
     */
    suspend fun renameDevice(newName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitized = DeviceName.sanitize(newName) ?: error("Name must not be empty.")
            val renamed = identity.withName(sanitized)
            identityStore.save(SyncIdentity.serialize(renamed))
            identity = renamed
            _deviceName.value = sanitized
            _state.update { it.copy(statusMessage = "Device renamed to \"$sanitized\".", syncError = null) }
            sanitized
        }.onFailure { error ->
            _state.update { it.copy(syncError = error.message, statusMessage = null) }
        }
    }

    // ---------- Identity rotation ----------

    /**
     * Regenerates the device's X25519 identity keypair (same device id and
     * name) and invalidates every existing pairing. All devices must be
     * re-paired afterwards; old pairings can no longer authenticate.
     */
    suspend fun rotateIdentityKey(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val newIdentity = SyncIdentity.create(deviceName = identity.deviceName, crypto = crypto, deviceId = identity.deviceId)
            identityStore.save(SyncIdentity.serialize(newIdentity))
            identity = newIdentity
            repository.clearAllPeers()
            _state.update { it.copy(statusMessage = "Sync key rotated. Re-pair your devices.", syncError = null) }
            "Sync key rotated. Re-pair your devices."
        }.onFailure { error ->
            _state.update { it.copy(syncError = error.message, statusMessage = null) }
        }
    }

    fun syncNowAsync(peerId: String) {
        scope.launch {
            _state.update { it.copy(isSyncing = true, syncError = null) }
            val result = syncNow(peerId)
            _state.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = result.getOrNull() ?: "Sync failed.",
                    syncError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun syncAllPeersAsync() {
        scope.launch {
            _state.update { it.copy(isSyncing = true, syncError = null) }
            val peers = repository.getPeers()
            val results = peers.map { peer -> peer.deviceId to syncNow(peer.deviceId) }
            val failures = results.filter { it.second.isFailure }
            _state.update {
                it.copy(
                    isSyncing = false,
                    statusMessage = if (failures.isEmpty()) "All devices synced." else "Synced ${results.size - failures.size}/${results.size} devices.",
                    syncError = failures.firstOrNull()?.second?.exceptionOrNull()?.message
                )
            }
        }
    }
}

package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.ConflictEvent
import chat.donzi.localtavern.data.database.SyncPeer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

// Where the HOST is in the pairing sequence. The UI renders a different
// screen per stage (QR -> PIN -> fingerprint -> paired) so the QR and the PIN
// never share a screen, and so a receiver's actions are reflected on the
// host's screen as they happen.
enum class PairingStage {
    /** No pairing session active. */
    Idle,

    /** Host generated a PIN and is showing the QR, waiting for a receiver. */
    WaitingForReceiver,

    /** A receiver announced itself (GET /hello?announce=): QR is replaced by the PIN. */
    ReceiverConnected,

    /** The receiver submitted the PIN and the pair request succeeded: fingerprint card shown. */
    VerifyingPin,

    /** Fingerprint confirmed on BOTH devices: pairing done, syncing may start. */
    Paired
}

data class SyncUiState(
    val isServerRunning: Boolean = false,
    val pairingPin: String? = null,
    val pairingExpiresAt: Long? = null,
    val localAddresses: List<String> = emptyList(),
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val syncError: String? = null,
    // Pairing wizard progress on the host side (see PairingStage).
    val pairingStage: PairingStage = PairingStage.Idle,
    // Display-only name of the receiver that announced itself (sanitized).
    val connectedPeerName: String? = null,
    // Fingerprint of the peer that just paired (host and client side), shown
    // for out-of-band comparison until BOTH users confirm it. While it is
    // set, this device refuses to INITIATE exchanges with that peer — the
    // peer is not yet trusted — and incoming exchanges do NOT complete the
    // pairing either: one side confirming must never be enough for data to
    // flow. Only the mutual handshake (both sides confirmed, see
    // SyncPairing.confirmFingerprint / onPeerFingerprintConfirmed) clears
    // it and opens sync.
    val pendingPeerFingerprint: String? = null,
    val pendingPeerName: String? = null,
    val pendingPeerDeviceId: String? = null,
    // The two halves of the fingerprint handshake, for the pending peer:
    // this device's user confirmed the code matches, and the peer's user
    // confirmed it on their screen (arrived as an authenticated
    // POST /pair-confirm). Pairing completes only when both are true.
    val fingerprintConfirmedByMe: Boolean = false,
    val fingerprintConfirmedByPeer: Boolean = false,
    // Name of the peer once pairing completed (survives the pendingPeer*
    // clear, so the host's success screen can greet the receiver by name).
    val pairedPeerName: String? = null,
    // Progress of an in-flight image-blob transfer (null when idle).
    val blobProgress: BlobTransferProgress? = null,
    // Progress of an in-flight delta sync (null when idle). Updated after
    // every sync round and every applied batch, so even a multi-minute first
    // sync of a big library always shows a live, determinate progress bar.
    val syncProgress: SyncProgress? = null,
    // Device id of the peer the current sync is talking to (null when idle).
    val syncPeerId: String? = null
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
// Fingerprint-confirmation delivery: the confirming side retries sending
// its confirmation while the handshake is still open.
private const val CONFIRM_RETRY_DELAY_MS = 5_000L
private const val CONFIRM_MAX_ATTEMPTS = 24
// Plaintext of the authenticated POST /pair-confirm payload: decrypting it
// under the paired channel key IS the authentication; the bytes themselves
// carry no data.
private val PAIR_CONFIRM_PAYLOAD = "confirmed".encodeToByteArray()

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
    // NOTE: the parameter is deliberately NOT named `identity`: a constructor
    // parameter with the same name as this class's `identity` property would
    // shadow it inside the class-body lambdas (pairing/exchange/channelKeys
    // providers), which would then capture the initial value forever and
    // renames would never reach outbound envelopes.
    initialIdentity: SyncIdentity,
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
    private val blobStore: BlobStore? = null,
    // Idle-watchdog timing, overridable so tests can prove the server winds
    // down in seconds instead of minutes.
    private val idleTimeoutMs: Long = SYNC_IDLE_TIMEOUT_MS,
    private val watchdogIntervalMs: Long = IDLE_WATCHDOG_INTERVAL_MS
) {
    // The identity is mutated by renameDevice/rotateIdentityKey on
    // Dispatchers.IO while server handler threads and the discovery
    // deviceNameProvider read it. It lives in a StateFlow (not a plain var):
    // MutableStateFlow.value is safe to read/write from any thread on every
    // target, which a plain field would not be in common code.
    private val identityFlow = MutableStateFlow(initialIdentity)
    var identity: SyncIdentity
        get() = identityFlow.value
        private set(value) { identityFlow.value = value }

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
        hello = { announcedName ->
            pairing.onHello(announcedName)
            HelloResponse(deviceId = identity.deviceId, deviceName = identity.deviceName)
        },
        onPair = { request, remoteHost -> pairing.handlePairRequest(request, remoteHost) },
        onPairConfirm = { fromDeviceId, payload, ephemeralPublicKey, exchangeId ->
            handlePairConfirm(fromDeviceId, payload, ephemeralPublicKey, exchangeId)
        },
        onExchange = { fromDeviceId, payload, ephemeralPublicKey, exchangeId ->
            exchange.handleExchange(fromDeviceId, payload, ephemeralPublicKey, exchangeId)
        },
        onBlobFetch = { fromDeviceId, payload, ephemeralPublicKey, exchangeId ->
            blobTransfer.handleBlobFetch(fromDeviceId, payload, ephemeralPublicKey, exchangeId)
        }
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
        confirmRetryJob?.cancel()
        confirmRetryJob = null
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
                delay(watchdogIntervalMs)
                val now = Clock.System.now().toEpochMilliseconds()
                val pinStillValid = pairing.hasActivePin(now)
                if (!pinStillValid && server.isRunning && now - lastActivityAt >= idleTimeoutMs) {
                    _state.update {
                        it.copy(statusMessage = "Sync server stopped after being idle — it restarts automatically when you sync.")
                    }
                    stopServer()
                }
            }
        }
    }

    fun observePeers(): Flow<List<SyncPeer>> = repository.observePeers()

    /**
     * Unseen conflict events: rows where a newer version from a paired device
     * overwrote a local edit that peer had never seen (last-write-wins). The
     * records are per-device and never synced; dismissing them marks them
     * seen.
     */
    fun observeConflicts(): Flow<List<ConflictEvent>> = repository.observeUnseenConflicts()

    /** Dismisses specific conflict events (marks them seen). */
    suspend fun dismissConflicts(ids: List<Long>) = repository.markConflictSeen(ids)

    /** Dismisses every conflict event. */
    suspend fun dismissAllConflicts() = repository.markAllConflictsSeen()

    // ---------- Pairing (delegated) ----------

    fun startPairing() = pairing.startPairing()

    /**
     * Ends the pairing session. If a fingerprint is still pending, its peer
     * row is removed first: pairing creates the peer BEFORE the out-of-band
     * check, so leaving the row would let a later sync treat an unverified
     * device as trusted. Suspends so the removal completes before the caller
     * proceeds (tests assert immediately after).
     */
    suspend fun cancelPairing() {
        confirmRetryJob?.cancel()
        confirmRetryJob = null
        val pendingId = _state.value.pendingPeerDeviceId
        if (pendingId != null && _state.value.pendingPeerFingerprint != null) {
            runCatching { repository.deletePeer(pendingId) }
        }
        pairing.cancelPairing()
    }

    /**
     * Rejects the currently pending (fingerprint-unconfirmed) pairing and
     * removes its peer row. Same as cancelPairing() — kept as a named alias
     * so UI code reads as an explicit security decision.
     */
    suspend fun rejectPendingPairing() = cancelPairing()

    /**
     * Dismisses the pairing dialog safely: removes the unverified peer when
     * one is pending, otherwise just ends the session. UI close/back actions
     * should call this instead of pairing.cancelPairing().
     */
    suspend fun dismissPairing() = cancelPairing()

    /**
     * The text to encode in the pairing QR code: this device's address, id and
     * name. Null when no LAN address is known (no QR can be shown then).
     */
    fun pairingQrPayload(): String? = pairing.pairingQrPayload()

    // Retry loop for the fingerprint confirmation: after this device's user
    // confirms, the confirmation is sent to the peer until the peer
    // acknowledges it. One attempt is enough in the normal case; the loop
    // only covers a dropped packet or a peer that was briefly unreachable.
    // The loop deliberately does NOT stop when the pending pairing clears:
    // recording the local confirmation can complete the pairing right here
    // (the peer already confirmed), which clears the pending state before
    // this loop's first check runs — but the peer still needs to hear that
    // this side confirmed, or it waits forever. The loop stops on the
    // peer's acknowledgement, on cancellation (cancelPairing/stopServer),
    // or after CONFIRM_MAX_ATTEMPTS.
    private var confirmRetryJob: Job? = null

    /**
     * Acknowledges the out-of-band fingerprint comparison after pairing:
     * records this device's confirmation and sends it to the peer. The
     * pairing completes — and sync starts — only once the peer has
     * confirmed too, so confirming here never syncs on its own.
     */
    fun confirmFingerprint() {
        val peerId = _state.value.pendingPeerDeviceId ?: run {
            pairing.confirmFingerprint()
            return
        }
        pairing.confirmFingerprint()
        confirmRetryJob?.cancel()
        confirmRetryJob = scope.launch {
            var attempts = 0
            while (isActive && attempts < CONFIRM_MAX_ATTEMPTS) {
                attempts++
                if (sendFingerprintConfirmation(peerId)) return@launch
                delay(CONFIRM_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Sends this device's fingerprint confirmation to the peer over the
     * paired channel (same AEAD authentication as an exchange: only the
     * paired peer can read it). Returns true when the peer acknowledged
     * and recorded it. One attempt; the retry loop in confirmFingerprint
     * covers failures.
     */
    private suspend fun sendFingerprintConfirmation(peerId: String): Boolean {
        return try {
            val peer = repository.getPeer(peerId) ?: return false
            val peerKey = peer.publicKey ?: return false
            val address = peer.lastKnownAddress ?: return false
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull() ?: SYNC_PORT
            val channelKey = channelKeys.outboundChannelKey(peerKey)
            val exchangeId = freshExchangeId()
            val payload = encodeBase64(
                crypto.encrypt(
                    channelKey.key,
                    aad(from = identity.deviceId, to = peerId, exchangeId = exchangeId),
                    PAIR_CONFIRM_PAYLOAD
                )
            )
            val response: PairConfirmResponse = httpClient.post("http://$host:$port/pair-confirm") {
                contentType(ContentType.Application.Json)
                setBody(
                    PairConfirmRequest(
                        fromDeviceId = identity.deviceId,
                        exchangeId = exchangeId,
                        payload = payload,
                        ephemeralPublicKey = encodeBase64(channelKey.ephemeralPublicKey)
                    )
                )
            }.body()
            response.ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Server side of POST /pair-confirm: the peer's user confirmed the
     * fingerprint on their screen. Authenticated exactly like an exchange
     * (paired static secret + fresh ephemeral, AEAD) — only the paired
     * peer can produce it. Records the confirmation; if this device's user
     * already confirmed, the handshake completes here. A confirmation is
     * also the peer proving it is reachable right now, so a locally
     * confirmed device answers with its own confirmation immediately.
     */
    suspend fun handlePairConfirm(fromDeviceId: String, encryptedPayload: String, requestEphemeralKey: String, exchangeId: String): PairConfirmResponse {
        try {
            if (exchangeId.isBlank()) {
                return PairConfirmResponse(ok = false, message = "Missing exchange id.")
            }
            if (!isValidDeviceId(fromDeviceId)) {
                return PairConfirmResponse(ok = false, message = "Invalid device id.")
            }
            val peer = repository.getPeer(fromDeviceId)
                ?: return PairConfirmResponse(ok = false, message = "Not paired.")
            noteActivity()
            val peerKey = peer.publicKey
                ?: return PairConfirmResponse(ok = false, message = "Peer has no key.")
            val peerEphemeral = runCatching { decodeBase64(requestEphemeralKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                return PairConfirmResponse(ok = false, message = "Invalid ephemeral key.")
            }
            val channelKey = channelKeys.inboundChannelKey(peerKey, peerEphemeral)
            val decrypted = try {
                crypto.decrypt(channelKey, aad(from = fromDeviceId, to = identity.deviceId, exchangeId = exchangeId), decodeBase64(encryptedPayload))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return PairConfirmResponse(ok = false, message = "Could not read confirmation.")
            }
            if (!decrypted.contentEquals(PAIR_CONFIRM_PAYLOAD)) {
                return PairConfirmResponse(ok = false, message = "Invalid confirmation.")
            }
            // Capture whether this device's user already confirmed BEFORE
            // recording the peer's: recording may complete the handshake
            // and clear the pending peer, after which the check below
            // could no longer tell that an answer is owed.
            val iConfirmed = _state.value.fingerprintConfirmedByMe &&
                _state.value.pendingPeerDeviceId == fromDeviceId
            pairing.onPeerFingerprintConfirmed(fromDeviceId, exchangeId)
            if (iConfirmed) {
                scope.launch { sendFingerprintConfirmation(fromDeviceId) }
            }
            return PairConfirmResponse(ok = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return PairConfirmResponse(ok = false, message = "Could not read confirmation.")
        }
    }

    suspend fun connectToDevice(host: String, port: Int, pin: String): Result<String> =
        pairing.connectToDevice(host, port, pin)

    /**
     * Tells a host (fire-and-forget) that this device is on its PIN step, so
     * the host's screen swaps its QR for the PIN before the PIN is even
     * typed. Failures are ignored: the host is told again by the /hello the
     * pairing call itself performs.
     */
    fun announceConnection(host: String, port: Int) {
        scope.launch {
            runCatching {
                httpClient.get("http://$host:$port/hello") {
                    parameter("announce", identity.deviceName)
                }
            }
        }
    }

    // ---------- Sync exchange (delegated) ----------

    // Serializes background syncs: only one syncNowAsync/syncAllPeersAsync
    // session runs at a time, so two overlapping sessions can never
    // interleave rounds against the same peer (wasted re-sends) or stack
    // their batch buffers on a mobile heap. Direct syncNow() calls (the
    // pairing dialog's initial sync) bypass the mutex but are still safe:
    // concurrent exchanges are idempotent LWW with per-peer cursors, so the
    // worst case is redundant work, never corruption.
    private val syncMutex = Mutex()
    private var syncJob: Job? = null
    // Bumped on every cancelSync(): a session queued behind the running one
    // aborts instead of starting a stale sync after the user cancelled.
    // A StateFlow (not a plain var): cancelSync runs on the UI thread while
    // sessions read it from background threads.
    private val syncGeneration = MutableStateFlow(0L)

    suspend fun syncNow(
        peerId: String,
        onProgress: (SyncProgress) -> Unit = {}
    ): Result<String> {
        // A peer whose pairing fingerprint has not been confirmed yet is not
        // trusted; refuse to sync with it locally (the server refuses too).
        if (_state.value.pendingPeerFingerprint != null && _state.value.pendingPeerDeviceId == peerId) {
            return Result.failure(IllegalStateException("Confirm the pairing fingerprint before syncing with this device."))
        }
        // Publish progress to the shared UI state as well (the pairing
        // dialog's initial sync calls this directly and renders
        // state.syncProgress); the per-call listener stays for tests.
        _state.update { it.copy(syncProgress = null, syncPeerId = peerId) }
        try {
            return exchange.syncNow(peerId) { progress ->
                _state.update { it.copy(syncProgress = progress, syncPeerId = peerId) }
                onProgress(progress)
            }
        } finally {
            _state.update { it.copy(syncProgress = null, syncPeerId = null) }
        }
    }

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
        try {
            val sanitized = DeviceName.sanitize(newName) ?: error("Name must not be empty.")
            val renamed = identity.withName(sanitized)
            identityStore.save(SyncIdentity.serialize(renamed))
            identity = renamed
            _deviceName.value = sanitized
            _state.update { it.copy(statusMessage = "Device renamed to \"$sanitized\".", syncError = null) }
            Result.success(sanitized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(syncError = e.message, statusMessage = null) }
            Result.failure(e)
        }
    }

    // ---------- Identity rotation ----------

    /**
     * Regenerates the device's X25519 identity keypair (same device id and
     * name) and invalidates every existing pairing. All devices must be
     * re-paired afterwards; old pairings can no longer authenticate.
     */
    suspend fun rotateIdentityKey(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val newIdentity = SyncIdentity.create(deviceName = identity.deviceName, crypto = crypto, deviceId = identity.deviceId)
            identityStore.save(SyncIdentity.serialize(newIdentity))
            identity = newIdentity
            repository.clearAllPeers()
            // A pending unconfirmed pairing belongs to the old key; clear it so
            // the gate does not block the re-pairing flow — and cancel any
            // active pairing session (its PIN would still be valid against the
            // new key, and its stage would leave the wizard on a stale screen).
            pairing.cancelPairing()
            _state.update {
                it.copy(
                    statusMessage = "Sync key rotated. Re-pair your devices.",
                    syncError = null,
                    pendingPeerFingerprint = null,
                    pendingPeerName = null,
                    pendingPeerDeviceId = null
                )
            }
            Result.success("Sync key rotated. Re-pair your devices.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(syncError = e.message, statusMessage = null) }
            Result.failure(e)
        }
    }

    fun syncNowAsync(peerId: String) {
        // Safety net (the UI disables sync buttons while syncing): never
        // stack a second session on top of a running one.
        if (syncMutex.isLocked) return
        syncJob = scope.launch {
            val generation = syncGeneration.value
            syncMutex.withLock {
                // Cancelled while queued: never start a stale sync.
                ensureActive()
                if (syncGeneration.value != generation) return@withLock
                _state.update { it.copy(isSyncing = true, syncError = null) }
                try {
                    val result = syncNow(peerId)
                    _state.update {
                        it.copy(
                            statusMessage = result.getOrNull() ?: "Sync failed.",
                            syncError = result.exceptionOrNull()?.message
                        )
                    }
                } catch (e: CancellationException) {
                    _state.update { it.copy(statusMessage = "Sync cancelled — nothing was lost. Sync again to resume.") }
                    throw e
                } finally {
                    _state.update { it.copy(isSyncing = false) }
                }
            }
        }
    }

    fun syncAllPeersAsync() {
        // Safety net (see syncNowAsync): one session at a time.
        if (syncMutex.isLocked) return
        syncJob = scope.launch {
            val generation = syncGeneration.value
            syncMutex.withLock {
                ensureActive()
                if (syncGeneration.value != generation) return@withLock
                _state.update { it.copy(isSyncing = true, syncError = null) }
                try {
                    val peers = repository.getPeers()
                    var synced = 0
                    var failedMessage: String? = null
                    for (peer in peers) {
                        // Per-peer cancellation checkpoint: stopping after a
                        // finished peer leaves every completed peer synced and
                        // every cursor exact — resume-safe by construction.
                        ensureActive()
                        val result = syncNow(peer.deviceId)
                        if (result.isSuccess) synced++ else if (failedMessage == null) {
                            failedMessage = result.exceptionOrNull()?.message
                        }
                    }
                    val total = peers.size
                    _state.update {
                        it.copy(
                            statusMessage = if (failedMessage == null) "All devices synced." else "Synced $synced/$total devices.",
                            syncError = failedMessage
                        )
                    }
                } catch (e: CancellationException) {
                    _state.update { it.copy(statusMessage = "Sync cancelled — nothing was lost. Sync again to resume.") }
                    throw e
                } finally {
                    _state.update { it.copy(isSyncing = false) }
                }
            }
        }
    }

    /**
     * Cancels the running background sync (if any) and clears its UI state.
     * Safe to call with no active sync (a no-op that leaves the state clean).
     *
     * Cancellation is resume-safe: sync rounds are idempotent and cursors
     * advance only after a committed apply, so a cancelled sync loses no row
     * — syncing again resumes exactly where it stopped.
     */
    fun cancelSync() {
        syncGeneration.value += 1
        syncJob?.cancel()
        syncJob = null
        // The running session's finally also clears these; clear
        // optimistically so the UI never sticks on a phantom sync.
        _state.update { it.copy(isSyncing = false, syncProgress = null, syncPeerId = null) }
    }
}

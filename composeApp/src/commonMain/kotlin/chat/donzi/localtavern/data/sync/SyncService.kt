package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.SyncPeer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.serialization.json.Json
import kotlin.random.Random
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
    val pendingPeerName: String? = null
)

const val SYNC_PORT = 47324
const val SYNC_DISCOVERY_PORT = 47325
private const val PAIRING_TTL_MS = 5 * 60 * 1000L

// The sync server winds down when it goes quiet: after this long without any
// sync traffic, pairing attempt or explicit engagement it stops listening
// (and the app stops broadcasting on the LAN). A stopped server is invisible
// to the user — every sync UI action restarts it via ensureSyncRunning().
private const val SYNC_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
private const val IDLE_WATCHDOG_INTERVAL_MS = 30_000L

// Online PIN guessing is throttled per pairing session; the PIN (6 digits)
// is still the last line of defense, so an attacker who can repeatedly probe
// the pairing endpoint must fail before the lockout and restart the session.
private const val MAX_PAIRING_ATTEMPTS = 5

class SyncService(
    var identity: SyncIdentity,
    private val crypto: SyncCrypto,
    private val repository: SyncRepository,
    private val identityStore: SyncIdentityStore,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val port: Int = SYNC_PORT,
    private val localAddressesProvider: () -> List<String> = { emptyList() },
    private val onServerStopped: () -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    // Live display name of this device: renamed via renameDevice(), read by
    // the UI (settings + pairing) so it recomposes on change.
    private val _deviceName = MutableStateFlow(identity.deviceName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private var activePin: String? = null
    private var pinExpiresAt: Long = 0L
    private var pairingFailures = 0

    // High-water mark of the most recent sync/pairing activity; the idle
    // watchdog stops the server when this goes quiet for SYNC_IDLE_TIMEOUT_MS.
    private var lastActivityAt = 0L
    private var idleWatchdog: Job? = null

    val server = SyncServer(
        port = port,
        hello = { HelloResponse(deviceId = identity.deviceId, deviceName = identity.deviceName) },
        onPair = { request, remoteHost -> handlePairRequest(request, remoteHost) },
        onExchange = { fromDeviceId, payload, ephemeralPublicKey -> handleExchange(fromDeviceId, payload, ephemeralPublicKey) }
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
        activePin = null
        pairingFailures = 0
        _state.update { it.copy(isServerRunning = false, pairingPin = null) }
        onServerStopped()
    }

    // Marks sync activity so the idle watchdog keeps the server alive.
    private fun noteActivity() {
        lastActivityAt = Clock.System.now().toEpochMilliseconds()
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
                val pinStillValid = activePin != null && now <= pinExpiresAt
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

    // ---------- Pairing (host side) ----------

    fun startPairing() {
        if (!server.isRunning) startServer()
        noteActivity()
        activePin = (Random.nextInt(0, 1_000_000)).toString().padStart(6, '0')
        pinExpiresAt = Clock.System.now().toEpochMilliseconds() + PAIRING_TTL_MS
        pairingFailures = 0
        _state.update {
            it.copy(pairingPin = activePin, pairingExpiresAt = pinExpiresAt, syncError = null, pendingPeerFingerprint = null, pendingPeerName = null)
        }
    }

    fun cancelPairing() {
        activePin = null
        pairingFailures = 0
        _state.update { it.copy(pairingPin = null, pairingExpiresAt = null, pendingPeerFingerprint = null, pendingPeerName = null) }
    }

    /**
     * The text to encode in the pairing QR code: this device's address, id and
     * name. Null when no LAN address is known (no QR can be shown then).
     */
    fun pairingQrPayload(): String? {
        val address = pickPairingAddress() ?: return null
        return PairPayload(
            host = address,
            port = port,
            deviceId = identity.deviceId,
            deviceName = identity.deviceName
        ).toQrText()
    }

    // The peer must reach us over the LAN, so prefer private-range IPv4
    // addresses (WiFi/LAN) over VPN, tunnel or cellular IPs.
    private fun pickPairingAddress(): String? {
        val addresses = localAddressesProvider()
        return addresses.firstOrNull { isPrivateLanAddress(it) } ?: addresses.firstOrNull()
    }

    private fun isPrivateLanAddress(address: String): Boolean {
        val prefix = address.substringBefore('.').toIntOrNull() ?: return false
        val second = address.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return when {
            prefix == 10 -> true
            prefix == 172 -> second in 16..31
            prefix == 192 && second == 168 -> true
            else -> false
        }
    }

    /** Acknowledges the out-of-band fingerprint comparison after pairing. */
    fun confirmFingerprint() {
        _state.update { it.copy(pendingPeerFingerprint = null, pendingPeerName = null) }
    }

    private suspend fun handlePairRequest(request: PairRequest, remoteHost: String?): PairResponse {
        noteActivity()
        val now = Clock.System.now().toEpochMilliseconds()
        val pin = activePin
        if (pin == null || now > pinExpiresAt) {
            return PairResponse(ok = false, message = "No active pairing session. Start pairing on the other device.")
        }
        if (pairingFailures >= MAX_PAIRING_ATTEMPTS) {
            return PairResponse(ok = false, message = "Too many failed attempts. Restart pairing on the host device.")
        }
        if (request.deviceId == identity.deviceId) {
            return PairResponse(ok = false, message = "Cannot pair a device with itself.")
        }
        val peerPublicKey = runCatching { decodeBase64(request.publicKey) }.getOrNull()
        val nonce = runCatching { decodeBase64(request.nonce) }.getOrNull()
        val proof = runCatching { decodeBase64(request.pinProof) }.getOrNull()
        if (peerPublicKey == null || peerPublicKey.size != 32 || nonce == null || nonce.isEmpty() || proof == null) {
            return PairResponse(ok = false, message = "Invalid pairing payload.")
        }

        // Constant-time PIN verification; the PIN never crosses the network.
        val pinOk = crypto.verifyPairingProof(pin, request.deviceId, peerPublicKey, nonce, proof)
        if (!pinOk) {
            pairingFailures++
            return PairResponse(ok = false, message = "Invalid PIN.")
        }
        pairingFailures = 0

        // Fingerprint over both exchanged keys: the same string must appear
        // on the other device's screen during pairing.
        val fingerprint = crypto.pairingFingerprint(identity.publicKeyBytes, peerPublicKey)
        repository.upsertPeer(
            SyncPeer(
                deviceId = request.deviceId,
                name = request.deviceName,
                publicKey = peerPublicKey,
                lastKnownAddress = remoteHost?.let { "$it:$port" },
                receivedCursor = 0L,
                peerReceivedCursor = 0L,
                lastSyncAt = now,
                updatedAt = now,
                isDeleted = 0L
            )
        )
        _state.update {
            it.copy(pendingPeerFingerprint = fingerprint, pendingPeerName = request.deviceName)
        }
        return PairResponse(
            ok = true,
            message = "Paired.",
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            publicKey = encodeBase64(identity.publicKeyBytes)
        )
    }

    // ---------- Pairing (client side) ----------

    suspend fun connectToDevice(host: String, port: Int, pin: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = "http://$host:$port"
            val hello: HelloResponse = httpClient.get("$base/hello").body()
            if (hello.deviceId == identity.deviceId) error("Cannot pair a device with itself.")

            // Fresh nonce per pairing attempt: the proof cannot be replayed
            // against a different key or in a different session.
            val nonce = ByteArray(16).also { Random.nextBytes(it) }
            val proof = crypto.pairingProof(pin, identity.deviceId, identity.publicKeyBytes, nonce)

            val response: PairResponse = httpClient.post("$base/pair") {
                contentType(ContentType.Application.Json)
                setBody(
                    PairRequest(
                        deviceId = identity.deviceId,
                        deviceName = identity.deviceName,
                        publicKey = encodeBase64(identity.publicKeyBytes),
                        nonce = encodeBase64(nonce),
                        pinProof = encodeBase64(proof)
                    )
                )
            }.body()

            if (!response.ok || response.publicKey.isBlank()) {
                error(response.message.ifBlank { "Pairing rejected." })
            }
            val peerKey = decodeBase64(response.publicKey)
            check(peerKey.size == 32) { "Invalid public key from peer." }

            val now = Clock.System.now().toEpochMilliseconds()
            repository.upsertPeer(
                SyncPeer(
                    deviceId = response.deviceId,
                    name = response.deviceName,
                    publicKey = peerKey,
                    lastKnownAddress = "$host:$port",
                    receivedCursor = 0L,
                    peerReceivedCursor = 0L,
                    lastSyncAt = now,
                    updatedAt = now,
                    isDeleted = 0L
                )
            )
            _state.update {
                it.copy(pendingPeerFingerprint = crypto.pairingFingerprint(identity.publicKeyBytes, peerKey), pendingPeerName = response.deviceName)
            }
            response.deviceId
        }
    }

    // ---------- Sync exchange ----------

    // Per-exchange forward-secret channel key. The static shared secret
    // provides authentication (only paired devices can derive it); a fresh
    // ephemeral X25519 key provides per-exchange secrecy — each exchange
    // encrypts under a key that exists for exactly one round trip, and the
    // sender's ephemeral private key is discarded immediately, so payloads a
    // device SENT cannot be decrypted later even with its long-term keys.
    // Rotating the identity key (see rotateIdentityKey) additionally
    // invalidates the static secrets entirely.
    private data class ExchangeKey(val key: ByteArray, val ephemeralPublicKey: ByteArray)

    // Outbound direction: the fresh ephemeral keypair seals THIS exchange, and
    // its public half is shipped inside the request for the peer to re-derive
    // the same channel key.
    private suspend fun outboundChannelKey(peerPublicKey: ByteArray): ExchangeKey {
        val staticShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerPublicKey)
        val (ephemeralPrivate, ephemeralPublic) = crypto.generateKeyPair()
        val ephemeralShared = crypto.deriveSharedSecret(ephemeralPrivate, peerPublicKey)
        return ExchangeKey(
            key = crypto.deriveChannelSecret(staticShared, ephemeralShared),
            ephemeralPublicKey = ephemeralPublic
        )
    }

    // Inbound direction: the peer's ephemeral public key (shipped with the
    // request/response) replaces our own ephemeral half; the DH result is the
    // same secret the peer derived with its ephemeral private key.
    private suspend fun inboundChannelKey(peerPublicKey: ByteArray, peerEphemeralPublicKey: ByteArray): ByteArray {
        val staticShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerPublicKey)
        val ephemeralShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerEphemeralPublicKey)
        return crypto.deriveChannelSecret(staticShared, ephemeralShared)
    }

    suspend fun syncNow(peerId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            noteActivity()
            val peer = repository.getPeer(peerId) ?: error("Peer not found.")
            val address = peer.lastKnownAddress ?: error("Peer has no address; reconnect or re-pair.")
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull() ?: SYNC_PORT
            val peerPublicKey = peer.publicKey ?: error("Peer has no key; re-pair.")

            // Changes I have not yet sent this peer, and my received cursor.
            val myChanges = repository.collectDelta(peer.peerReceivedCursor)
            val channelKey = outboundChannelKey(peerPublicKey)
            val envelope = SyncEnvelope(
                fromDeviceId = identity.deviceId,
                cursor = peer.receivedCursor,
                changes = myChanges,
                fromDeviceName = identity.deviceName
            )
            val aad = aad(from = identity.deviceId, to = peer.deviceId)
            val payload = encodeBase64(crypto.encrypt(channelKey.key, aad, encodeEnvelope(envelope)))

            val response: ExchangeResponse = httpClient.post("http://$host:$port/exchange") {
                contentType(ContentType.Application.Json)
                header("X-Sync-From", identity.deviceId)
                setBody(
                    ExchangeRequest(
                        fromDeviceId = identity.deviceId,
                        payload = payload,
                        ephemeralPublicKey = encodeBase64(channelKey.ephemeralPublicKey)
                    )
                )
            }.body()

            if (!response.ok) error(response.message.ifBlank { "Sync rejected by peer." })

            // The peer answers with its own delta (changes newer than my
            // received cursor), its own fresh ephemeral key, and its updated
            // received-cursor for me.
            val responsePayload = response.payload ?: error("Empty sync response.")
            val peerEphemeral = runCatching { decodeBase64(response.ephemeralPublicKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                error("Invalid ephemeral key from peer.")
            }
            val responseKey = inboundChannelKey(peerPublicKey, peerEphemeral)
            val responseEnvelope = decodeEnvelope(
                crypto.decrypt(responseKey, aad(from = peer.deviceId, to = identity.deviceId), decodeBase64(responsePayload))
            )

            if (!responseEnvelope.changes.isEmpty) {
                repository.applyChanges(responseEnvelope.changes, peerDeviceId = peer.deviceId)
            }
            // The peer's new display name (if any), authenticated by the
            // exchange: only the device holding the paired key can have
            // produced this envelope. Display-only — cursors are untouched.
            DeviceName.sanitize(responseEnvelope.fromDeviceName.orEmpty())?.let { name ->
                repository.updatePeerName(peer.deviceId, name)
            }
            val newReceivedCursor = maxOf(responseEnvelope.changes.maxUpdatedAt, peer.receivedCursor)
            repository.updatePeerCursors(
                deviceId = peer.deviceId,
                receivedCursor = newReceivedCursor,
                peerReceivedCursor = responseEnvelope.cursor
            )
            "Synced with ${peer.name}."
        }
    }

    private suspend fun handleExchange(fromDeviceId: String, encryptedPayload: String, requestEphemeralKey: String): ExchangeResponse {
        noteActivity()
        return runCatching {
            val peer = repository.getPeer(fromDeviceId)
                ?: return ExchangeResponse(ok = false, message = "Not paired.")
            val peerKey = peer.publicKey
                ?: return ExchangeResponse(ok = false, message = "Peer has no key.")
            val peerEphemeral = runCatching { decodeBase64(requestEphemeralKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                return ExchangeResponse(ok = false, message = "Invalid ephemeral key.")
            }
            val channelKey = inboundChannelKey(peerKey, peerEphemeral)
            val envelope = decodeEnvelope(
                crypto.decrypt(channelKey, aad(from = fromDeviceId, to = identity.deviceId), decodeBase64(encryptedPayload))
            )
            if (envelope.fromDeviceId != fromDeviceId) {
                return ExchangeResponse(ok = false, message = "Sender mismatch.")
            }
            // Authenticated display-name update (see syncNow): never touches
            // cursors or deltas, so renaming cannot disturb sync state.
            DeviceName.sanitize(envelope.fromDeviceName.orEmpty())?.let { name ->
                repository.updatePeerName(fromDeviceId, name)
            }

            val applyResult = runCatching {
                repository.applyChanges(envelope.changes, peerDeviceId = fromDeviceId)
            }
            if (applyResult.isFailure) {
                return ExchangeResponse(ok = false, message = "Failed to apply changes.")
            }
            val newReceivedCursor = maxOf(envelope.changes.maxUpdatedAt, peer.receivedCursor)
            repository.updatePeerCursors(
                deviceId = fromDeviceId,
                receivedCursor = newReceivedCursor,
                peerReceivedCursor = envelope.cursor
            )

            val myChanges = repository.collectDelta(envelope.cursor)
            val myExchangeKey = outboundChannelKey(peerKey)
            val responseEnvelope = SyncEnvelope(
                fromDeviceId = identity.deviceId,
                cursor = newReceivedCursor,
                changes = myChanges,
                fromDeviceName = identity.deviceName
            )
            val responsePayload = encodeBase64(
                crypto.encrypt(myExchangeKey.key, aad(from = identity.deviceId, to = fromDeviceId), encodeEnvelope(responseEnvelope))
            )
            ExchangeResponse(
                ok = true,
                payload = responsePayload,
                ephemeralPublicKey = encodeBase64(myExchangeKey.ephemeralPublicKey)
            )
        }.getOrElse { error ->
            ExchangeResponse(ok = false, message = error.message ?: "Sync failed.")
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

    // ---------- Envelope serialization ----------

    private fun encodeEnvelope(envelope: SyncEnvelope): ByteArray =
        json.encodeToString(SyncEnvelope.serializer(), envelope).encodeToByteArray()

    private fun decodeEnvelope(bytes: ByteArray): SyncEnvelope =
        json.decodeFromString(SyncEnvelope.serializer(), bytes.decodeToString())

    private fun aad(from: String, to: String): ByteArray =
        "localtavern-sync|from=$from|to=$to".encodeToByteArray()
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun encodeBase64(bytes: ByteArray): String =
    kotlin.io.encoding.Base64.encode(bytes)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun decodeBase64(text: String): ByteArray =
    kotlin.io.encoding.Base64.decode(text)

package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.SyncPeer
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

// PIN-authenticated device pairing, host side (startPairing / handlePairRequest)
// and client side (connectToDevice). The PIN never crosses the wire: the
// client sends a proof computed over it, the host verifies it in constant
// time and rate-limits attempts per pairing session.
//
// The proof is HMAC-SHA256 over PBKDF2-HMAC-SHA256(pin, nonce) with 600k
// iterations: the nonce doubles as the KDF salt, so a sniffer who captures a
// proof must repeat the (intentionally expensive) KDF for every candidate of
// the 6-digit space — minutes on dedicated hardware, which meaningfully
// raises the bar; the out-of-band fingerprint comparison is the primary
// defense against an active man-in-the-middle.
//
// Rate limiting is two-layered: the per-session budget (MAX_PAIRING_ATTEMPTS)
// stops brute-forcing one session, and the per-source-IP window below stops a
// LAN attacker from killing every session the host restarts.
private const val MAX_IP_ATTEMPTS_PER_WINDOW = 20
private const val IP_ATTEMPT_WINDOW_MS = 15 * 60 * 1000L

class SyncPairing(
    private val crypto: SyncCrypto,
    private val repository: SyncRepository,
    private val state: MutableStateFlow<SyncUiState>,
    private val identityProvider: () -> SyncIdentity,
    private val port: Int,
    private val localAddressesProvider: () -> List<String>,
    private val httpClient: HttpClient,
    private val ensureServerRunning: () -> Unit,
    private val noteActivity: () -> Unit
) {
    // Pairing session state. The PIN/budget fields live in StateFlows: they
    // are mutated from the UI thread (startPairing/cancelPairing) and read
    // from Ktor server threads (handlePairRequest), so the values need
    // cross-thread visibility on every target — a plain field is not
    // sufficient in common code.
    private val activePin = MutableStateFlow<String?>(null)
    private val pinExpiresAt = MutableStateFlow(0L)
    private val pairingFailures = MutableStateFlow(0)

    // Nonces seen during the CURRENT pairing session, to reject replayed
    // pairing proofs (the PIN proof itself never expires until the PIN does).
    // The attempt budget, the replay check and the nonce recording all run
    // under pairingMutex: pairing requests arrive on Ktor server threads and
    // can be concurrent with each other AND with UI-thread resets
    // (startPairing/cancelPairing), so the budget and the replay set are
    // shared state. UI-side resets swap the set reference instead of clearing
    // it in place, so a concurrent handler can never corrupt it.
    private val pairingMutex = Mutex()
    private var seenPairingNonces = mutableSetOf<String>()

    private val identity get() = identityProvider()

    /** True while a pairing PIN is still valid (used by the idle watchdog). */
    fun hasActivePin(now: Long): Boolean = activePin.value != null && now <= pinExpiresAt.value

    /** Clears pairing state when the server stops (PINs must not survive). */
    fun resetOnServerStop() {
        activePin.value = null
        pairingFailures.value = 0
        seenPairingNonces = mutableSetOf()
        state.update {
            it.copy(
                pairingPin = null,
                pairingStage = PairingStage.Idle,
                connectedPeerName = null,
                pairedPeerName = null
            )
        }
    }

    // ---------- Host side ----------

    fun startPairing() {
        ensureServerRunning()
        noteActivity()
        // Cryptographically secure PIN and nonces: the nonce is the KDF salt
        // of the PIN proof, so it must be unpredictable.
        activePin.value = CryptographyRandom.Default.nextInt(0, 1_000_000).toString().padStart(6, '0')
        pinExpiresAt.value = Clock.System.now().toEpochMilliseconds() + PAIRING_TTL_MS
        pairingFailures.value = 0
        seenPairingNonces = mutableSetOf()
        state.update {
            it.copy(
                pairingPin = activePin.value,
                pairingExpiresAt = pinExpiresAt.value,
                pairingStage = PairingStage.WaitingForReceiver,
                connectedPeerName = null,
                pairedPeerName = null,
                syncError = null,
                pendingPeerFingerprint = null,
                pendingPeerName = null,
                pendingPeerDeviceId = null
            )
        }
    }

    fun cancelPairing() {
        activePin.value = null
        pairingFailures.value = 0
        seenPairingNonces = mutableSetOf()
        state.update {
            it.copy(
                pairingPin = null,
                pairingExpiresAt = null,
                pairingStage = PairingStage.Idle,
                connectedPeerName = null,
                pairedPeerName = null,
                pendingPeerFingerprint = null,
                pendingPeerName = null,
                pendingPeerDeviceId = null
            )
        }
    }

    /**
     * A receiver entered its PIN step and announced itself to this host
     * (GET /hello?announce=<name>). Once that happens the host screen swaps
     * the QR for the PIN, so both users never have to read two things at
     * once. The announced name is display-only and sanitized: it comes from
     * an unauthenticated request.
     */
    fun onHello(announcedName: String?) {
        if (activePin.value == null) return
        state.update {
            if (it.pairingStage == PairingStage.WaitingForReceiver) {
                it.copy(
                    pairingStage = PairingStage.ReceiverConnected,
                    connectedPeerName = announcedName?.let { name -> DeviceName.sanitize(name) }
                )
            } else {
                it
            }
        }
    }

    /**
     * An authenticated exchange arrived from the peer whose fingerprint is
     * still pending: the other side has verified its fingerprint (the only
     * way it can produce an exchange is by holding the shared secret this
     * pairing established), so this side's gate opens automatically — the
     * flow no longer requires both users to press "Fingerprints match" at
     * the same time.
     */
    fun markPairingComplete(fromDeviceId: String) {
        state.update {
            if (it.pendingPeerDeviceId == fromDeviceId && it.pendingPeerFingerprint != null) {
                it.copy(
                    pairingStage = PairingStage.Paired,
                    pairedPeerName = it.pendingPeerName,
                    pendingPeerFingerprint = null,
                    pendingPeerName = null,
                    pendingPeerDeviceId = null
                )
            } else {
                it
            }
        }
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

    /** The address this device advertises for blob fetches during sync. */
    fun advertisedFetchAddress(): String? =
        pickPairingAddress()?.let { "$it:$port" }

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
        state.update {
            it.copy(
                pairingStage = PairingStage.Paired,
                pairedPeerName = it.pendingPeerName,
                pendingPeerFingerprint = null,
                pendingPeerName = null,
                pendingPeerDeviceId = null
            )
        }
    }

    // Per-source-IP throttle that SURVIVES pairing sessions. The session
    // budget (MAX_PAIRING_ATTEMPTS) stops PIN brute-forcing but resets on
    // every new session, which let a LAN attacker kill every restarted
    // session with five well-formed wrong guesses (VULN-015). This window
    // caps how many well-formed attempts ANY single source address can make
    // overall, so a hammering attacker is throttled instead of chasing the
    // host through an endless restart loop. Only well-formed attempts count
    // (malformed ones are rejected before this point); the maps expire by
    // wall-clock window and are guarded by pairingMutex like the budget.
    private val ipAttemptCounts = mutableMapOf<String, Int>()
    private val ipAttemptWindows = mutableMapOf<String, Long>()

    private fun isIpThrottled(ip: String?, now: Long): Boolean {
        val key = ip ?: "unknown"
        val windowStart = ipAttemptWindows[key] ?: return false
        if (now - windowStart > IP_ATTEMPT_WINDOW_MS) return false
        return (ipAttemptCounts[key] ?: 0) >= MAX_IP_ATTEMPTS_PER_WINDOW
    }

    private fun recordIpAttempt(ip: String?, now: Long) {
        val key = ip ?: "unknown"
        val windowStart = ipAttemptWindows[key]
        if (windowStart == null || now - windowStart > IP_ATTEMPT_WINDOW_MS) {
            ipAttemptWindows[key] = now
            ipAttemptCounts[key] = 1
        } else {
            ipAttemptCounts[key] = (ipAttemptCounts[key] ?: 0) + 1
        }
    }

    suspend fun handlePairRequest(request: PairRequest, remoteHost: String?): PairResponse {
        val now = Clock.System.now().toEpochMilliseconds()
        val pin = activePin.value
        if (pin == null || now > pinExpiresAt.value) {
            return PairResponse(ok = false, message = "No active pairing session. Start pairing on the other device.")
        }
        if (request.deviceId == identity.deviceId) {
            return PairResponse(ok = false, message = "Cannot pair a device with itself.")
        }
        val peerPublicKey = runCatching { decodeBase64(request.publicKey) }.getOrNull()
        val nonce = runCatching { decodeBase64(request.nonce) }.getOrNull()
        val proof = runCatching { decodeBase64(request.pinProof) }.getOrNull()
        if (peerPublicKey == null || peerPublicKey.size != 32 || nonce == null || nonce.isEmpty() || proof == null) {
            // Malformed payloads are rejected WITHOUT consuming the PIN
            // budget: they cost the host no crypto work and can never be a
            // PIN guess, so counting them would let any LAN attacker kill a
            // pairing session with five tiny garbage requests and repeat it
            // forever (a permanent pairing DoS). Only well-formed attempts
            // that reach the constant-time verification consume the budget.
            return PairResponse(ok = false, message = "Invalid pairing payload.")
        }
        // A well-formed attempt reached the pairing logic; the throttled
        // and budgeted checks below run in one critical section.
        val pinOk = pairingMutex.withLock {
            if (isIpThrottled(remoteHost, now)) {
                return PairResponse(ok = false, message = "Too many attempts from this address. Wait a few minutes, then restart pairing on the host device.")
            }
            recordIpAttempt(remoteHost, now)
            // A real (non-throttled) pairing attempt is activity: the idle
            // watchdog must not wind the server down under a live pairing.
            noteActivity()
            if (pairingFailures.value >= MAX_PAIRING_ATTEMPTS) {
                return PairResponse(ok = false, message = "Too many failed attempts. Restart pairing on the host device.")
            }
            if (request.nonce in seenPairingNonces) {
                // The same nonce + proof cannot be accepted twice: a captured
                // pairing request must not replay within the same session.
                pairingFailures.value += 1
                return PairResponse(ok = false, message = "Replayed pairing attempt.")
            }
            seenPairingNonces.add(request.nonce)
            // Constant-time PIN verification; the PIN never crosses the network.
            val ok = crypto.verifyPairingProof(pin, request.deviceId, peerPublicKey, nonce, proof)
            pairingFailures.value = if (ok) 0 else pairingFailures.value + 1
            ok
        }
        if (!pinOk) {
            return PairResponse(ok = false, message = "Invalid PIN.")
        }

        // The peer's display name is at its least-trusted moment during
        // pairing (the fingerprint is still pending), so it is sanitized like
        // the authenticated-exchange path before it is stored or displayed.
        val peerName = DeviceName.sanitize(request.deviceName) ?: "Unknown device"

        // Fingerprint over both exchanged keys: the same string must appear
        // on the other device's screen during pairing.
        val fingerprint = crypto.pairingFingerprint(identity.publicKeyBytes, peerPublicKey)
        repository.upsertPeer(
            SyncPeer(
                deviceId = request.deviceId,
                name = peerName,
                publicKey = peerPublicKey,
                lastKnownAddress = remoteHost?.let { "$it:$port" },
                receivedCursor = 0L,
                peerReceivedCursor = 0L,
                lastSyncAt = now,
                updatedAt = now,
                isDeleted = 0L
            )
        )
        state.update {
            it.copy(
                pairingStage = PairingStage.VerifyingPin,
                pendingPeerFingerprint = fingerprint,
                pendingPeerName = peerName,
                pendingPeerDeviceId = request.deviceId
            )
        }
        return PairResponse(
            ok = true,
            message = "Paired.",
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            publicKey = encodeBase64(identity.publicKeyBytes)
        )
    }

    // ---------- Client side ----------

    suspend fun connectToDevice(host: String, port: Int, pin: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = "http://$host:$port"
            // The announce query param tells the host a receiver has arrived
            // even when the UI-side announce call was skipped (e.g. manual
            // entry without the PIN step): the host swaps its QR for the PIN
            // as soon as it sees a /hello while a pairing session is active.
            val hello: HelloResponse = httpClient.get("$base/hello") {
                parameter("announce", identity.deviceName)
            }.body()
            if (hello.deviceId == identity.deviceId) error("Cannot pair a device with itself.")

            // Fresh nonce per pairing attempt from the CSPRNG: the proof
            // cannot be replayed against a different key or in a different
            // session, and the nonce doubles as the KDF salt of the proof.
            val nonce = ByteArray(16).also { CryptographyRandom.Default.nextBytes(it) }
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

            // The peer's name is at its least-trusted moment (fingerprint
            // pending); sanitize before storing or displaying.
            val peerName = DeviceName.sanitize(response.deviceName) ?: "Unknown device"

            val now = Clock.System.now().toEpochMilliseconds()
            repository.upsertPeer(
                SyncPeer(
                    deviceId = response.deviceId,
                    name = peerName,
                    publicKey = peerKey,
                    lastKnownAddress = "$host:$port",
                    receivedCursor = 0L,
                    peerReceivedCursor = 0L,
                    lastSyncAt = now,
                    updatedAt = now,
                    isDeleted = 0L
                )
            )
            state.update {
                it.copy(pendingPeerFingerprint = crypto.pairingFingerprint(identity.publicKeyBytes, peerKey), pendingPeerName = peerName, pendingPeerDeviceId = response.deviceId)
            }
            Result.success(response.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

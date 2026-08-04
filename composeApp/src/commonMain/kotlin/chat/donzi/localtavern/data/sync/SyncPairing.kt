package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.SyncPeer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

// PIN-authenticated device pairing, host side (startPairing / handlePairRequest)
// and client side (connectToDevice). The PIN never crosses the wire: the
// client sends an HMAC proof computed over it; the host verifies it in
// constant time and rate-limits attempts per pairing session.
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
    private var activePin: String? = null
    private var pinExpiresAt: Long = 0L
    private var pairingFailures = 0

    private val identity get() = identityProvider()

    /** True while a pairing PIN is still valid (used by the idle watchdog). */
    fun hasActivePin(now: Long): Boolean = activePin != null && now <= pinExpiresAt

    /** Clears pairing state when the server stops (PINs must not survive). */
    fun resetOnServerStop() {
        activePin = null
        pairingFailures = 0
        state.update { it.copy(pairingPin = null) }
    }

    // ---------- Host side ----------

    fun startPairing() {
        ensureServerRunning()
        noteActivity()
        activePin = (Random.nextInt(0, 1_000_000)).toString().padStart(6, '0')
        pinExpiresAt = Clock.System.now().toEpochMilliseconds() + PAIRING_TTL_MS
        pairingFailures = 0
        state.update {
            it.copy(pairingPin = activePin, pairingExpiresAt = pinExpiresAt, syncError = null, pendingPeerFingerprint = null, pendingPeerName = null)
        }
    }

    fun cancelPairing() {
        activePin = null
        pairingFailures = 0
        state.update { it.copy(pairingPin = null, pairingExpiresAt = null, pendingPeerFingerprint = null, pendingPeerName = null) }
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
        state.update { it.copy(pendingPeerFingerprint = null, pendingPeerName = null) }
    }

    suspend fun handlePairRequest(request: PairRequest, remoteHost: String?): PairResponse {
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
        state.update {
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

    // ---------- Client side ----------

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
            state.update {
                it.copy(pendingPeerFingerprint = crypto.pairingFingerprint(identity.publicKeyBytes, peerKey), pendingPeerName = response.deviceName)
            }
            response.deviceId
        }
    }
}

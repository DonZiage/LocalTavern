package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// One bidirectional delta exchange with a paired device: send my changes
// (deltas since the peer's cursor), apply the peer's changes (LWW, resolved
// in SyncRepository), advance both cursors and, when the peer referenced
// image blobs, pull them out of band via SyncBlobTransfer. The response
// envelope carries the peer's own delta, so every exchange converges both
// sides in a single round trip.
class SyncExchange(
    private val crypto: SyncCrypto,
    private val repository: SyncRepository,
    private val identityProvider: () -> SyncIdentity,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val blobStore: BlobStore?,
    private val channelKeys: SyncChannelKeys,
    private val blobTransfer: SyncBlobTransfer,
    private val advertisedFetchAddress: () -> String?,
    private val noteActivity: () -> Unit,
    // Device id whose pairing fingerprint is still awaiting out-of-band
    // confirmation; while set, exchanges with that peer are refused (the
    // peer is not yet trusted). Null = nothing pending.
    private val unconfirmedPeerDeviceId: () -> String? = { null }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val identity get() = identityProvider()

    // Anti-replay: exchange ids seen within the last few minutes per peer.
    // Every /exchange request carries a fresh id inside the AEAD associated
    // data; a verbatim replay of a captured request (same id) is refused, and
    // a replayed request with a different id fails decryption.
    private val seenExchangeIds = LinkedHashMap<String, Long>()
    private val seenMutex = Mutex()

    private suspend fun markExchangeSeen(peerId: String, exchangeId: String) {
        seenMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            seenExchangeIds.entries.removeAll { it.value < now - REPLAY_WINDOW_MS }
            if (seenExchangeIds.size >= MAX_SEEN_EXCHANGES) {
                seenExchangeIds.remove(seenExchangeIds.entries.first().key)
            }
            seenExchangeIds["$peerId:$exchangeId"] = now
        }
    }

    private suspend fun isExchangeReplay(peerId: String, exchangeId: String): Boolean =
        seenMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            seenExchangeIds.entries.removeAll { it.value < now - REPLAY_WINDOW_MS }
            seenExchangeIds.containsKey("$peerId:$exchangeId")
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
            val channelKey = channelKeys.outboundChannelKey(peerPublicKey)
            val exchangeId = freshExchangeId()
            val envelope = SyncEnvelope(
                fromDeviceId = identity.deviceId,
                cursor = peer.receivedCursor,
                changes = myChanges,
                fromDeviceName = identity.deviceName,
                fetchAddress = advertisedFetchAddress()
            )
            val aad = aad(from = identity.deviceId, to = peer.deviceId, exchangeId = exchangeId)
            val payload = encodeBase64(crypto.encrypt(channelKey.key, aad, encodeEnvelope(envelope)))

            val response: ExchangeResponse = httpClient.post("http://$host:$port/exchange") {
                contentType(ContentType.Application.Json)
                header("X-Sync-From", identity.deviceId)
                setBody(
                    ExchangeRequest(
                        fromDeviceId = identity.deviceId,
                        exchangeId = exchangeId,
                        payload = payload,
                        ephemeralPublicKey = encodeBase64(channelKey.ephemeralPublicKey)
                    )
                )
            }.body()

            if (!response.ok) error(response.message.ifBlank { "Sync rejected by peer." })
            if (response.exchangeId != exchangeId) {
                // A response from a different exchange context: it cannot be
                // the answer to this request.
                error("Sync response did not match the request.")
            }

            // The peer answers with its own delta (changes newer than my
            // received cursor), its own fresh ephemeral key, and its updated
            // received-cursor for me.
            val responsePayload = response.payload ?: error("Empty sync response.")
            val peerEphemeral = runCatching { decodeBase64(response.ephemeralPublicKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                error("Invalid ephemeral key from peer.")
            }
            val responseKey = channelKeys.inboundChannelKey(peerPublicKey, peerEphemeral)
            val responseEnvelope = decodeEnvelope(
                crypto.decrypt(responseKey, aad(from = peer.deviceId, to = identity.deviceId, exchangeId = exchangeId), decodeBase64(responsePayload))
            )

            if (!responseEnvelope.changes.isEmpty) {
                repository.applyChanges(responseEnvelope.changes, peerDeviceId = peer.deviceId)
            }
            // Pull image blobs referenced by the received rows out of band
            // (chunked, authenticated). The peer's stored address is used;
            // envelope.fetchAddress is only used server-side (the responder
            // cannot know the initiator's stored address).
            val refs = responseEnvelope.changes.messages.flatMap { it.imageRefs }
            if (refs.isNotEmpty() && blobStore != null) {
                blobTransfer.fetchMissingBlobs(peerId, address, refs, peerPublicKey)
            }
            // The peer's new display name (if any), authenticated by the
            // exchange: only the device holding the paired key can have
            // produced this envelope. Display-only — cursors are untouched.
            DeviceName.sanitize(responseEnvelope.fromDeviceName.orEmpty())?.let { name ->
                repository.updatePeerName(peer.deviceId, name)
            }
            val newReceivedCursor = nextReceivedCursor(peer.receivedCursor, responseEnvelope.changes)
            repository.updatePeerCursors(
                deviceId = peer.deviceId,
                receivedCursor = newReceivedCursor,
                peerReceivedCursor = responseEnvelope.cursor
            )
            "Synced with ${peer.name}."
        }
    }

    suspend fun handleExchange(fromDeviceId: String, encryptedPayload: String, requestEphemeralKey: String, exchangeId: String): ExchangeResponse {
        noteActivity()
        return runCatching {
            if (exchangeId.isBlank()) {
                return ExchangeResponse(ok = false, message = "Missing exchange id.")
            }
            if (unconfirmedPeerDeviceId() == fromDeviceId) {
                return ExchangeResponse(ok = false, message = "Confirm the pairing fingerprint on this device before syncing.")
            }
            val peer = repository.getPeer(fromDeviceId)
                ?: return ExchangeResponse(ok = false, message = "Not paired.")
            val peerKey = peer.publicKey
                ?: return ExchangeResponse(ok = false, message = "Peer has no key.")
            val peerEphemeral = runCatching { decodeBase64(requestEphemeralKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                return ExchangeResponse(ok = false, message = "Invalid ephemeral key.")
            }
            val channelKey = channelKeys.inboundChannelKey(peerKey, peerEphemeral)
            val envelope = decodeEnvelope(
                crypto.decrypt(channelKey, aad(from = fromDeviceId, to = identity.deviceId, exchangeId = exchangeId), decodeBase64(encryptedPayload))
            )
            if (envelope.fromDeviceId != fromDeviceId) {
                return ExchangeResponse(ok = false, message = "Sender mismatch.")
            }
            if (isExchangeReplay(fromDeviceId, exchangeId)) {
                return ExchangeResponse(ok = false, message = "Replayed exchange.")
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
            // The sender referenced image blobs this device may not have; pull
            // them from the sender's advertised address WITHOUT blocking the
            // exchange round-trip (the response goes out first, the blobs
            // follow in the background and land as placeholders until then).
            val pendingRefs = envelope.changes.messages.flatMap { it.imageRefs }
            val senderAddress = envelope.fetchAddress
            if (pendingRefs.isNotEmpty() && senderAddress != null && blobStore != null) {
                scope.launch {
                    runCatching { blobTransfer.fetchMissingBlobs(fromDeviceId, senderAddress, pendingRefs, peerKey) }
                }
            }
            val newReceivedCursor = nextReceivedCursor(peer.receivedCursor, envelope.changes)
            repository.updatePeerCursors(
                deviceId = fromDeviceId,
                receivedCursor = newReceivedCursor,
                peerReceivedCursor = envelope.cursor
            )
            markExchangeSeen(fromDeviceId, exchangeId)

            val myChanges = repository.collectDelta(envelope.cursor)
            val myExchangeKey = channelKeys.outboundChannelKey(peerKey)
            val responseEnvelope = SyncEnvelope(
                fromDeviceId = identity.deviceId,
                cursor = newReceivedCursor,
                changes = myChanges,
                fromDeviceName = identity.deviceName,
                fetchAddress = advertisedFetchAddress()
            )
            val responsePayload = encodeBase64(
                crypto.encrypt(myExchangeKey.key, aad(from = identity.deviceId, to = fromDeviceId, exchangeId = exchangeId), encodeEnvelope(responseEnvelope))
            )
            ExchangeResponse(
                ok = true,
                exchangeId = exchangeId,
                payload = responsePayload,
                ephemeralPublicKey = encodeBase64(myExchangeKey.ephemeralPublicKey)
            )
        }.getOrElse { error ->
            ExchangeResponse(ok = false, message = error.message ?: "Sync failed.")
        }
    }

    /**
     * The receiver's new cursor after applying [changes]: the highest sync
     * sequence carried by the envelope PLUS ONE when it carries anything.
     *
     * Sync sequences are strictly monotone DEVICE-LOCAL counters: every local
     * write and every applied incoming row is stamped with a fresh value
     * (see SyncRepository.applyChanges), so the delta queries
     * (`syncSeq >= cursor`) have no boundary/equality cases and a cursor of
     * `maxSeq + 1` is exact: it cannot skip a row, and nothing is ever
     * re-sent. An empty envelope carries no information and must not move
     * the cursor (a row could still be written between the query and the
     * response).
     */
    private fun nextReceivedCursor(oldCursor: Long, changes: SyncChanges): Long =
        if (changes.isEmpty) oldCursor else changes.maxSyncSeq + 1

    private fun encodeEnvelope(envelope: SyncEnvelope): ByteArray =
        json.encodeToString(SyncEnvelope.serializer(), envelope).encodeToByteArray()

    private fun decodeEnvelope(bytes: ByteArray): SyncEnvelope =
        json.decodeFromString(SyncEnvelope.serializer(), bytes.decodeToString())

    private companion object {
        // Exchange ids are only meaningful within minutes of an exchange; a
        // bounded window keeps the seen-set small while still blocking
        // verbatim replays.
        const val REPLAY_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_SEEN_EXCHANGES = 256
    }
}

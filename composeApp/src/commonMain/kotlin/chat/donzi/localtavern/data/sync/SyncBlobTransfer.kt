package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

// Out-of-band transfer of content-addressed image blobs between paired
// devices. Rows carry only SHA-256 refs; the referenced bytes are pulled (or
// served) over the authenticated /blob/fetch endpoint in 512 KB chunks, with
// offset-based resume, strict reassembly-size checks and per-session
// missing-ref memory. Progress is surfaced through SyncUiState.blobProgress.
class SyncBlobTransfer(
    private val crypto: SyncCrypto,
    private val repository: SyncRepository,
    private val state: MutableStateFlow<SyncUiState>,
    private val identityProvider: () -> SyncIdentity,
    private val httpClient: HttpClient,
    private val blobStore: BlobStore?,
    private val channelKeys: SyncChannelKeys,
    private val noteActivity: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val identity get() = identityProvider()

    // Ref hashes the peer reported as not serving; they are not re-fetched
    // during this app session (they would only be re-reported missing).
    private val knownMissingRefs = mutableSetOf<String>()
    private val knownMissingMutex = Mutex()

    private suspend fun isRefKnownMissing(hash: String): Boolean =
        knownMissingMutex.withLock { hash in knownMissingRefs }

    private suspend fun rememberMissingRefs(refs: List<String>) {
        knownMissingMutex.withLock { knownMissingRefs.addAll(refs) }
    }

    /**
     * Pulls every [refs] blob this device does not yet have from [address]
     * over the authenticated /blob/fetch endpoint, chunk by chunk, and stores
     * the reassembled blobs in the local store. The protocol is stateless and
     * strictly per-ref with offset addressing, so a dropped request is simply
     * retried from the last acknowledged offset. Refs the peer cannot serve
     * are remembered for the session and skipped in later syncs. Progress is
     * surfaced through [SyncUiState.blobProgress].
     */
    suspend fun fetchMissingBlobs(peerId: String, address: String, refs: List<SyncImageRef>, peerPublicKey: ByteArray) {
        val store = blobStore ?: return
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: SYNC_PORT

        // Only fetch what is genuinely missing; refs the peer already
        // reported as missing are skipped for this session.
        val pending = refs.filter { store.read(it.sha256) == null && !isRefKnownMissing(it.sha256) }
        if (pending.isEmpty()) return

        val totalBytes = pending.sumOf { it.size }
        var doneBytes = 0L
        noteActivity()
        state.update { it.copy(blobProgress = BlobTransferProgress(doneBytes, totalBytes)) }
        try {
            var refIndex = 0
            while (refIndex < pending.size) {
                val ref = pending[refIndex]
                // Resume-safe accumulation: chunks for the current ref are
                // reassembled from the first byte.
                val builder = ArrayList<ByteArray>()
                var offset = 0
                var finalSize = 0
                var refComplete = false
                while (!refComplete) {
                    noteActivity()
                    val request = encodeBlobRequest(BlobFetchPayload(
                        refs = pending.map { it.sha256 },
                        refIndex = refIndex,
                        offset = offset
                    ))
                    val channelKey = channelKeys.outboundChannelKey(peerPublicKey)
                    val aad = aad(from = identity.deviceId, to = peerId)
                    val payload = encodeBase64(crypto.encrypt(channelKey.key, aad, request))
                    val response: BlobFetchResponse = httpClient.post("http://$host:$port/blob/fetch") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            BlobFetchRequest(
                                fromDeviceId = identity.deviceId,
                                payload = payload,
                                ephemeralPublicKey = encodeBase64(channelKey.ephemeralPublicKey)
                            )
                        )
                    }.body()
                    if (!response.ok) {
                        // The peer refused the fetch; abandon the remaining refs.
                        refIndex = pending.size
                        break
                    }

                    val resultPayload = response.payload ?: run {
                        refIndex = pending.size
                        break
                    }
                    val peerEphemeral = runCatching { decodeBase64(response.ephemeralPublicKey) }.getOrNull()
                    if (peerEphemeral == null || peerEphemeral.size != 32) {
                        refIndex = pending.size
                        break
                    }
                    val responseKey = channelKeys.inboundChannelKey(peerPublicKey, peerEphemeral)
                    val result = decodeBlobResult(
                        crypto.decrypt(responseKey, aad(from = peerId, to = identity.deviceId), decodeBase64(resultPayload))
                    )
                    if (result.refIndex != refIndex) {
                        // Protocol drift: never loop on an unexpected index.
                        refIndex = pending.size
                        break
                    }
                    if (result.missing.isNotEmpty()) {
                        rememberMissingRefs(result.missing)
                        doneBytes += ref.size
                        refIndex++
                        refComplete = true
                        state.update { it.copy(blobProgress = BlobTransferProgress(doneBytes, totalBytes)) }
                        continue
                    }
                    if (result.data.isNotEmpty()) {
                        builder.add(decodeBase64(result.data))
                        finalSize = result.total
                    } else if (!result.hasMore) {
                        // Empty blob or offset past the end: nothing to store.
                        refIndex = pending.size
                        break
                    }
                    offset += CHUNK_BYTES
                    doneBytes += minOf(CHUNK_BYTES.toLong(), (finalSize - (offset - CHUNK_BYTES)).coerceAtLeast(0).toLong())
                    state.update { it.copy(blobProgress = BlobTransferProgress(doneBytes, totalBytes)) }
                    if (!result.hasMore) {
                        // Ref complete: store the reassembled blob — only when
                        // the reassembly exactly matches the advertised total,
                        // so a truncated reassembly never poisons future
                        // fetches of the same ref.
                        val combined = ByteArray(builder.sumOf { it.size })
                        var pos = 0
                        builder.forEach { part -> part.copyInto(combined, pos); pos += part.size }
                        if (combined.size == finalSize && store.read(ref.sha256) == null) store.write(ref.sha256, combined)
                        refIndex++
                        refComplete = true
                    }
                }
            }
        } finally {
            state.update { it.copy(blobProgress = null) }
        }
    }

    // Server side of /blob/fetch: serves one chunk of the addressed ref to a
    // paired device, or reports the ref as missing. Stateless and strictly
    // per-ref: the chunk is served at exactly (refIndex, offset); the client
    // advances to the next ref itself.
    suspend fun handleBlobFetch(fromDeviceId: String, encryptedPayload: String, requestEphemeralKey: String): BlobFetchResponse {
        noteActivity()
        return runCatching {
            val peer = repository.getPeer(fromDeviceId)
                ?: return BlobFetchResponse(ok = false, message = "Not paired.")
            val peerKey = peer.publicKey
                ?: return BlobFetchResponse(ok = false, message = "Peer has no key.")
            val peerEphemeral = runCatching { decodeBase64(requestEphemeralKey) }.getOrNull()
            if (peerEphemeral == null || peerEphemeral.size != 32) {
                return BlobFetchResponse(ok = false, message = "Invalid ephemeral key.")
            }
            val channelKey = channelKeys.inboundChannelKey(peerKey, peerEphemeral)
            val payload = decodeBlobRequest(
                crypto.decrypt(channelKey, aad(from = fromDeviceId, to = identity.deviceId), decodeBase64(encryptedPayload))
            )

            val store = blobStore
            val result = if (store == null || payload.refIndex !in payload.refs.indices) {
                BlobFetchResult(
                    refIndex = payload.refIndex,
                    offset = payload.offset,
                    total = 0,
                    data = "",
                    hasMore = false,
                    missing = payload.refs.getOrNull(payload.refIndex)?.let { listOf(it) } ?: emptyList()
                )
            } else {
                val hash = payload.refs[payload.refIndex]
                val bytes = store.read(hash)
                if (bytes == null) {
                    BlobFetchResult(
                        refIndex = payload.refIndex,
                        offset = payload.offset,
                        total = 0,
                        data = "",
                        hasMore = false,
                        missing = listOf(hash)
                    )
                } else {
                    val chunk = if (payload.offset < bytes.size) {
                        bytes.copyOfRange(payload.offset, minOf(payload.offset + CHUNK_BYTES, bytes.size))
                    } else {
                        ByteArray(0)
                    }
                    BlobFetchResult(
                        refIndex = payload.refIndex,
                        offset = payload.offset,
                        total = bytes.size,
                        data = encodeBase64(chunk),
                        hasMore = payload.offset + chunk.size < bytes.size
                    )
                }
            }

            val outbound = channelKeys.outboundChannelKey(peerKey)
            val responsePayload = encodeBase64(
                crypto.encrypt(outbound.key, aad(from = identity.deviceId, to = fromDeviceId), encodeBlobResult(result))
            )
            BlobFetchResponse(ok = true, payload = responsePayload, ephemeralPublicKey = encodeBase64(outbound.ephemeralPublicKey))
        }.getOrElse { error ->
            BlobFetchResponse(ok = false, message = error.message ?: "Blob fetch failed.")
        }
    }


    private fun encodeBlobRequest(payload: BlobFetchPayload): ByteArray =
        json.encodeToString(BlobFetchPayload.serializer(), payload).encodeToByteArray()

    private fun decodeBlobRequest(bytes: ByteArray): BlobFetchPayload =
        json.decodeFromString(BlobFetchPayload.serializer(), bytes.decodeToString())

    private fun encodeBlobResult(result: BlobFetchResult): ByteArray =
        json.encodeToString(BlobFetchResult.serializer(), result).encodeToByteArray()

    private fun decodeBlobResult(bytes: ByteArray): BlobFetchResult =
        json.decodeFromString(BlobFetchResult.serializer(), bytes.decodeToString())
}

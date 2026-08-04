package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.utils.Hashing
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
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

    // Upper bounds on a single ref fetch: a malicious peer could otherwise
    // stream chunks forever or claim a gigantic blob. Legitimate message
    // images are at most a few hundred KB; 128 chunks of 512 KB (64 MB) is an
    // order of magnitude beyond any legitimately advertised ref.
    private companion object {
        const val MAX_CHUNKS_PER_REF = 128
        const val MAX_FETCH_BYTES_PER_REF = 64L * 1024 * 1024
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
        // Refs arrive from the wire: only canonical SHA-256 hex keys are ever
        // read or written, and the fetch address is format-validated so a
        // hostile peer cannot point this device at arbitrary hosts (SSRF) or
        // traverse the blob directory.
        val validatedAddress = validateFetchAddress(address) ?: return
        val host = validatedAddress.first
        val port = validatedAddress.second
        val pending = refs
            .filter { Hashing.isValidSha256Hex(it.sha256) }
            .filter { store.read(it.sha256) == null && !isRefKnownMissing(it.sha256) }
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
                var chunkCount = 0
                var refComplete = false
                while (!refComplete) {
                    noteActivity()
                    val exchangeId = freshExchangeId()
                    val request = encodeBlobRequest(BlobFetchPayload(
                        refs = pending.map { it.sha256 },
                        refIndex = refIndex,
                        offset = offset
                    ))
                    val channelKey = channelKeys.outboundChannelKey(peerPublicKey)
                    val aad = aad(from = identity.deviceId, to = peerId, exchangeId = exchangeId)
                    val payload = encodeBase64(crypto.encrypt(channelKey.key, aad, request))
                    val response: BlobFetchResponse = httpClient.post("http://$host:$port/blob/fetch") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            BlobFetchRequest(
                                fromDeviceId = identity.deviceId,
                                exchangeId = exchangeId,
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
                    if (response.exchangeId != exchangeId) {
                        // A response from a different exchange context: never
                        // trust it (protects against response swapping).
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
                        crypto.decrypt(responseKey, aad(from = peerId, to = identity.deviceId, exchangeId = exchangeId), decodeBase64(resultPayload))
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
                        // Chunks are addressed by fixed offsets, so a chunk
                        // larger than CHUNK_BYTES (or of unexpected size) is a
                        // protocol violation: a peer on a different chunk size
                        // would silently shift every subsequent offset. Refuse
                        // the transfer instead of reassembling garbage.
                        val chunk = runCatching { decodeBase64(result.data) }.getOrNull()
                        if (chunk == null || chunk.size > CHUNK_BYTES) {
                            refIndex = pending.size
                            break
                        }
                        builder.add(chunk)
                        finalSize = result.total
                        chunkCount++
                    } else if (!result.hasMore) {
                        // Empty blob or offset past the end: nothing to store.
                        refIndex = pending.size
                        break
                    }
                    offset += CHUNK_BYTES
                    doneBytes += minOf(CHUNK_BYTES.toLong(), (finalSize - (offset - CHUNK_BYTES)).coerceAtLeast(0).toLong())
                    state.update { it.copy(blobProgress = BlobTransferProgress(doneBytes, totalBytes)) }
                    // Bounds against a hostile responder: the reassembly must
                    // never exceed the advertised size (plus one chunk of
                    // slack), the per-ref chunk count, or the absolute cap.
                    val assembled = builder.sumOf { it.size }
                    val allowedSize = maxOf(ref.size, finalSize.toLong())
                    if (chunkCount > MAX_CHUNKS_PER_REF ||
                        assembled > allowedSize + CHUNK_BYTES ||
                        assembled > MAX_FETCH_BYTES_PER_REF
                    ) {
                        refIndex = pending.size
                        break
                    }
                    if (!result.hasMore) {
                        // Ref complete: store the reassembled blob — only when
                        // the reassembly exactly matches the advertised total
                        // AND its content hash matches the ref. Content
                        // addressing is only trustworthy when the address is
                        // verified: without the hash check a malicious (or
                        // buggy) peer could poison the store, and the garbage
                        // would then propagate to every other device that
                        // fetches (or is served) the same ref.
                        val combined = ByteArray(builder.sumOf { it.size })
                        var pos = 0
                        builder.forEach { part -> part.copyInto(combined, pos); pos += part.size }
                        val hashMatches = Hashing.sha256Hex(combined) == ref.sha256
                        if (hashMatches && combined.size == finalSize && store.read(ref.sha256) == null) store.write(ref.sha256, combined)
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
    suspend fun handleBlobFetch(fromDeviceId: String, encryptedPayload: String, requestEphemeralKey: String, exchangeId: String): BlobFetchResponse {
        noteActivity()
        try {
            if (exchangeId.isBlank()) {
                return BlobFetchResponse(ok = false, message = "Missing exchange id.")
            }
            if (!isValidDeviceId(fromDeviceId)) {
                // deviceIds are embedded (unescaped) in the AEAD associated
                // data; reject anything outside the generated alphabet.
                return BlobFetchResponse(ok = false, message = "Invalid device id.")
            }
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
                crypto.decrypt(channelKey, aad(from = fromDeviceId, to = identity.deviceId, exchangeId = exchangeId), decodeBase64(encryptedPayload))
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
                val bytes = if (Hashing.isValidSha256Hex(hash)) store.read(hash) else null
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
                crypto.encrypt(outbound.key, aad(from = identity.deviceId, to = fromDeviceId, exchangeId = exchangeId), encodeBlobResult(result))
            )
            return BlobFetchResponse(ok = true, exchangeId = exchangeId, payload = responsePayload, ephemeralPublicKey = encodeBase64(outbound.ephemeralPublicKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BlobFetchResponse(ok = false, message = e.message ?: "Blob fetch failed.")
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

// Fresh random identifier binding one request to its response: it rides
// OUTSIDE the ciphertext (both peers need it before decrypting) and inside
// the AEAD associated data, so a captured request cannot be replayed against
// a different exchange context, and a response can be verified as belonging
// to the request it answers.
internal fun freshExchangeId(): String {
    val bytes = ByteArray(16)
    CryptographyRandom.Default.nextBytes(bytes)
    return encodeBase64(bytes).replace("+", "a").replace("/", "b").replace("=", "").take(22)
}

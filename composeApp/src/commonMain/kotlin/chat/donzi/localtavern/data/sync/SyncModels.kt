package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.ChatSession
import chat.donzi.localtavern.data.database.MessageEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.database.PromptBlockEntity
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageList
import kotlinx.serialization.Serializable

// Row snapshots exchanged during sync. ByteArray fields are base64-encoded
// by kotlinx.serialization automatically (avatars, message images, keys).

@Serializable
data class SyncCharacter(
    val id: String,
    val name: String,
    val description: String?,
    val personality: String,
    val scenario: String,
    val firstMes: String?,
    val mesExample: String?,
    val creatorNotes: String?,
    val altGreetings: String?,
    val avatarData: ByteArray?,
    val isAssistant: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    val syncSeq: Long = 0,
    val systemPrompt: String?,
    val postHistoryInstructions: String?,
    val creator: String?,
    val characterVersion: String?,
    val tags: String?,
    val extensions: String?,
    val characterBook: String?
)

@Serializable
data class SyncPersona(
    val id: String,
    val name: String,
    val description: String?,
    val avatarData: ByteArray?,
    val updatedAt: Long,
    val isDeleted: Long,
    val syncSeq: Long = 0
)

@Serializable
data class SyncSession(
    val id: String,
    val characterId: String,
    val personaId: String,
    val title: String?,
    val lastTimestamp: Long,
    val currentMessageId: String?,
    val parentSessionId: String?,
    val updatedAt: Long,
    val isDeleted: Long,
    val syncSeq: Long = 0
)

// A content-addressed reference to one message-image blob on the sender.
// The bytes travel OUT of band (the /blob/fetch protocol); the envelope only
// carries the references, so sync exchanges stay small even with many
// images. Older peers ship the bytes inline via SyncMessage.imageData, which
// the receiver converts into refs + stored blobs.
@Serializable
data class SyncImageRef(
    val sha256: String,
    val size: Long
)

@Serializable
data class SyncMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val parentId: String?,
    val isActivePath: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    // Legacy inline images (pre-blob-store peers); empty on modern sends.
    val imageData: ByteArray? = null,
    // Content-addressed refs to the image blobs (modern sends).
    val imageRefs: List<SyncImageRef> = emptyList(),
    val reasoningText: String?,
    val costEstimate: Double?,
    val syncSeq: Long = 0
)

@Serializable
data class SyncApiConnection(
    val id: String,
    val provider: String,
    val name: String,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    // OpenRouter-style upstream provider routing; null = endpoint default.
    // Default value keeps envelopes from older peers parseable.
    val inferenceProvider: String? = null,
    // OpenRouter quantization preference; null = endpoint default.
    val quantization: String? = null,
    val isActive: Long,
    val isChatCompletion: Long,
    val lastUsed: Long?,
    val temperature: Double,
    val topP: Double,
    val topK: Long,
    val presencePenalty: Double,
    val frequencyPenalty: Double,
    val contextLimit: Long,
    val responseLimit: Long,
    val displayOrder: Long,
    val timeoutLimit: Long,
    val reasoningOverride: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    val syncSeq: Long = 0
)

@Serializable
data class SyncPromptBlock(
    val id: String,
    val name: String,
    val template: String,
    val isEnabled: Long,
    val isCustom: Long,
    val displayOrder: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    val syncSeq: Long = 0
)

@Serializable
data class SyncChanges(
    val characters: List<SyncCharacter> = emptyList(),
    val personas: List<SyncPersona> = emptyList(),
    val sessions: List<SyncSession> = emptyList(),
    val messages: List<SyncMessage> = emptyList(),
    val apiConnections: List<SyncApiConnection> = emptyList(),
    val promptBlocks: List<SyncPromptBlock> = emptyList()
) {
    val isEmpty: Boolean
        get() = characters.isEmpty() && personas.isEmpty() && sessions.isEmpty() &&
            messages.isEmpty() && apiConnections.isEmpty() && promptBlocks.isEmpty()

    /** Highest updatedAt carried by any row in these changes. */
    val maxUpdatedAt: Long
        get() = (characters.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(personas.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(sessions.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(messages.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(apiConnections.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(promptBlocks.maxOfOrNull { it.updatedAt } ?: 0L)

    /** Highest sync sequence carried by any row in these changes. */
    val maxSyncSeq: Long
        get() = (characters.maxOfOrNull { it.syncSeq } ?: 0L)
            .coerceAtLeast(personas.maxOfOrNull { it.syncSeq } ?: 0L)
            .coerceAtLeast(sessions.maxOfOrNull { it.syncSeq } ?: 0L)
            .coerceAtLeast(messages.maxOfOrNull { it.syncSeq } ?: 0L)
            .coerceAtLeast(apiConnections.maxOfOrNull { it.syncSeq } ?: 0L)
            .coerceAtLeast(promptBlocks.maxOfOrNull { it.syncSeq } ?: 0L)
}

// One side's view of an exchange: everything the sender changed since the
// recipient's last cursor, plus the sender's own cursor watermark.
@Serializable
data class SyncEnvelope(
    val fromDeviceId: String,
    val cursor: Long,
    val changes: SyncChanges,
    // Display-only name of the sending device. It rides INSIDE the
    // authenticated ciphertext, so only the device holding the paired
    // private key can rename itself on a peer; recipients update their peer
    // row but never the cursor machinery. Default keeps envelopes from
    // older peers parseable.
    val fromDeviceName: String? = null,
    // Where the sender's sync server can be reached, so the RECEIVER can
    // pull image blobs referenced by this envelope out of band. The receiver
    // never trusts it blindly: /blob/fetch is authenticated by the same
    // paired-key scheme as /exchange.
    val fetchAddress: String? = null
)

// ---------- Blob fetch protocol (message images travel out of band) ----------

// Encrypted body of a /blob/fetch request: the content-addressed refs the
// receiver is missing, and where to resume. The server is stateless: each
// request addresses exactly one chunk via (refIndex, offset), so a dropped
// request can be retried from the last acknowledged offset.
@Serializable
data class BlobFetchPayload(
    val refs: List<String> = emptyList(),
    val refIndex: Int = 0,
    val offset: Int = 0
)

// Encrypted body of the /blob/fetch response: one chunk of the requested
// ref, or a missing marker for it. The server is strictly per-ref: it serves
// the chunk at (refIndex, offset) of the addressed ref, or reports that ref
// as missing — it never auto-advances to another ref. The receiver
// reassembles chunks sequentially (offset advances by CHUNK_BYTES), stores
// the blob once a ref is complete (hasMore = false), and moves to the next
// ref.
@Serializable
data class BlobFetchResult(
    val refIndex: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
    val data: String = "",
    val hasMore: Boolean = false,
    // Set when the addressed ref cannot be served (its blob is absent).
    val missing: List<String> = emptyList()
)

// Wire bodies of /blob/fetch: an encrypted payload plus the sender's
// per-request ephemeral X25519 public key, identical in shape to /exchange.
// The exchange id binds this request to its response AND to the AEAD
// associated data of both payloads; it rides outside the ciphertext because
// both peers need it before they can derive the channel key.
@Serializable
data class BlobFetchRequest(
    val fromDeviceId: String,
    val exchangeId: String = "",
    val payload: String,
    val ephemeralPublicKey: String = ""
)

@Serializable
data class BlobFetchResponse(
    val ok: Boolean,
    val message: String = "",
    val exchangeId: String = "",
    val payload: String? = null,
    val ephemeralPublicKey: String = ""
)

// Pairing handshake. The PIN is never transmitted: [nonce] is a fresh
// CSPRNG value that doubles as the KDF salt, and [pinProof] is
// HMAC-SHA256(PBKDF2(pin, nonce), deviceId|publicKey|nonce), which the host
// verifies against the PIN it displayed. A sniffer that relays the exchange
// cannot swap keys without knowing the PIN, and the stretched KDF makes
// offline PIN recovery from a captured proof prohibitively expensive.
@Serializable
data class PairRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,     // base64 X25519 public key
    val nonce: String,         // base64 random 16 bytes (PBKDF2 salt)
    val pinProof: String       // base64 HMAC-SHA256 over deviceId|publicKey|nonce
)

@Serializable
data class PairResponse(
    val ok: Boolean,
    val message: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val publicKey: String = "" // base64 X25519 public key
)

@Serializable
data class HelloResponse(
    val deviceId: String,
    val deviceName: String
)

// ---------- Wire-input validation ----------

// Device ids are embedded (unescaped) into the AEAD associated data of every
// encrypted payload, so anything outside the generated alphabet must be
// rejected before it can be echoed back into a security context. Generated
// ids are 24 lowercase base64url-ish chars (see SyncIdentity.randomDeviceId).
internal fun isValidDeviceId(deviceId: String): Boolean =
    deviceId.isNotEmpty() && deviceId.length <= 64 &&
        deviceId.all { it.isLowerCase() || it.isDigit() || it == '_' || it == '-' }

// Validates a peer-supplied fetch address before it is used to make HTTP
// requests: an envelope's fetchAddress is attacker-controlled, so it must be
// a bare "host:port" — no scheme, credentials, path, query or whitespace —
// with a numeric port and an IPv4 or plain hostname host. Anything else
// (e.g. "http://169.254.169.254/latest/meta-data") is rejected, which blocks
// the SSRF vector of pointing this device at arbitrary internal hosts.
// Returns (host, port) or null when the address is unusable.
internal fun validateFetchAddress(address: String?): Pair<String, Int>? {
    val trimmed = address?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.length > 255) return null
    if (trimmed.contains("://") || trimmed.contains("@") || trimmed.contains("/") ||
        trimmed.contains("?") || trimmed.contains("#") || trimmed.any { it.isWhitespace() }
    ) return null
    val port = trimmed.substringAfterLast(':')
    if (port == trimmed) return null
    val portNumber = port.toIntOrNull() ?: return null
    if (portNumber !in 1..65535) return null
    val host = trimmed.substringBeforeLast(':')
    if (host.isBlank() || host.length > 253) return null
    // Colons in the host mean IPv6, which this protocol does not support
    // (addresses are advertised as plain "ip:port").
    if (':' in host) return null
    if (!isIpv4Literal(host) && !isPlainHostname(host)) return null
    return host to portNumber
}

private fun isIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        val value = part.toIntOrNull() ?: return false
        // Reject leading zeros (octal ambiguity) and out-of-range octets.
        value in 0..255 && part == value.toString()
    }
}

private fun isPlainHostname(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (!host.first().isLetterOrDigit() || !host.last().isLetterOrDigit()) return false
    return host.all { char ->
        char.isLetterOrDigit() || char == '-' || char == '.'
    }
}

// ---------- Entity -> DTO mappers ----------
// Convert database rows (including tombstones) into the wire snapshots sent
// inside sync envelopes. API keys travel as portable plaintext inside the
// end-to-end-encrypted envelope (see ApiKeyCipher.toPortableForm); a key this
// device cannot read is withheld (null) so the peer never stores an
// undecryptable blob. Message images ride as content-addressed refs, with the
// bytes kept for legacy peers that still expect inline imageData.

internal fun CharacterEntity.toSync() = SyncCharacter(
    id = id, name = name, description = description, personality = personality ?: "",
    scenario = scenario ?: "", firstMes = firstMes, mesExample = mesExample,
    creatorNotes = creatorNotes, altGreetings = altGreetings, avatarData = avatarData,
    isAssistant = isAssistant, updatedAt = updatedAt, isDeleted = isDeleted,
    syncSeq = syncSeq,
    systemPrompt = systemPrompt, postHistoryInstructions = postHistoryInstructions,
    creator = creator, characterVersion = characterVersion, tags = tags,
    extensions = extensions, characterBook = characterBook
)

internal fun PersonaEntity.toSync() = SyncPersona(
    id = id, name = name, description = description, avatarData = avatarData,
    updatedAt = updatedAt, isDeleted = isDeleted, syncSeq = syncSeq
)

internal fun ChatSession.toSync() = SyncSession(
    id = id, characterId = characterId, personaId = personaId, title = title,
    lastTimestamp = lastTimestamp, currentMessageId = currentMessageId,
    parentSessionId = parentSessionId, updatedAt = updatedAt, isDeleted = isDeleted,
    syncSeq = syncSeq
)

internal suspend fun MessageEntity.toSync(blobStore: BlobStore?): SyncMessage {
    val refs = deserializeImageRefs(imageRefs)
    return SyncMessage(
        id = id, sessionId = sessionId, role = role, content = content, timestamp = timestamp,
        parentId = parentId, isActivePath = isActivePath, updatedAt = updatedAt,
        isDeleted = isDeleted,
        // Legacy wire format: the image bytes ride in the envelope (loaded
        // from the store); the refs ride along for newer peers to fetch.
        imageData = blobStore?.let { store ->
            refs.mapNotNull { store.read(it.sha256) }.takeIf { it.isNotEmpty() }?.let { serializeImageList(it) }
        },
        imageRefs = refs.map { SyncImageRef(sha256 = it.sha256, size = it.size) },
        reasoningText = reasoningText,
        costEstimate = costEstimate, syncSeq = syncSeq
    )
}

internal fun ApiConnection.toSync(cipher: ApiKeyCipher?) = SyncApiConnection(
    id = id, provider = provider, name = name, baseUrl = baseUrl,
    // The key travels as portable plaintext inside the end-to-end-encrypted
    // envelope (see ApiKeyCipher.toPortableForm); a key this device cannot
    // read is withheld (null) so the peer never stores an undecryptable blob.
    apiKey = if (cipher != null) cipher.toPortableForm(apiKey) else apiKey,
    model = model, inferenceProvider = inferenceProvider, quantization = quantization,
    isActive = 0L, isChatCompletion = isChatCompletion,
    lastUsed = lastUsed, temperature = temperature, topP = topP,
    topK = topK, presencePenalty = presencePenalty,
    frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
    responseLimit = responseLimit, displayOrder = displayOrder,
    timeoutLimit = timeoutLimit, reasoningOverride = reasoningOverride,
    updatedAt = updatedAt, isDeleted = isDeleted, syncSeq = syncSeq
)

internal fun PromptBlockEntity.toSync() = SyncPromptBlock(
    id = id, name = name, template = template, isEnabled = isEnabled,
    isCustom = isCustom, displayOrder = displayOrder, updatedAt = updatedAt,
    isDeleted = isDeleted, syncSeq = syncSeq
)
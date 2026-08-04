package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.ApiConnection
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.ChatSession
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.MessageEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.PromptBlockEntity
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageList
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageList
import chat.donzi.localtavern.utils.serializeImageRefs
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// Reads and applies sync deltas. Everything here works on raw rows (including
// tombstones, i.e. isDeleted=1), which the app-facing queries normally hide.
class SyncRepository(
    private val database: LocalTavernDB,
    private val identity: SyncIdentity,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Converts API keys between the device-stored form (encrypted under the
    // local backend) and the portable plaintext form used inside the
    // end-to-end-encrypted sync envelope. Null keeps the legacy behavior of
    // shipping the stored value verbatim (tests, or backends without a cipher).
    private val apiKeyCipher: ApiKeyCipher? = null,
    // Hybrid logical clock stamping updatedAt. MUST be the same instance the
    // write repositories use: applyChanges advances it past every timestamp
    // observed from a peer, and local writes stamp from it, which is what
    // makes LWW converge under wall-clock skew.
    private val clock: LogicalClock = LogicalClock(database),
    // Content-addressed blob store for message images. Incoming rows write
    // their blobs here and outbound envelopes read them from here; a null
    // store (tests) ships/holds no image bytes.
    private val blobStore: BlobStore? = null
) {
    private val queries get() = database.localTavernDBQueries

    // ---------- Peer store ----------

    suspend fun upsertPeer(peer: SyncPeer): Unit = withContext(ioDispatcher) {
        database.transaction {
            val existing = queries.selectSyncPeerAny(peer.deviceId).executeAsOneOrNull()
            if (existing == null) {
                queries.insertSyncPeer(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    publicKey = peer.publicKey,
                    lastKnownAddress = peer.lastKnownAddress,
                    receivedCursor = peer.receivedCursor,
                    peerReceivedCursor = peer.peerReceivedCursor,
                    lastSyncAt = peer.lastSyncAt,
                    updatedAt = peer.updatedAt,
                    isDeleted = 0L
                )
            } else {
                queries.updateSyncPeer(
                    name = peer.name,
                    publicKey = peer.publicKey,
                    lastKnownAddress = peer.lastKnownAddress,
                    receivedCursor = peer.receivedCursor,
                    peerReceivedCursor = peer.peerReceivedCursor,
                    lastSyncAt = peer.lastSyncAt,
                    updatedAt = peer.updatedAt,
                    deviceId = peer.deviceId
                )
            }
        }
    }

    fun observePeers(): Flow<List<SyncPeer>> =
        queries.selectAllSyncPeers().asFlow().mapToList(ioDispatcher)

    suspend fun getPeers(): List<SyncPeer> = withContext(ioDispatcher) {
        queries.selectAllSyncPeers().executeAsList()
    }

    suspend fun getPeer(deviceId: String): SyncPeer? = withContext(ioDispatcher) {
        queries.selectSyncPeer(deviceId).executeAsOneOrNull()
    }

    suspend fun updatePeerAddress(deviceId: String, address: String) = withContext(ioDispatcher) {
        queries.updateSyncPeerAddress(lastKnownAddress = address, updatedAt = currentTimeMillis(), deviceId = deviceId)
    }

    /**
     * Updates a peer's DISPLAY name (user reference only). Only called with
     * names received through an authenticated sync exchange, so a device
     * cannot rename itself to anything but the holder of its own key.
     * Cursors and delta state are never touched.
     */
    suspend fun updatePeerName(deviceId: String, name: String) = withContext(ioDispatcher) {
        queries.updateSyncPeerName(name = name, updatedAt = currentTimeMillis(), deviceId = deviceId)
    }

    suspend fun updatePeerCursors(deviceId: String, receivedCursor: Long, peerReceivedCursor: Long) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        queries.updateSyncPeerCursors(
            receivedCursor = receivedCursor,
            peerReceivedCursor = peerReceivedCursor,
            lastSyncAt = now,
            updatedAt = now,
            deviceId = deviceId
        )
    }

    suspend fun deletePeer(deviceId: String) = withContext(ioDispatcher) {
        queries.deleteSyncPeer(updatedAt = currentTimeMillis(), deviceId = deviceId)
    }

    /** Invalidates every pairing (used when the device identity key rotates). */
    suspend fun clearAllPeers() = withContext(ioDispatcher) {
        queries.deleteAllSyncPeers(updatedAt = currentTimeMillis())
    }

    // ---------- Delta collection ----------

    /** All rows changed after [since] (including tombstones). */
    suspend fun collectDelta(since: Long): SyncChanges = withContext(ioDispatcher) {
        SyncChanges(
            characters = queries.selectCharacterDeltas(since).executeAsList().map { it.toSync() },
            personas = queries.selectPersonaDeltas(since).executeAsList().map { it.toSync() },
            sessions = queries.selectSessionDeltas(since).executeAsList().map { it.toSync() },
            messages = queries.selectMessageDeltas(since).executeAsList().map { it.toSync(blobStore) },
            apiConnections = queries.selectApiConnectionDeltas(since).executeAsList().map { it.toSync(apiKeyCipher) },
            promptBlocks = queries.selectPromptBlockDeltas(since).executeAsList().map { it.toSync() }
        )
    }

    // ---------- Applying remote changes (LWW) ----------

    /**
     * Applies incoming changes. For each row, the newer version wins
     * (updatedAt); on exact ties the lexicographically greater deviceId wins,
     * which both sides resolve identically so they converge.
     *
     * Afterwards the device's logical clock absorbs the envelope's highest
     * timestamp — even for rows rejected as stale — so a subsequent local
     * edit always out-stamps the version it was caused by, regardless of how
     * far behind this device's wall clock is.
     *
     * Rows that ARE applied are re-stamped with a fresh device-local sync
     * sequence ([LogicalClock.nextSyncSeq]): deltas cut on that sequence, so
     * a row arriving late (however low its updatedAt stamp) always gets a
     * sequence above every cursor this device has ever reported, and can
     * therefore always be forwarded to other peers. Rows rejected as stale
     * are not touched — their version loses, and the winning version will
     * arrive from its author.
     */
    suspend fun applyChanges(changes: SyncChanges, peerDeviceId: String) = withContext(ioDispatcher) {
        // Message image blobs are written to the store BEFORE the row touches
        // the database (crash-safe ordering: a row never references a missing
        // blob). Tombstoned rows are not persisted. Modern envelopes carry
        // only content-addressed refs; legacy envelopes (older peers) ship
        // the images inline, which are hashed and stored here.
        val preparedMessages = changes.messages.map { row ->
            val refs = when {
                row.imageRefs.isNotEmpty() -> row.imageRefs.map { chat.donzi.localtavern.domain.ImageRef(it.sha256, it.size) }
                row.imageData != null && row.isDeleted == 0L -> persistLegacyImages(row.imageData)
                else -> emptyList()
            }
            PreparedMessage(row, serializeImageRefs(refs))
        }
        database.transaction {
            changes.characters.forEach { row -> apply(row, peerDeviceId) }
            changes.personas.forEach { row -> apply(row, peerDeviceId) }
            changes.sessions.forEach { row -> apply(row, peerDeviceId) }
            preparedMessages.forEach { apply(it.row, peerDeviceId, it.refsJson) }
            changes.apiConnections.forEach { row -> apply(row, peerDeviceId) }
            changes.promptBlocks.forEach { row -> apply(row, peerDeviceId) }
            clock.absorb(changes.maxUpdatedAt)
        }
    }

    private data class PreparedMessage(val row: SyncMessage, val refsJson: String?)

    // Legacy wire images (serialized byte-list BLOB) -> content-addressed
    // blobs + references. Only writes blobs that are not already stored.
    private suspend fun persistLegacyImages(imageData: ByteArray): List<chat.donzi.localtavern.domain.ImageRef> {
        val store = blobStore ?: return emptyList()
        return deserializeImageList(imageData).map { img ->
            val hash = Hashing.sha256Hex(img)
            if (store.read(hash) == null) store.write(hash, img)
            chat.donzi.localtavern.domain.ImageRef(hash, img.size.toLong())
        }
    }

    private fun incomingWins(existingUpdatedAt: Long, incomingUpdatedAt: Long, peerDeviceId: String): Boolean {
        if (incomingUpdatedAt > existingUpdatedAt) return true
        if (incomingUpdatedAt < existingUpdatedAt) return false
        // Exact tie: deterministic tie-break, identical on both devices.
        return peerDeviceId > identity.deviceId
    }

    private fun apply(row: SyncCharacter, peerDeviceId: String) {
        val existing = queries.selectCharacterByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        // Applied rows are re-stamped with a device-local sync sequence (see
        // applyChanges): the row's own sequence (from its author) is only a
        // cursor hint for the sender; here it must be local so it can always
        // be forwarded.
        val seq = clock.nextSyncSeq()
        if (existing == null) {
            queries.insertCharacterFull(
                id = row.id, name = row.name, description = row.description,
                personality = row.personality, scenario = row.scenario, firstMes = row.firstMes,
                mesExample = row.mesExample, creatorNotes = row.creatorNotes, altGreetings = row.altGreetings,
                avatarData = row.avatarData, isAssistant = row.isAssistant, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, systemPrompt = row.systemPrompt,
                postHistoryInstructions = row.postHistoryInstructions, creator = row.creator,
                characterVersion = row.characterVersion, tags = row.tags, extensions = row.extensions,
                characterBook = row.characterBook, syncSeq = seq
            )
        } else {
            queries.upsertCharacterFull(
                name = row.name, description = row.description, personality = row.personality,
                scenario = row.scenario, firstMes = row.firstMes, mesExample = row.mesExample,
                creatorNotes = row.creatorNotes, altGreetings = row.altGreetings,
                avatarData = row.avatarData, isAssistant = row.isAssistant, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, systemPrompt = row.systemPrompt,
                postHistoryInstructions = row.postHistoryInstructions, creator = row.creator,
                characterVersion = row.characterVersion, tags = row.tags, extensions = row.extensions,
                characterBook = row.characterBook, syncSeq = seq, id = row.id
            )
        }
    }

    private fun apply(row: SyncPersona, peerDeviceId: String) {
        val existing = queries.selectPersonaByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        val seq = clock.nextSyncSeq()
        if (existing == null) {
            queries.insertPersonaFull(
                id = row.id, name = row.name, description = row.description,
                avatarData = row.avatarData, updatedAt = row.updatedAt, isDeleted = row.isDeleted,
                syncSeq = seq
            )
        } else {
            queries.upsertPersonaFull(
                name = row.name, description = row.description, avatarData = row.avatarData,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, syncSeq = seq, id = row.id
            )
        }
    }

    private fun apply(row: SyncSession, peerDeviceId: String) {
        val existing = queries.selectSessionByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        val seq = clock.nextSyncSeq()
        if (existing == null) {
            queries.insertSessionFull(
                id = row.id, characterId = row.characterId, personaId = row.personaId,
                title = row.title, lastTimestamp = row.lastTimestamp,
                currentMessageId = row.currentMessageId, parentSessionId = row.parentSessionId,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, syncSeq = seq
            )
        } else {
            queries.upsertSessionFull(
                characterId = row.characterId, personaId = row.personaId, title = row.title,
                lastTimestamp = row.lastTimestamp, currentMessageId = row.currentMessageId,
                parentSessionId = row.parentSessionId, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, syncSeq = seq, id = row.id
            )
        }
    }

    private fun apply(row: SyncMessage, peerDeviceId: String, refsJson: String?) {
        val existing = queries.selectMessageByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        val seq = clock.nextSyncSeq()

        if (existing == null) {
            queries.insertMessageFull(
                id = row.id, sessionId = row.sessionId, role = row.role, content = row.content,
                timestamp = row.timestamp, parentId = row.parentId, isActivePath = row.isActivePath,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, imageRefs = refsJson,
                reasoningText = row.reasoningText, costEstimate = row.costEstimate, syncSeq = seq
            )
            // Match local insert semantics: a newly-active message deactivates
            // its siblings so the timeline never shows two active branches.
            if (row.isActivePath == 1L && row.isDeleted == 0L) {
                queries.deactivateSiblings(updatedAt = row.updatedAt, syncSeq = clock.nextSyncSeq(), sessionId = row.sessionId, parentId = row.parentId, id = row.id)
            }
        } else {
            queries.upsertMessageFull(
                sessionId = row.sessionId, role = row.role, content = row.content,
                timestamp = row.timestamp, parentId = row.parentId, isActivePath = row.isActivePath,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, imageRefs = refsJson,
                reasoningText = row.reasoningText, costEstimate = row.costEstimate, syncSeq = seq, id = row.id
            )
            if (row.isActivePath == 1L && row.isDeleted == 0L && existing.isActivePath != 1L) {
                queries.deactivateSiblings(updatedAt = row.updatedAt, syncSeq = clock.nextSyncSeq(), sessionId = row.sessionId, parentId = row.parentId, id = row.id)
            }
        }
    }

    private fun apply(row: SyncApiConnection, peerDeviceId: String) {
        val existing = queries.selectApiConnectionByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        // The active flag is a per-device preference (like activePersonaId);
        // it never crosses the wire, so a sync cannot silently flip which
        // profile THIS device uses, and its deactivation cascade cannot
        // generate sync churn. Incoming rows therefore preserve the LOCAL
        // active state: an existing row keeps its own isActive value, and a
        // brand-new row mirrors the local auto-activation semantic (the first
        // connection on a device becomes active) instead of adopting anything
        // from the wire.
        val storedKey = effectiveApiKey(row.apiKey, existing?.apiKey)
        val seq = clock.nextSyncSeq()
        if (existing == null) {
            // Mirrors ApiSettingsRepository.insertApiConnection: with no
            // active connection, the incoming first one becomes active so the
            // device is not left without a profile. The deactivation cascade
            // (and a fresh local HLC stamp, since this is a local decision)
            // matches the local insert path exactly.
            val shouldActivate = queries.selectActiveApiConnection().executeAsOneOrNull() == null
            if (shouldActivate) {
                queries.setActiveApiConnection(updatedAt = clock.nextTimestamp(), syncSeq = clock.nextSyncSeq())
            }
            queries.insertApiConnectionFull(
                id = row.id, provider = row.provider, name = row.name, baseUrl = row.baseUrl,
                apiKey = storedKey, model = row.model, inferenceProvider = row.inferenceProvider,
                quantization = row.quantization,
                isActive = if (shouldActivate) 1L else 0L,
                isChatCompletion = row.isChatCompletion, lastUsed = if (shouldActivate) row.lastUsed ?: row.updatedAt else row.lastUsed,
                temperature = row.temperature, topP = row.topP, topK = row.topK,
                presencePenalty = row.presencePenalty, frequencyPenalty = row.frequencyPenalty,
                contextLimit = row.contextLimit, responseLimit = row.responseLimit,
                displayOrder = row.displayOrder, timeoutLimit = row.timeoutLimit,
                reasoningOverride = row.reasoningOverride, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, syncSeq = seq
            )
        } else {
            queries.upsertApiConnectionFull(
                provider = row.provider, name = row.name, baseUrl = row.baseUrl, apiKey = storedKey,
                model = row.model, inferenceProvider = row.inferenceProvider,
                quantization = row.quantization,
                isActive = existing.isActive, isChatCompletion = row.isChatCompletion,
                lastUsed = row.lastUsed, temperature = row.temperature, topP = row.topP,
                topK = row.topK, presencePenalty = row.presencePenalty,
                frequencyPenalty = row.frequencyPenalty, contextLimit = row.contextLimit,
                responseLimit = row.responseLimit, displayOrder = row.displayOrder,
                timeoutLimit = row.timeoutLimit, reasoningOverride = row.reasoningOverride,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, syncSeq = seq, id = row.id
            )
        }
    }

    /**
     * Maps an incoming wire-form key to this device's stored form.
     *
     * - Wire key null/blank (peer has no key, or withheld its undecryptable
     *   one): keep whatever this device already stored — a withheld key must
     *   not wipe a working local key, and a null key is never a deliberate
     *   "clear" (local updates keep the stored key when passed null).
     * - Wire key is portable plaintext: re-encrypt under the local backend.
     * - Wire key still marked (foreign encrypted blob from an older peer):
     *   unusable here; keep the existing key instead of clobbering it.
     */
    private fun effectiveApiKey(wireKey: String?, existingStored: String?): String? {
        val cipher = apiKeyCipher ?: return wireKey
        if (wireKey.isNullOrBlank()) return existingStored ?: wireKey
        return cipher.fromPortableForm(wireKey) ?: existingStored
    }

    private fun apply(row: SyncPromptBlock, peerDeviceId: String) {
        val existing = queries.selectPromptBlockByIdAny(row.id).executeAsOneOrNull()
        if (existing != null && !incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        val seq = clock.nextSyncSeq()
        if (existing == null) {
            queries.insertPromptBlockFull(
                id = row.id, name = row.name, template = row.template,
                isEnabled = row.isEnabled, isCustom = row.isCustom, displayOrder = row.displayOrder,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, syncSeq = seq
            )
        } else {
            queries.upsertPromptBlockFull(
                name = row.name, template = row.template, isEnabled = row.isEnabled,
                isCustom = row.isCustom, displayOrder = row.displayOrder, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, syncSeq = seq, id = row.id
            )
        }
    }
}

private fun currentTimeMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()

// ---------- Entity -> DTO mappers ----------

private fun CharacterEntity.toSync() = SyncCharacter(
    id = id, name = name, description = description, personality = personality ?: "",
    scenario = scenario ?: "", firstMes = firstMes, mesExample = mesExample,
    creatorNotes = creatorNotes, altGreetings = altGreetings, avatarData = avatarData,
    isAssistant = isAssistant, updatedAt = updatedAt, isDeleted = isDeleted,
    syncSeq = syncSeq,
    systemPrompt = systemPrompt, postHistoryInstructions = postHistoryInstructions,
    creator = creator, characterVersion = characterVersion, tags = tags,
    extensions = extensions, characterBook = characterBook
)

private fun PersonaEntity.toSync() = SyncPersona(
    id = id, name = name, description = description, avatarData = avatarData,
    updatedAt = updatedAt, isDeleted = isDeleted, syncSeq = syncSeq
)

private fun ChatSession.toSync() = SyncSession(
    id = id, characterId = characterId, personaId = personaId, title = title,
    lastTimestamp = lastTimestamp, currentMessageId = currentMessageId,
    parentSessionId = parentSessionId, updatedAt = updatedAt, isDeleted = isDeleted,
    syncSeq = syncSeq
)

private suspend fun MessageEntity.toSync(blobStore: BlobStore?): SyncMessage {
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

private fun ApiConnection.toSync(cipher: ApiKeyCipher?) = SyncApiConnection(
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

private fun PromptBlockEntity.toSync() = SyncPromptBlock(
    id = id, name = name, template = template, isEnabled = isEnabled,
    isCustom = isCustom, displayOrder = displayOrder, updatedAt = updatedAt,
    isDeleted = isDeleted, syncSeq = syncSeq
)

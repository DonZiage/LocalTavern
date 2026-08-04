package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageList
import chat.donzi.localtavern.utils.serializeImageList
import chat.donzi.localtavern.utils.serializeImageRefs
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import chat.donzi.localtavern.data.database.ConflictEvent
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
    private val blobStore: BlobStore? = null,
    // Pure-read dispatcher: delta collection, peer lookups and flows. Reads
    // never contend with the single write connection under WAL.
    private val readDispatcher: CoroutineDispatcher = ioDispatcher
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
        queries.selectAllSyncPeers().asFlow().mapToList(readDispatcher)

    // ---------- Conflict ledger (per-device, never synced) ----------

    /**
     * Unseen conflict events: rows where a newer version from a paired device
     * overwrote a local edit the peer had never seen (last-write-wins). The
     * UI shows these so the silent overwrite is at least surfaced; dismissing
     * them marks them seen.
     */
    fun observeUnseenConflicts(): Flow<List<ConflictEvent>> =
        queries.selectUnseenConflicts().asFlow().mapToList(readDispatcher)

    suspend fun markConflictSeen(ids: List<Long>) = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext
        queries.markConflictSeen(ids)
        pruneOldConflictsInternal()
    }

    suspend fun markAllConflictsSeen() = withContext(ioDispatcher) {
        queries.markAllConflictsSeen()
        pruneOldConflictsInternal()
    }

    /**
     * Drops seen events older than 30 days. Events are observational history;
     * once dismissed they serve no purpose, and a device that syncs a lot
     * would otherwise grow this table forever. Called on startup and after
     * every dismiss action.
     */
    suspend fun pruneOldConflicts() = withContext(ioDispatcher) {
        pruneOldConflictsInternal()
    }

    private fun pruneOldConflictsInternal() {
        queries.pruneSeenConflicts(currentTimeMillis() - CONFLICT_RETENTION_MS)
    }

    suspend fun getPeers(): List<SyncPeer> = withContext(readDispatcher) {
        queries.selectAllSyncPeers().executeAsList()
    }

    suspend fun getPeer(deviceId: String): SyncPeer? = withContext(readDispatcher) {
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

    /**
     * Updates a peer's cursors after an exchange.
     *
     * Cursors are announcements, not high-water marks: [receivedCursor] is
     * this device's own cursor into the peer's sequence space, and
     * [peerReceivedCursor] is the peer's announced cursor into THIS device's
     * space. Both are taken verbatim — a regression is not "corrected" here,
     * because the peer is authoritative about what it has consumed, and a
     * refused regression is exactly what starves a peer that restored from an
     * older backup (its re-stamped rows would sit below the frozen cursor and
     * never be forwarded). Stale cutoffs are safe: SyncExchange clamps every
     * cutoff against this device's actual sequence space (saneDeltaCutoff),
     * and a re-send is idempotent LWW.
     */
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

    /**
     * Makes a peer's delta cutoff sane against this device's sequence space.
     *
     * Cursors are stored as `maxSeq + 1` and sequences are strictly monotone
     * while the database lives, so a cutoff above `maxSeq + 1` cannot happen
     * in normal operation — it only appears when this device's sequence space
     * was rewound by restoring a database from an older backup. In that case
     * every row the peer already consumed has been replaced by older rows with
     * new low sequences, and the exact cutoff would silently skip them all
     * (rows would be stamped below the cursor forever, no matter their LWW
     * timestamps). Resetting the cutoff to 0 re-sends everything once; the
     * peer re-advances its cursor from the batch's max sequence and normal
     * exact-cut behavior resumes.
     */
    suspend fun saneDeltaCutoff(cursor: Long): Long = withContext(readDispatcher) {
        val maxSeq = queries.selectMaxSyncSeq().executeAsOne()
        if (cursor > maxSeq + 1) 0L else cursor
    }

    suspend fun deletePeer(deviceId: String) = withContext(ioDispatcher) {
        queries.deleteSyncPeer(updatedAt = currentTimeMillis(), deviceId = deviceId)
    }

    /** Invalidates every pairing (used when the device identity key rotates). */
    suspend fun clearAllPeers() = withContext(ioDispatcher) {
        queries.deleteAllSyncPeers(updatedAt = currentTimeMillis())
    }

    // ---------- Delta collection ----------

    /** All rows changed after [since] (including tombstones), in one batch. */
    suspend fun collectDelta(since: Long): SyncChanges = collectDeltaBatched(since, Long.MAX_VALUE).changes

    /**
     * All rows changed after [since] (including tombstones), cut to at most
     * [budgetBytes] of estimated wire bytes and ordered by sync sequence.
     *
     * The cut lands on a sequence boundary: rows that share a syncSeq (a
     * local write and a peer's applied row can collide under SQLite's
     * serialized stamping) form one atomic group and always ship together,
     * because the peer's cursor advances to maxSeq + 1 — splitting a group
     * would strand the leftover row below the cursor forever.
     *
     * The returned batch is sequence-closed: every local row with
     * syncSeq <= batch.changes.maxSyncSeq is in the batch, so the peer's
     * cursor (maxSeq + 1) is exact and the next batch resumes without gaps
     * or duplicates. [hasMore] is true when rows remain above the cut.
     * Batches are small enough that a large library (hundreds of characters
     * with avatars) never has to ride in one envelope: the receiver of such
     * an envelope would otherwise have to hold several times its size in
     * memory while decoding, which exhausts mobile heaps mid-exchange.
     */
    suspend fun collectDeltaBatched(since: Long, budgetBytes: Long = DELTA_BUDGET_BYTES): DeltaBatch = withContext(readDispatcher) {
        val characters = queries.selectCharacterDeltas(since).executeAsList()
        val personas = queries.selectPersonaDeltas(since).executeAsList()
        val sessions = queries.selectSessionDeltas(since).executeAsList()
        val messages = queries.selectMessageDeltas(since).executeAsList()
        val apiConnections = queries.selectApiConnectionDeltas(since).executeAsList()
        val promptBlocks = queries.selectPromptBlockDeltas(since).executeAsList()

        // Rank the union of all six tables by syncSeq (each table is already
        // ordered by syncSeq, see the delta queries) so the budget cut is
        // taken on the GLOBAL sequence order.
        val ranking = ArrayList<RankedRow>(
            characters.size + personas.size + sessions.size + messages.size + apiConnections.size + promptBlocks.size
        )
        characters.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        personas.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        sessions.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        messages.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        apiConnections.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        promptBlocks.forEach { ranking.add(RankedRow(it.syncSeq, it.estimatedWireSize())) }
        ranking.sortBy { it.seq }

        // Walk whole sequence groups: the first group always ships (it is the
        // atomic minimum), and a group is never split by the budget.
        var cutSeq = Long.MIN_VALUE
        var total = 0L
        var index = 0
        while (index < ranking.size) {
            val seq = ranking[index].seq
            var groupBytes = 0L
            while (index < ranking.size && ranking[index].seq == seq) {
                groupBytes += ranking[index].size
                index++
            }
            if (total > 0 && total + groupBytes > budgetBytes) break
            total += groupBytes
            cutSeq = seq
        }

        // Sequence-closed slice: every row at or below the cut. Only the
        // shipped rows are mapped to wire DTOs (the tail is left as raw rows).
        val shippedCharacters = characters.takeWhile { it.syncSeq <= cutSeq }
        val shippedPersonas = personas.takeWhile { it.syncSeq <= cutSeq }
        val shippedSessions = sessions.takeWhile { it.syncSeq <= cutSeq }
        val shippedMessages = messages.takeWhile { it.syncSeq <= cutSeq }
        val shippedApiConnections = apiConnections.takeWhile { it.syncSeq <= cutSeq }
        val shippedPromptBlocks = promptBlocks.takeWhile { it.syncSeq <= cutSeq }

        DeltaBatch(
            changes = SyncChanges(
                characters = shippedCharacters.map { it.toSync() },
                personas = shippedPersonas.map { it.toSync() },
                sessions = shippedSessions.map { it.toSync() },
                messages = shippedMessages.map { it.toSync() },
                apiConnections = shippedApiConnections.map { it.toSync(apiKeyCipher) },
                promptBlocks = shippedPromptBlocks.map { it.toSync() }
            ),
            hasMore = shippedCharacters.size < characters.size ||
                shippedPersonas.size < personas.size ||
                shippedSessions.size < sessions.size ||
                shippedMessages.size < messages.size ||
                shippedApiConnections.size < apiConnections.size ||
                shippedPromptBlocks.size < promptBlocks.size
        )
    }

    // ---------- Applying remote changes (LWW) ----------

    // Table names stored on ConflictEvent rows; shown to the user verbatim.
    private companion object {
        const val TABLE_CHARACTER = "Character"
        const val TABLE_PERSONA = "Persona"
        const val TABLE_SESSION = "Chat"
        const val TABLE_MESSAGE = "Message"
        const val TABLE_API_CONNECTION = "API connection"
        const val TABLE_PROMPT_BLOCK = "Prompt block"
        const val CONFLICT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

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
        // blob). Tombstoned rows are not persisted — their refs are dropped,
        // so a delete cannot trigger blob fetches for content that is gone.
        // Modern envelopes carry only content-addressed refs; legacy envelopes
        // (older peers) ship the images inline, which are hashed and stored
        // here. Refs from the wire are filtered to the canonical SHA-256 hex
        // shape: anything else (path traversal, garbage) is dropped rather
        // than stored or served.
        val preparedMessages = changes.messages.map { row ->
            val refs = when {
                row.isDeleted == 1L -> emptyList()
                row.imageRefs.isNotEmpty() -> row.imageRefs
                    .mapNotNull { ref ->
                        if (Hashing.isValidSha256Hex(ref.sha256)) chat.donzi.localtavern.domain.ImageRef(ref.sha256, ref.size) else null
                    }
                row.imageData != null -> persistLegacyImages(row.imageData)
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

    // (syncSeq, estimated wire bytes) of one pending row, used to rank the
    // delta union for the batch cut.
    private data class RankedRow(val seq: Long, val size: Long)

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

    /**
     * Ledger + conflict-event recording for one incoming row whose local copy
     * exists.
     *
     * The peer's announcement advances the per-peer ledger REGARDLESS of
     * whether the row wins or loses LWW: the ledger tracks the newest version
     * that peer has ever announced, which is the baseline "has the peer seen
     * this row's state" must be compared against.
     *
     * An event is recorded only when a STRICTLY newer incoming version
     * replaces a local version that the peer had never announced — i.e. the
     * local row was edited after the peer's knowledge, and that edit is now
     * discarded by last-write-wins. Equal stamps are the peer echoing this
     * device's own version back (applied rows keep the author's updatedAt), so
     * they never record; an incoming version that only converges with what
     * the peer already knew never records either.
     */
    private fun noteIncoming(rowId: String, tableName: String, peerDeviceId: String, localUpdatedAt: Long, incomingUpdatedAt: Long) {
        val previous = queries.selectAppliedRow(rowId, peerDeviceId).executeAsOneOrNull()?.appliedUpdatedAt ?: 0L
        if (incomingUpdatedAt > localUpdatedAt && localUpdatedAt > previous) {
            queries.insertConflictEventIgnore(
                rowId = rowId, tableName = tableName, peerDeviceId = peerDeviceId,
                localUpdatedAt = localUpdatedAt, incomingUpdatedAt = incomingUpdatedAt,
                createdAt = currentTimeMillis()
            )
        }
        queries.insertAppliedRow(rowId, peerDeviceId, incomingUpdatedAt)
        queries.updateAppliedRowMax(incomingUpdatedAt, rowId, peerDeviceId)
    }

    private fun apply(row: SyncCharacter, peerDeviceId: String) {
        val existing = queries.selectCharacterByIdAny(row.id).executeAsOneOrNull()
        if (existing != null) {
            noteIncoming(row.id, TABLE_CHARACTER, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
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
        if (existing != null) {
            noteIncoming(row.id, TABLE_PERSONA, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
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
        if (existing != null) {
            noteIncoming(row.id, TABLE_SESSION, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
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
        if (existing != null) {
            noteIncoming(row.id, TABLE_MESSAGE, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
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
        if (existing != null) {
            noteIncoming(row.id, TABLE_API_CONNECTION, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
        // The active flag is a per-device preference (like activePersonaId);
        // it never crosses the wire, so a sync cannot silently flip which
        // profile THIS device uses, and its deactivation cascade cannot
        // generate sync churn. Incoming rows therefore preserve the LOCAL
        // active state: an existing row keeps its own isActive value, and a
        // brand-new row mirrors the local auto-activation semantic (the first
        // connection on a device becomes active) instead of adopting anything
        // from the wire.
        val storedKey = effectiveApiKey(row.apiKey, existing?.apiKey)
        // Peers on older builds only send isChatCompletion; bridge their
        // explicit legacy choice into the mode, auto otherwise.
        val chatCompletionMode = if (row.chatCompletionMode != 0L) {
            row.chatCompletionMode
        } else if (row.isChatCompletion == 0L) {
            2L
        } else {
            0L
        }
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
                reasoningOverride = row.reasoningOverride, chatCompletionMode = chatCompletionMode, updatedAt = row.updatedAt,
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
                chatCompletionMode = chatCompletionMode,
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
        if (existing != null) {
            noteIncoming(row.id, TABLE_PROMPT_BLOCK, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
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

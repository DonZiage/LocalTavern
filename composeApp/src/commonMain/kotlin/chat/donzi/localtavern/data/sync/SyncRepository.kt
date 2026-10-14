package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageList
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageList
import chat.donzi.localtavern.utils.serializeImageRefs
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import chat.donzi.localtavern.data.database.ConflictEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

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

    // (syncSeq, estimated wire bytes) of one sequence group, as computed by
    // the SQL delta-group queries (see LocalTavernDB.sq).
    private data class GroupSize(val seq: Long, val size: Long)

    /**
     * All rows changed after [since] (including tombstones), cut to at most
     * [budgetBytes] of estimated wire bytes and ordered by sync sequence.
     *
     * The cut is decided on SQL-computed (sequence, estimated size) groups
     * BEFORE any row is materialized: only the rows that actually ship are
     * fetched. Draining a huge backlog therefore does bounded work per round
     * no matter how large the library is (the previous implementation
     * re-materialized every pending row of all six tables on every round).
     *
     * The cut lands on a sequence boundary: rows that share a syncSeq (a local
     * write and a peer's applied row can collide under SQLite's serialized
     * stamping) form one atomic group and always ship together, because the
     * peer's cursor advances to maxSeq + 1 — splitting a group would strand
     * the leftover row below the cursor forever.
     *
     * The returned batch is sequence-closed: every row with
     * syncSeq <= batch.changes.maxSyncSeq is in the batch, so the peer's
     * cursor (maxSeq + 1) is exact and the next batch resumes without gaps or
     * duplicates. [hasMore] is true when rows remain above the cut.
     *
     * A single group larger than MAX_SAFE_GROUP_PLAINTEXT_BYTES cannot fit any
     * envelope: the peer's server rejects oversized bodies with 413, which
     * would cut the sync and freeze the cursor forever (the pre-fix size-limit
     * failure). Such a group is never shipped: the batch comes back empty with
     * [hasMore] = true and the exchange reports a clear error instead.
     */
    suspend fun collectDeltaBatched(since: Long, budgetBytes: Long = DELTA_BUDGET_BYTES): DeltaBatch = withContext(readDispatcher) {
        // Group sizes come straight from SQL (no row materialization). The
        // estimates mirror what the rows will put on the wire, including the
        // avatar threshold (big avatars ship as small refs, see
        // SyncModels.avatarRefForTransport), so the cut below is byte-accurate
        // before any row is fetched.
        val groups = ArrayList<GroupSize>()
        queries.selectCharacterDeltaGroups(ROW_OVERHEAD, AVATAR_REF_THRESHOLD_BYTES, AVATAR_REF_WIRE_ESTIMATE, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        queries.selectPersonaDeltaGroups(ROW_OVERHEAD, AVATAR_REF_THRESHOLD_BYTES, AVATAR_REF_WIRE_ESTIMATE, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        queries.selectSessionDeltaGroups(ROW_OVERHEAD, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        queries.selectMessageDeltaGroups(ROW_OVERHEAD, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        queries.selectApiConnectionDeltaGroups(ROW_OVERHEAD, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        queries.selectPromptBlockDeltaGroups(ROW_OVERHEAD, since).executeAsList()
            .forEach { groups.add(GroupSize(it.seq, it.size)) }
        groups.sortBy { it.seq }

        // Walk whole sequence groups: the first group always ships (it is the
        // atomic minimum) unless it cannot fit any envelope at all, and a
        // group is never split by the budget.
        var cutSeq: Long? = null
        var total = 0L
        var index = 0
        while (index < groups.size) {
            val seq = groups[index].seq
            var groupBytes = 0L
            while (index < groups.size && groups[index].seq == seq) {
                groupBytes += groups[index].size
                index++
            }
            if (groupBytes > MAX_SAFE_GROUP_PLAINTEXT_BYTES) break
            if (total > 0 && total + groupBytes > budgetBytes) break
            total += groupBytes
            cutSeq = seq
        }
        if (cutSeq == null) {
            // Nothing pending, or the first group cannot ride any envelope:
            // the batch must stay empty (an oversized envelope would be
            // rejected with 413 and freeze the cursor), and hasMore tells the
            // exchange to fail with a clear error instead of looping.
            return@withContext DeltaBatch(changes = SyncChanges(), hasMore = groups.isNotEmpty())
        }

        // Sequence-closed slice: every row at or below the cut. Only the
        // shipped rows are fetched and mapped to wire DTOs (the tail is never
        // materialized).
        val shippedCharacters = queries.selectCharacterDeltaRows(since, cutSeq).executeAsList()
        val shippedPersonas = queries.selectPersonaDeltaRows(since, cutSeq).executeAsList()
        val shippedSessions = queries.selectSessionDeltaRows(since, cutSeq).executeAsList()
        val shippedMessages = queries.selectMessageDeltaRows(since, cutSeq).executeAsList()
        val shippedApiConnections = queries.selectApiConnectionDeltaRows(since, cutSeq).executeAsList()
        val shippedPromptBlocks = queries.selectPromptBlockDeltaRows(since, cutSeq).executeAsList()

        DeltaBatch(
            changes = SyncChanges(
                characters = shippedCharacters.map { it.toSync(blobStore) },
                personas = shippedPersonas.map { it.toSync(blobStore) },
                sessions = shippedSessions.map { it.toSync() },
                messages = shippedMessages.map { it.toSync() },
                apiConnections = shippedApiConnections.map { it.toSync(apiKeyCipher) },
                promptBlocks = shippedPromptBlocks.map { it.toSync() }
            ),
            hasMore = groups.any { it.seq > cutSeq }
        )
    }

    // ---------- Out-of-band blob resolution ----------

    /**
     * Every avatar ref this device is still waiting for, bounded to [limit]
     * rows: the exchange re-fetches these against the current peer on every
     * sync round, so a missed avatar heals on the next sync instead of being
     * lost forever. Bounded per round (with the remainder healing on later
     * rounds as the missing set shrinks) so a huge backlog cannot stall one
     * round for hours or pin every ref list on a mobile heap.
     */
    suspend fun getMissingAvatarRefs(limit: Long = MAX_MISSING_REFS_PER_ROUND): List<SyncImageRef> = withContext(readDispatcher) {
        val refs = ArrayList<SyncImageRef>()
        queries.selectCharactersWithMissingAvatarCapped(limit).executeAsList().forEach { row ->
            deserializeImageRefs(row.avatarRef).forEach { refs.add(SyncImageRef(it.sha256, it.size)) }
        }
        queries.selectPersonasWithMissingAvatarCapped(limit).executeAsList().forEach { row ->
            deserializeImageRefs(row.avatarRef).forEach { refs.add(SyncImageRef(it.sha256, it.size)) }
        }
        if (refs.size > limit) refs.subList(0, limit.toInt()) else refs
    }

    /**
     * Every message-image ref carried by a live row, bounded to [limit] rows
     * for the same retry path (see getMissingAvatarRefs): blobs that failed
     * to arrive are re-attempted against the current peer on every sync round
     * (fetchMissingBlobs skips refs already in the store).
     */
    suspend fun getMissingImageRefs(limit: Long = MAX_MISSING_REFS_PER_ROUND): List<SyncImageRef> = withContext(readDispatcher) {
        val refs = ArrayList<SyncImageRef>()
        queries.selectLiveImageRefsCapped(limit).executeAsList().forEach { refsJson ->
            deserializeImageRefs(refsJson).forEach { refs.add(SyncImageRef(it.sha256, it.size)) }
        }
        refs
    }

    /**
     * Restores the avatar bytes of rows whose blob has arrived in [blobStore]:
     * the row's avatar is filled and its ref cleared. Only rows that STILL
     * carry the same ref, still have no bytes and are not tombstoned are
     * touched, so a newer local avatar or a newer ref is never overwritten.
     * Returns the number of filled rows.
     *
     * Rows are resolved and committed in bounded batches (see
     * FILL_TX_CHUNK_ROWS/FILL_TX_CHUNK_BYTES): a large library can wait on
     * hundreds of avatars at once, and resolving every blob before the first
     * write would pin the whole library's bytes on a mobile heap on top of
     * the sync envelope decode — an OOM kill mid-sync. Each batch commits on
     * its own, so a kill mid-fill only loses the uncommitted tail and the
     * next sync resumes the remainder.
     */
    suspend fun fillMissingAvatars(blobStore: BlobStore?): Int = withContext(ioDispatcher) {
        if (blobStore == null) return@withContext 0
        // Work items are (table, id, expected ref JSON): id + ref only, never
        // bytes. The missing-set queries below return just those two columns,
        // so snapshotting the work list itself stays small whatever N is.
        val work = ArrayList<AvatarFillWork>()
        queries.selectCharactersWithMissingAvatar().executeAsList().forEach { row ->
            work.add(AvatarFillWork(isPersona = false, id = row.id, refJson = row.avatarRef))
        }
        queries.selectPersonasWithMissingAvatar().executeAsList().forEach { row ->
            work.add(AvatarFillWork(isPersona = true, id = row.id, refJson = row.avatarRef))
        }
        var filled = 0
        var index = 0
        while (index < work.size) {
            // Cancellation checkpoint + cooperative yield between commits (see
            // applyChanges): a big fill must answer a cancelled sync promptly
            // instead of draining the whole queue after the UI gave up.
            if (index > 0) yield() else ensureActive()
            // Resolve ONE bounded batch: blob reads (file I/O) interleave
            // with the commit below, so at most one batch of avatar bytes is
            // ever held at once. Reads stay outside the transaction; the
            // update re-checks the expected ref, so a row edited concurrently
            // is never overwritten.
            val batch = ArrayList<ResolvedAvatarFill>()
            var batchBytes = 0L
            while (index < work.size && batch.size < FILL_TX_CHUNK_ROWS && batchBytes < FILL_TX_CHUNK_BYTES) {
                val item = work[index++]
                val ref = deserializeImageRefs(item.refJson).firstOrNull() ?: continue
                // Keys from the wire are canonical SHA-256 hex before any file
                // access (the blob store resolves keys as file names).
                if (!Hashing.isValidSha256Hex(ref.sha256)) continue
                val bytes = blobStore.read(ref.sha256) ?: continue
                batch.add(ResolvedAvatarFill(item = item, bytes = bytes))
                batchBytes += bytes.size.toLong()
            }
            if (batch.isEmpty()) continue
            database.transaction {
                batch.forEach { resolved ->
                    val updated = if (resolved.item.isPersona) {
                        queries.updatePersonaAvatarData(resolved.bytes, resolved.item.id, resolved.item.refJson).value
                    } else {
                        queries.updateCharacterAvatarData(resolved.bytes, resolved.item.id, resolved.item.refJson).value
                    }
                    if (updated > 0L) filled++
                }
            }
        }
        filled
    }

    // One row waiting on its out-of-band avatar (id + expected ref JSON only).
    private data class AvatarFillWork(val isPersona: Boolean, val id: String, val refJson: String?)

    // One resolved batch entry: the row plus its blob bytes, held only until
    // its batch commits.
    private data class ResolvedAvatarFill(val item: AvatarFillWork, val bytes: ByteArray)

    // ---------- Applying remote changes (LWW) ----------

    // Table names stored on ConflictEvent rows; shown to the user verbatim.
    private companion object {
        // Rows committed per sub-transaction in applyChanges: bounds how long
        // one commit holds the single write connection (200 small rows commit
        // in milliseconds, whatever the total batch size).
        const val APPLY_TX_CHUNK_ROWS = 200
        // Rows resolved per sub-transaction in fillMissingAvatars, cut by
        // count AND by resolved avatar bytes: 20 cards with multi-MB avatars
        // must flush long before the count is reached, or resolving a heavy
        // library would pin its whole bytes on a mobile heap (OOM kill
        // mid-sync). A single avatar larger than the byte budget still
        // commits alone (progress is guaranteed).
        const val FILL_TX_CHUNK_ROWS = 20
        const val FILL_TX_CHUNK_BYTES = 8L * 1024 * 1024
        // Missing refs re-attempted per sync round (see getMissingAvatarRefs /
        // getMissingImageRefs): bounded work per round, full coverage across
        // rounds as healed refs leave the missing set.
        const val MAX_MISSING_REFS_PER_ROUND = 100L
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
     * Rows that carry an out-of-band avatar ref get their bytes from
     * [avatarResolver] (preferred, read per committed chunk so at most one
     * chunk of avatar bytes is ever held) or from [resolvedAvatars] (a
     * pre-resolved map, kept for callers that already hold the bytes): the
     * exchange fetches them from the sender through /blob/fetch BEFORE
     * applying (see SyncExchange), so the row lands in the database with its
     * avatar already materialized. A ref the sender could not serve resolves
     * to null and the row lands without an avatar rather than blocking the
     * sync.
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
     *
     * Crash safety: the batch is committed in small per-chunk transactions
     * (see APPLY_TX_CHUNK_ROWS). A kill mid-batch only loses the uncommitted
     * tail — SQLite rolls it back — and the caller does NOT advance its
     * cursor on failure, so the next sync re-sends from the un-advanced
     * cursor and re-applying is idempotent LWW: no row is ever lost,
     * duplicated or half-written. Either every row of the envelope lands or
     * the caller treats the batch as failed; there is no partial-commit
     * state the cursor can skip over.
     *
     * [onProgress] reports (appliedRows, totalRows) after every committed
     * chunk so the sync UI can render a determinate progress bar even for
     * very large libraries.
     */
    suspend fun applyChanges(
        changes: SyncChanges,
        peerDeviceId: String,
        resolvedAvatars: Map<String, ByteArray?> = emptyMap(),
        // Reads one avatar blob by its SHA-256 hex key. Resolved per committed
        // chunk (see below) instead of once for the whole envelope: a
        // ref-heavy envelope packs thousands of refs into one batch (each is
        // only ~150 wire bytes), and resolving them all up front would pin
        // the whole library's bytes on a mobile heap on top of the envelope
        // decode — an OOM kill mid-sync whose retry (including auto-sync at
        // launch) crashes identically while the file stays valid.
        avatarResolver: (suspend (String) -> ByteArray?)? = null,
        onProgress: (appliedRows: Int, totalRows: Int) -> Unit = { _, _ -> }
    ) = withContext(ioDispatcher) {
        // Refuse absurd envelopes BEFORE any row is materialized: a hostile,
        // buggy or legacy peer must never force this device to queue hundreds
        // of thousands of statements or pin an unbounded batch in memory.
        // Nothing is written before this point, so a rejection leaves zero
        // rows behind and the cursor never advances over it.
        checkEnvelopeFits(changes)
        val totalRows = changes.totalRowCount
        if (totalRows == 0) {
            clock.absorb(changes.maxUpdatedAt)
            return@withContext
        }
        // Message image blobs are written to the store BEFORE the row touches
        // the database (crash-safe ordering: a row never references a missing
        // blob). Tombstoned rows are not persisted — their refs are dropped,
        // so a delete cannot trigger blob fetches for content that is gone.
        // Modern envelopes carry only content-addressed refs; legacy envelopes
        // (older peers) ship the images inline, which are hashed and stored
        // here. Refs from the wire are filtered to the canonical SHA-256 hex
        // shape: anything else (path traversal, garbage) is dropped rather
        // than stored or served.
        //
        // Prepared in per-chunk slices (not one upfront map): a single envelope
        // batch can carry thousands of messages, and materializing every row's
        // refs plus its blob-store reads up front pins the whole batch in
        // memory at once — exactly how a big sync exhausts a mobile heap.
        val messageChunks = changes.messages.chunked(APPLY_TX_CHUNK_ROWS)
        // The out-of-band avatar fetch is asynchronous: an incoming winning
        // row whose ref bytes have not landed yet (neither the resolver nor
        // [resolvedAvatars] has them) must NOT destroy the avatar bytes this
        // device already holds — a slow or failed fetch would otherwise wipe
        // every character avatar the receiving device had, for the whole
        // library at once. When the existing bytes hash to the wire ref they
        // ARE the referenced content (SHA-256 verified), so they are kept and
        // the ref is redundant. Resolved per committed chunk below (not one
        // upfront map): preloading the whole envelope's existing avatars
        // would pin the library's bytes exactly like preloading the incoming
        // ones. Hashing is CPU work, not a statement, and the reads stay
        // outside the transaction.
        // A single envelope batch can carry thousands of small rows within
        // its byte budget; committing them all in one SQLite transaction
        // would hold the single write connection for seconds on a mobile
        // device (ANR, watchdog kill) and pin the whole batch in memory.
        // Each chunk commits on its own: a crash mid-batch only loses the
        // uncommitted tail, and the next sync re-sends from the un-advanced
        // cursor — re-applying is idempotent LWW, so no row is ever lost or
        // duplicated. The clock absorbs the batch's timestamps in every
        // chunk (absorb only moves forward), so the skew protection holds
        // even if the process dies mid-batch.
        val characterChunks = changes.characters.chunked(APPLY_TX_CHUNK_ROWS)
        val personaChunks = changes.personas.chunked(APPLY_TX_CHUNK_ROWS)
        val sessionChunks = changes.sessions.chunked(APPLY_TX_CHUNK_ROWS)
        val apiChunks = changes.apiConnections.chunked(APPLY_TX_CHUNK_ROWS)
        val promptChunks = changes.promptBlocks.chunked(APPLY_TX_CHUNK_ROWS)
        val rounds = maxOf(
            characterChunks.size, personaChunks.size, sessionChunks.size,
            messageChunks.size, apiChunks.size, promptChunks.size
        )
        var appliedRows = 0
        repeat(rounds) { index ->
            // Cancellation checkpoint + cooperative yield between commits: a
            // big batch must neither ignore a user cancel nor starve every
            // other queued write (chat sends, settings) for its whole run.
            // yield() is cancellable, so a cancelled sync aborts here with
            // the cursor un-advanced — resume-safe by construction.
            if (index > 0) yield() else ensureActive()
            // Message blobs for THIS chunk only: bounded I/O per commit, and
            // a failure here aborts before the chunk's transaction opens —
            // the rows of earlier chunks stay committed, this chunk stays
            // unapplied, and the un-advanced cursor re-sends it next time.
            val preparedMessages = messageChunks.getOrNull(index)?.map { row ->
                PreparedMessage(row, refsJsonFor(row))
            }.orEmpty()
            // Avatar bytes for THIS chunk only (same bound as the message
            // blobs above): at most one chunk of avatar bytes is ever held,
            // and each chunk commits on its own, so a kill mid-batch keeps
            // its prefix and the next sync resumes the remainder. Reads stay
            // outside the transaction (blob reads are file I/O); the updates
            // re-check the expected ref, so a row edited concurrently is
            // never overwritten.
            val roundCharacters = characterChunks.getOrNull(index).orEmpty()
            val roundPersonas = personaChunks.getOrNull(index).orEmpty()
            val roundResolved = HashMap<String, ByteArray>()
            (roundCharacters.mapNotNull { it.avatarRef } + roundPersonas.mapNotNull { it.avatarRef })
                .distinctBy { it.sha256 }
                .forEach { ref ->
                    val bytes = resolvedAvatars[ref.sha256] ?: avatarResolver?.invoke(ref.sha256)
                    if (bytes != null) roundResolved[ref.sha256] = bytes
                }
            val roundMatching = HashMap<String, ByteArray>()
            roundCharacters.forEach { row ->
                if (row.isDeleted != 1L && row.avatarRef != null && roundResolved[row.avatarRef.sha256] == null) {
                    val bytes = queries.selectCharacterByIdAny(row.id).executeAsOneOrNull()?.avatarData
                    if (bytes != null && Hashing.sha256Hex(bytes) == row.avatarRef.sha256) {
                        roundMatching[row.id] = bytes
                    }
                }
            }
            roundPersonas.forEach { row ->
                if (row.isDeleted != 1L && row.avatarRef != null && roundResolved[row.avatarRef.sha256] == null) {
                    val bytes = queries.selectPersonaByIdAny(row.id).executeAsOneOrNull()?.avatarData
                    if (bytes != null && Hashing.sha256Hex(bytes) == row.avatarRef.sha256) {
                        roundMatching[row.id] = bytes
                    }
                }
            }
            database.transaction {
                roundCharacters.forEach { row -> apply(row, peerDeviceId, roundResolved, roundMatching[row.id]) }
                roundPersonas.forEach { row -> apply(row, peerDeviceId, roundResolved, roundMatching[row.id]) }
                sessionChunks.getOrNull(index)?.forEach { row -> apply(row, peerDeviceId) }
                preparedMessages.forEach { apply(it.row, peerDeviceId, it.refsJson) }
                apiChunks.getOrNull(index)?.forEach { row -> apply(row, peerDeviceId) }
                promptChunks.getOrNull(index)?.forEach { row -> apply(row, peerDeviceId) }
                clock.absorb(changes.maxUpdatedAt)
            }
            appliedRows += (characterChunks.getOrNull(index)?.size ?: 0) +
                (personaChunks.getOrNull(index)?.size ?: 0) +
                (sessionChunks.getOrNull(index)?.size ?: 0) +
                preparedMessages.size +
                (apiChunks.getOrNull(index)?.size ?: 0) +
                (promptChunks.getOrNull(index)?.size ?: 0)
            onProgress(appliedRows, totalRows)
        }
    }

    // Serializes one message row's image references for storage: wire refs are
    // filtered to canonical SHA-256 hex, legacy inline bytes are hashed into
    // the blob store, tombstones carry nothing. Blob writes happen before the
    // row's transaction opens (crash-safe ordering).
    private suspend fun refsJsonFor(row: SyncMessage): String? {
        val refs = when {
            row.isDeleted == 1L -> emptyList()
            row.imageRefs.isNotEmpty() -> row.imageRefs
                .mapNotNull { ref ->
                    if (Hashing.isValidSha256Hex(ref.sha256)) chat.donzi.localtavern.domain.ImageRef(ref.sha256, ref.size) else null
                }
            row.imageData != null -> persistLegacyImages(row.imageData)
            else -> emptyList()
        }
        return serializeImageRefs(refs)
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

    private fun apply(row: SyncCharacter, peerDeviceId: String, resolvedAvatars: Map<String, ByteArray?>, matchingBytes: ByteArray?) {
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
        // Out-of-band avatars arrive pre-fetched by the exchange. A ref the
        // sender could not serve (or a fetch that failed) leaves the row
        // WITHOUT bytes but WITH the ref stored on the row, so a later sync
        // re-fetches and fills it (see getMissingAvatarRefs / fillMissingAvatars).
        // Tombstones carry no avatar state at all. [matchingBytes] are the
        // bytes this device already held for the same content (hash-verified
        // against the ref in applyChanges): they are kept so an unresolved ref
        // never discards an avatar this device already has.
        val resolved = row.avatarRef?.let { resolvedAvatars[it.sha256] }
        val avatarData = if (row.isDeleted == 1L) null else (resolved ?: matchingBytes ?: row.avatarData)
        // Refs from the wire are filtered to the canonical SHA-256 hex shape
        // before anything is stored: the column is later used for blob-store
        // file reads (see fillMissingAvatars), and an unvalidated key is a
        // path-traversal primitive.
        val avatarRef = if (row.isDeleted == 1L || avatarData != null || row.avatarRef == null ||
            !Hashing.isValidSha256Hex(row.avatarRef.sha256)
        ) {
            null
        } else {
            serializeImageRefs(listOf(chat.donzi.localtavern.domain.ImageRef(row.avatarRef.sha256, row.avatarRef.size)))
        }
        if (existing == null) {
            queries.insertCharacterFull(
                id = row.id, name = row.name, description = row.description,
                personality = row.personality, scenario = row.scenario, firstMes = row.firstMes,
                mesExample = row.mesExample, creatorNotes = row.creatorNotes, altGreetings = row.altGreetings,
                avatarData = avatarData, avatarRef = avatarRef, isAssistant = row.isAssistant,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, systemPrompt = row.systemPrompt,
                postHistoryInstructions = row.postHistoryInstructions, creator = row.creator,
                characterVersion = row.characterVersion, tags = row.tags, extensions = row.extensions,
                characterBook = row.characterBook, syncSeq = seq
            )
        } else {
            queries.upsertCharacterFull(
                name = row.name, description = row.description, personality = row.personality,
                scenario = row.scenario, firstMes = row.firstMes, mesExample = row.mesExample,
                creatorNotes = row.creatorNotes, altGreetings = row.altGreetings,
                avatarData = avatarData, avatarRef = avatarRef, isAssistant = row.isAssistant,
                updatedAt = row.updatedAt, isDeleted = row.isDeleted, systemPrompt = row.systemPrompt,
                postHistoryInstructions = row.postHistoryInstructions, creator = row.creator,
                characterVersion = row.characterVersion, tags = row.tags, extensions = row.extensions,
                characterBook = row.characterBook, syncSeq = seq, id = row.id
            )
        }
    }

    private fun apply(row: SyncPersona, peerDeviceId: String, resolvedAvatars: Map<String, ByteArray?>, matchingBytes: ByteArray?) {
        val existing = queries.selectPersonaByIdAny(row.id).executeAsOneOrNull()
        if (existing != null) {
            noteIncoming(row.id, TABLE_PERSONA, peerDeviceId, existing.updatedAt, row.updatedAt)
            if (!incomingWins(existing.updatedAt, row.updatedAt, peerDeviceId)) return
        } else {
            queries.insertAppliedRow(row.id, peerDeviceId, row.updatedAt)
        }
        val seq = clock.nextSyncSeq()
        // See the character apply: hash-verified existing bytes are kept over
        // an unresolved ref so an async fetch never discards an avatar this
        // device already has.
        val resolved = row.avatarRef?.let { resolvedAvatars[it.sha256] }
        val avatarData = if (row.isDeleted == 1L) null else (resolved ?: matchingBytes ?: row.avatarData)
        // Wire refs are shape-validated before storing (see the character
        // apply): the column feeds blob-store file reads.
        val avatarRef = if (row.isDeleted == 1L || avatarData != null || row.avatarRef == null ||
            !Hashing.isValidSha256Hex(row.avatarRef.sha256)
        ) {
            null
        } else {
            serializeImageRefs(listOf(chat.donzi.localtavern.domain.ImageRef(row.avatarRef.sha256, row.avatarRef.size)))
        }
        if (existing == null) {
            queries.insertPersonaFull(
                id = row.id, name = row.name, description = row.description,
                avatarData = avatarData, avatarRef = avatarRef, updatedAt = row.updatedAt,
                isDeleted = row.isDeleted, syncSeq = seq
            )
        } else {
            queries.upsertPersonaFull(
                name = row.name, description = row.description, avatarData = avatarData,
                avatarRef = avatarRef, updatedAt = row.updatedAt, isDeleted = row.isDeleted,
                syncSeq = seq, id = row.id
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

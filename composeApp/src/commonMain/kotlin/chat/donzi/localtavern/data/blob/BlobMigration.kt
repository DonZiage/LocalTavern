package chat.donzi.localtavern.data.blob

import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.sync.AVATAR_REF_THRESHOLD_BYTES
import chat.donzi.localtavern.domain.ImageRef
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageList
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageRefs

/**
 * Offloads legacy inline message images (the imageData BLOB column, retained
 * in schema v10 for this transitional release) into the content-addressed
 * blob store, replacing the bytes on each row with imageRefs.
 *
 * Runs in bounded batches so memory stays flat, is idempotent (rows are
 * processed until none still carry imageData), and writes blobs before the
 * row is updated so a crash never leaves a row referencing a missing blob.
 * Returns the number of migrated rows; a nonzero return means the caller may
 * want to VACUUM the database to reclaim the freed pages.
 */
suspend fun migrateMessageImagesToBlobStore(
    database: LocalTavernDB,
    blobStore: BlobStore,
    clock: LogicalClock
): Int {
    val queries = database.localTavernDBQueries
    var migrated = 0
    var offset = 0L
    while (true) {
        // ID-only page: the IDs alone can never overflow the ~2 MB Android
        // CursorWindow, no matter how large the legacy imageData blobs are.
        // The legacy unbounded SELECT * materialized every inline image at
        // once and crashed startup with "Row too big to fit into
        // CursorWindow" before the unlock gate could open.
        val ids = queries.selectMessageIdsWithImagesPaged(MIGRATION_PAGE_SIZE, offset).executeAsList()
        if (ids.isEmpty()) break
        val prepared = ArrayList<Pair<String, String?>>()
        ids.forEach { id ->
            // One row's blob at a time, reassembled in CursorWindow-sized
            // chunks when large; a row that still fails to read is skipped
            // (its ID stays for the next launch) instead of aborting the
            // whole migration and blocking startup.
            val blob = readMessageImageBlob(queries, id) ?: return@forEach
            val images = deserializeImageList(blob)
            val refs = images.map { img ->
                val hash = Hashing.sha256Hex(img)
                if (blobStore.read(hash) == null) blobStore.write(hash, img)
                ImageRef(hash, img.size.toLong())
            }
            prepared.add(id to serializeImageRefs(refs))
        }
        // IDs are consumed from the head: rows migrated in this round leave
        // the pending set, so the next page re-reads from offset 0. Rows
        // skipped above keep their imageData and are retried next launch
        // without blocking the rows behind them.
        if (prepared.isNotEmpty()) {
            database.transaction {
                prepared.forEach { (id, refsJson) ->
                    queries.updateMessageImageRefs(
                        imageRefs = refsJson,
                        updatedAt = clock.nextTimestamp(),
                        syncSeq = clock.nextSyncSeq(),
                        id = id
                    )
                }
            }
            migrated += prepared.size
        } else {
            // Nothing on this page could be read (poison rows only): step
            // past it so migration terminates instead of spinning.
            offset += ids.size
        }
        if (ids.size < MIGRATION_PAGE_SIZE) break
    }
    return migrated
}

// Reads one legacy imageData blob without ever materializing an oversized
// row: small blobs take the fast path, large ones are reassembled from
// substr() chunks. Null when the row vanished or is unreadable.
private fun readMessageImageBlob(
    queries: chat.donzi.localtavern.data.database.LocalTavernDBQueries,
    id: String
): ByteArray? = runCatching {
    val length = queries.selectMessageImageDataLength(id).executeAsOneOrNull()?.LENGTH
    if (length == null) {
        return@runCatching queries.selectMessageImageDataById(id).executeAsOneOrNull()?.imageData
    }
    if (length <= 0L) return@runCatching null
    if (length <= BLOB_CHUNK_BYTES) {
        return@runCatching queries.selectMessageImageDataById(id).executeAsOneOrNull()?.imageData
    }
    val out = ByteArray(length.toInt())
    var offset = 1L
    var dest = 0
    while (offset <= length) {
        val size = minOf(BLOB_CHUNK_BYTES, length - offset + 1)
        // substr() bind params are TEXT-typed by SQLDelight; SQLite coerces
        // the numeric strings (substr affinity conversion).
        val chunk = queries.selectMessageImageDataChunk(offset.toString(), size.toString(), id)
            .executeAsOneOrNull()?.substr
            ?: return@runCatching if (dest == 0) null else out.copyOf(dest)
        chunk.copyInto(out, dest)
        dest += chunk.size
        offset += chunk.size
        if (chunk.size < size && offset <= length) break
    }
    if (dest == out.size) out else out.copyOf(dest)
}.getOrNull()

/** Deletes blob-store files not referenced by any live (non-tombstoned) row. */
suspend fun runBlobGc(database: LocalTavernDB, blobStore: BlobStore) {
    val usedKeys = buildSet {
        // Every scan below pages through its table (see the *Paged queries):
        // each page is hashed and released before the next is read, so the
        // collection never holds more than one page of blobs or ref JSON —
        // the unpaged variants would pin a heavy library on the heap on every
        // startup, before the first screen renders.
        var offset = 0L
        while (true) {
            val page = database.localTavernDBQueries
                .selectLiveImageRefsPaged(GC_PAGE_SIZE, offset).executeAsList()
            if (page.isEmpty()) break
            page.forEach { refsJson ->
                deserializeImageRefs(refsJson).forEach { add(it.sha256) }
            }
            offset += page.size
            if (page.size < GC_PAGE_SIZE) break
        }
        // Sync stages avatars above the inline threshold in the blob store
        // (see SyncModels.avatarRefForTransport); those keys are live while
        // the owning row is. LENGTH()-filtered in SQL so small inline avatars
        // are never loaded into memory. ID-paged with one avatar hashed at a
        // time: even a single page of multi-MB avatar blobs would overflow
        // the ~2 MB Android CursorWindow ("Row too big to fit into
        // CursorWindow" at startup, before the unlock gate opens).
        val threshold = AVATAR_REF_THRESHOLD_BYTES
        val queries = database.localTavernDBQueries
        offset = 0L
        while (true) {
            val ids = queries
                .selectLiveCharacterAvatarIdsPaged(threshold, GC_PAGE_SIZE, offset).executeAsList()
            if (ids.isEmpty()) break
            ids.forEach { id ->
                readCharacterAvatarChunked(queries, id)?.let { bytes -> add(Hashing.sha256Hex(bytes)) }
            }
            offset += ids.size
            if (ids.size < GC_PAGE_SIZE) break
        }
        offset = 0L
        while (true) {
            val ids = queries
                .selectLivePersonaAvatarIdsPaged(threshold, GC_PAGE_SIZE, offset).executeAsList()
            if (ids.isEmpty()) break
            ids.forEach { id ->
                readPersonaAvatarChunked(queries, id)?.let { bytes -> add(Hashing.sha256Hex(bytes)) }
            }
            offset += ids.size
            if (ids.size < GC_PAGE_SIZE) break
        }
        // Out-of-band avatar refs: a live row that is still waiting for its
        // avatar bytes holds its ref on the row (the ref column IS the blob
        // key), so the blob must survive until the row resolves it.
        offset = 0L
        while (true) {
            val page = database.localTavernDBQueries
                .selectLiveCharacterAvatarRefsPaged(GC_PAGE_SIZE, offset).executeAsList()
            if (page.isEmpty()) break
            page.forEach { refsJson ->
                deserializeImageRefs(refsJson).forEach { add(it.sha256) }
            }
            offset += page.size
            if (page.size < GC_PAGE_SIZE) break
        }
        offset = 0L
        while (true) {
            val page = database.localTavernDBQueries
                .selectLivePersonaAvatarRefsPaged(GC_PAGE_SIZE, offset).executeAsList()
            if (page.isEmpty()) break
            page.forEach { refsJson ->
                deserializeImageRefs(refsJson).forEach { add(it.sha256) }
            }
            offset += page.size
            if (page.size < GC_PAGE_SIZE) break
        }
    }
    gcBlobStore(blobStore, usedKeys)
}

// One avatar's bytes via CursorWindow-safe chunked reads (see
// CharacterRepository.getCharacterAvatarData). Null when the row vanished or
// is unreadable; GC then simply keeps no key for it.
private fun readCharacterAvatarChunked(
    queries: chat.donzi.localtavern.data.database.LocalTavernDBQueries,
    id: String
): ByteArray? = runCatching {
    val length = queries.selectCharacterAvatarLength(id).executeAsOneOrNull()?.LENGTH
    if (length == null) {
        return@runCatching queries.selectCharacterAvatarData(id).executeAsOneOrNull()?.avatarData
    }
    if (length <= 0L) return@runCatching null
    if (length <= BLOB_CHUNK_BYTES) {
        return@runCatching queries.selectCharacterAvatarData(id).executeAsOneOrNull()?.avatarData
    }
    val out = ByteArray(length.toInt())
    var offset = 1L
    var dest = 0
    while (offset <= length) {
        val size = minOf(BLOB_CHUNK_BYTES, length - offset + 1)
        // substr() bind params are TEXT-typed by SQLDelight; SQLite coerces
        // the numeric strings (substr affinity conversion).
        val chunk = queries.selectCharacterAvatarChunk(offset.toString(), size.toString(), id)
            .executeAsOneOrNull()?.substr
            ?: return@runCatching if (dest == 0) null else out.copyOf(dest)
        chunk.copyInto(out, dest)
        dest += chunk.size
        offset += chunk.size
        if (chunk.size < size && offset <= length) break
    }
    if (dest == out.size) out else out.copyOf(dest)
}.getOrNull()

private fun readPersonaAvatarChunked(
    queries: chat.donzi.localtavern.data.database.LocalTavernDBQueries,
    id: String
): ByteArray? = runCatching {
    val length = queries.selectPersonaAvatarLength(id).executeAsOneOrNull()?.LENGTH
    if (length == null) {
        return@runCatching queries.selectPersonaAvatarData(id).executeAsOneOrNull()?.avatarData
    }
    if (length <= 0L) return@runCatching null
    if (length <= BLOB_CHUNK_BYTES) {
        return@runCatching queries.selectPersonaAvatarData(id).executeAsOneOrNull()?.avatarData
    }
    val out = ByteArray(length.toInt())
    var offset = 1L
    var dest = 0
    while (offset <= length) {
        val size = minOf(BLOB_CHUNK_BYTES, length - offset + 1)
        // substr() bind params are TEXT-typed by SQLDelight; SQLite coerces
        // the numeric strings (substr affinity conversion).
        val chunk = queries.selectPersonaAvatarChunk(offset.toString(), size.toString(), id)
            .executeAsOneOrNull()?.substr
            ?: return@runCatching if (dest == 0) null else out.copyOf(dest)
        chunk.copyInto(out, dest)
        dest += chunk.size
        offset += chunk.size
        if (chunk.size < size && offset <= length) break
    }
    if (dest == out.size) out else out.copyOf(dest)
}.getOrNull()

// Rows scanned per GC page: small enough that hashing one page of large
// avatars stays flat on a mobile heap, large enough that the page loop stays
// cheap on big libraries.
internal const val GC_PAGE_SIZE = 50L

// IDs scanned per legacy-image migration page (see the ID-paging note).
private const val MIGRATION_PAGE_SIZE = 50L

// Chunk size for CursorWindow-safe blob reassembly: far below the ~2 MB
// Android CursorWindow so each substr() row always fits.
private const val BLOB_CHUNK_BYTES = 512L * 1024

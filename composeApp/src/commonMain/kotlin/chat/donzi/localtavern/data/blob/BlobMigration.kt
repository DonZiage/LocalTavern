package chat.donzi.localtavern.data.blob

import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
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
    while (true) {
        val rows = queries.selectMessagesWithImages().executeAsList().take(50)
        if (rows.isEmpty()) break
        val prepared = rows.map { row ->
            val images = deserializeImageList(row.imageData)
            val refs = images.map { img ->
                val hash = Hashing.sha256Hex(img)
                if (blobStore.read(hash) == null) blobStore.write(hash, img)
                ImageRef(hash, img.size.toLong())
            }
            row.id to serializeImageRefs(refs)
        }
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
    }
    return migrated
}

/** Deletes blob-store files not referenced by any live (non-tombstoned) row. */
suspend fun runBlobGc(database: LocalTavernDB, blobStore: BlobStore) {
    val usedKeys = buildSet {
        database.localTavernDBQueries.selectAllLiveImageRefs().executeAsList().forEach { refsJson ->
            deserializeImageRefs(refsJson).forEach { add(it.sha256) }
        }
    }
    gcBlobStore(blobStore, usedKeys)
}

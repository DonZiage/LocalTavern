package chat.donzi.localtavern.data.blob

// Content-addressed blob storage for message images: keys are the SHA-256
// hex of the stored bytes, so equal images are stored exactly once and a key
// verifies its own content. The store lives on the platform file system
// (never in SQLite), which keeps the database small and sync exchanges
// bounded.
expect fun createBlobStore(): BlobStore

interface BlobStore {
    /** Stores [bytes] under [key]; overwrites any previous value. */
    suspend fun write(key: String, bytes: ByteArray)

    /** Returns the stored bytes, or null when the key is unknown. */
    suspend fun read(key: String): ByteArray?

    /** Removes the key; a missing key is a no-op. */
    suspend fun delete(key: String)

    /** All keys currently stored. */
    suspend fun listKeys(): Set<String>
}

/**
 * Deletes every stored blob whose key is not in [usedKeys] (content-addressed
 * keys make this safe: a key is either referenced by a live row or dead).
 * Missing/corrupt files are silently dropped. Runs on startup after the
 * database is open, so unreferenced files from deleted/edited messages do
 * not accumulate.
 */
suspend fun gcBlobStore(store: BlobStore, usedKeys: Set<String>) {
    val stored = store.listKeys()
    if (stored.isEmpty()) return
    stored.filterNot { it in usedKeys }.forEach { store.delete(it) }
}

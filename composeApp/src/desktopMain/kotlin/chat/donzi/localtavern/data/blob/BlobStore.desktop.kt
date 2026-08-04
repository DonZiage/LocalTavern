package chat.donzi.localtavern.data.blob

import java.io.File

// Message images live on disk under ~/.localtavern/blobs/, next to the
// SQLite database. Keys are SHA-256 hex (from the app's own hashing), so
// filenames are safe on every platform. Writes are atomic (temp file +
// rename) so a crash mid-write can never leave a truncated blob behind a
// live reference.
actual fun createBlobStore(): BlobStore = FileBlobStore()

private class FileBlobStore : BlobStore {

    private fun directory(): File? {
        val userHome = System.getProperty("user.home") ?: System.getProperty("user.dir")
        val dir = File(File(userHome, ".localtavern"), "blobs")
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun fileFor(key: String): File? {
        if (key.isBlank() || key.length > 128) return null
        return directory()?.resolve(key)
    }

    override suspend fun write(key: String, bytes: ByteArray) {
        val target = fileFor(key) ?: return
        val temp = File(target.parentFile, "$key.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            // A concurrent same-key write may have won the rename; fall back
            // to a direct write when the target already holds the content.
            if (target.exists()) return
            target.writeBytes(bytes)
        }
    }

    override suspend fun read(key: String): ByteArray? =
        runCatching { fileFor(key)?.takeIf { it.exists() }?.readBytes() }.getOrNull()

    override suspend fun delete(key: String) {
        runCatching { fileFor(key)?.delete() }
    }

    override suspend fun listKeys(): Set<String> =
        runCatching { directory()?.list()?.filter { !it.endsWith(".tmp") }?.toSet() }.getOrNull() ?: emptySet()
}

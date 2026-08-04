package chat.donzi.localtavern.data.blob

import chat.donzi.localtavern.AndroidAppContext
import java.io.File

// Message images live in the app-private files directory (internal storage,
// not visible to other apps). Same content-addressed keys and atomic write
// pattern as the desktop store.
actual fun createBlobStore(): BlobStore = AndroidBlobStore()

private class AndroidBlobStore : BlobStore {

    private fun directory(): File? {
        val context = AndroidAppContext.getContext() ?: return null
        val dir = File(context.filesDir, "blobs")
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

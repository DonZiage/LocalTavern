package chat.donzi.localtavern.data.blob

import chat.donzi.localtavern.utils.Hashing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

// Message images live in the app's private Documents/blobs folder. Same
// content-addressed keys as the other platforms. Everything is marked
// NSURLIsExcludedFromBackupKey: chat images are private user content and must
// never leave the device via iCloud backup.
actual fun createBlobStore(): BlobStore = IosBlobStore()

@OptIn(ExperimentalForeignApi::class)
private class IosBlobStore : BlobStore {

    private fun directoryUrl(): NSURL? {
        val documents = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask
        ).firstOrNull() as? NSURL ?: return null
        val dir = documents.URLByAppendingPathComponent("blobs", isDirectory = true) as NSURL
        NSFileManager.defaultManager.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
        // Applies to the directory itself; every written file is also marked.
        dir.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return dir
    }

    private fun urlFor(key: String): NSURL? {
        // Only canonical SHA-256 hex keys are valid file names: any other key
        // (path traversal, relative segments, non-hex) must never resolve into
        // the blob directory — refs can arrive from the wire.
        if (!Hashing.isValidSha256Hex(key)) return null
        return directoryUrl()?.URLByAppendingPathComponent(key)
    }

    override suspend fun write(key: String, bytes: ByteArray) {
        val url = urlFor(key) ?: return
        runCatching {
            val data = bytes.usePinned { pinned ->
                NSData.dataWithBytes(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            data.writeToURL(url, atomically = true)
            url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
    }

    override suspend fun read(key: String): ByteArray? {
        val url = urlFor(key) ?: return null
        return runCatching { NSData.dataWithContentsOfURL(url)?.toByteArray() }.getOrNull()
    }

    override suspend fun delete(key: String) {
        val url = urlFor(key) ?: return
        runCatching { NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
    }

    override suspend fun listKeys(): Set<String> {
        val url = directoryUrl() ?: return emptySet()
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtURL(
            url, includingPropertiesForKeys = null, options = 0u, error = null
        ) ?: return emptySet()
        return buildSet {
            contents.forEach { item ->
                (item as? NSURL)?.lastPathComponent?.let { if (!it.endsWith(".tmp")) add(it) }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val out = ByteArray(length)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length.convert())
    }
    return out
}

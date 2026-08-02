package chat.donzi.localtavern.data.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

// The device identity is stored in the app's private documents directory on
// iOS.
@OptIn(ExperimentalForeignApi::class)
actual fun createSyncIdentityStore(): SyncIdentityStore = IosSyncIdentityStore()

@OptIn(ExperimentalForeignApi::class)
private class IosSyncIdentityStore : SyncIdentityStore {

    private fun identityUrl(): NSURL? {
        val documents = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask
        ).firstOrNull() as? NSURL ?: return null
        return documents.URLByAppendingPathComponent("sync-identity.json")
    }

    override fun load(): ByteArray? {
        val url = identityUrl() ?: return null
        return runCatching { NSData.dataWithContentsOfURL(url)?.toByteArray() }.getOrNull()
    }

    override fun save(bytes: ByteArray) {
        val url = identityUrl() ?: return
        runCatching {
            val data = bytes.usePinned { pinned ->
                NSData.dataWithBytes(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
            data.writeToURL(url, atomically = true)
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

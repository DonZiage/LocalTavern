package chat.donzi.localtavern.data.sync

import chat.donzi.localtavern.AndroidAppContext
import java.io.File

// The device identity lives in the app's private files directory on Android
// (internal storage, not visible to other apps or the file browser).
actual fun createSyncIdentityStore(): SyncIdentityStore = AndroidSyncIdentityStore()

private class AndroidSyncIdentityStore : SyncIdentityStore {

    private fun identityFile(): File? {
        val context = AndroidAppContext.getContext() ?: return null
        return File(context.filesDir, "sync-identity.json")
    }

    override fun load(): ByteArray? = runCatching {
        identityFile()?.takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    override fun save(bytes: ByteArray) {
        runCatching { identityFile()?.writeBytes(bytes) }
    }
}

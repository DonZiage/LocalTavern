package chat.donzi.localtavern.data.sync

import java.io.File

// The device identity lives in the app data directory on desktop.
actual fun createSyncIdentityStore(): SyncIdentityStore = DesktopSyncIdentityStore()

private class DesktopSyncIdentityStore : SyncIdentityStore {

    private fun identityFile(): File {
        val userHome = System.getProperty("user.home") ?: System.getProperty("user.dir")
        return File(File(userHome, ".localtavern"), "sync-identity.json")
    }

    override fun load(): ByteArray? = runCatching {
        val file = identityFile()
        if (file.exists()) file.readBytes() else null
    }.getOrNull()

    override fun save(bytes: ByteArray) {
        runCatching {
            val file = identityFile()
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }
}

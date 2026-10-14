package chat.donzi.localtavern.data.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

// The device identity (deviceId + X25519 PRIVATE key, the root of all pairing
// trust) lives in the app data directory on desktop. Writes are atomic (temp
// + rename: a crash mid-write must not corrupt the identity, which would
// silently invalidate every pairing on the next launch) and owner-only
// (other local users must not read the private key).
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
            val tmp = File(file.parentFile, "sync-identity.json.tmp")
            tmp.writeBytes(bytes)
            try {
                Files.setPosixFilePermissions(
                    tmp.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            } catch (_: UnsupportedOperationException) {
                tmp.setReadable(false, false)
                tmp.setWritable(false, false)
                tmp.setReadable(true, true)
                tmp.setWritable(true, true)
            } catch (_: java.io.IOException) {
                tmp.setReadable(false, false)
                tmp.setWritable(false, false)
                tmp.setReadable(true, true)
                tmp.setWritable(true, true)
            }
            val target = file.toPath()
            try {
                Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

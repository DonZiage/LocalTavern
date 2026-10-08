package chat.donzi.localtavern.data.database

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import kotlin.time.Clock

// Renames the live database files (db + WAL sidecars) aside so a fresh
// database can be created after corruption. Never throws.
@OptIn(ExperimentalForeignApi::class)
actual fun quarantineCorruptDatabaseFiles(): Boolean {
    return try {
        // Same file the native driver opens ("localtavern.db" in Documents);
        // kept in sync by convention, see DriverFactory.ios.
        val fileManager = NSFileManager.defaultManager
        val documentsDir = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as? NSURL
        val basePath = documentsDir?.URLByAppendingPathComponent("localtavern.db")?.path ?: return false
        val tag = Clock.System.now().toEpochMilliseconds()
        var moved = false
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val path = basePath + suffix
            if (fileManager.fileExistsAtPath(path)) {
                if (fileManager.moveItemAtPath(path, toPath = "$path.corrupt-$tag", error = null)) {
                    moved = true
                }
            }
        }
        moved
    } catch (_: Exception) {
        false
    }
}

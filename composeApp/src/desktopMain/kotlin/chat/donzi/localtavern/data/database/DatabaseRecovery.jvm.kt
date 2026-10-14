package chat.donzi.localtavern.data.database

import java.io.File
import kotlin.time.Clock

// Renames the live database files (db + WAL sidecars) aside so a fresh
// database can be created after corruption. Never throws.
actual fun quarantineCorruptDatabaseFiles(): Boolean {
    return try {
        // Same location DriverFactory.jvm opens ("local_tavern.db" under
        // ~/.localtavern); kept in sync by convention, see that file.
        val userHome = System.getProperty("user.home") ?: System.getProperty("user.dir")
        val basePath = File(File(userHome, ".localtavern"), "local_tavern.db").absolutePath
        val tag = Clock.System.now().toEpochMilliseconds()
        var moved = false
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(basePath + suffix)
            if (file.exists() && file.renameTo(File("$basePath$suffix.corrupt-$tag"))) {
                moved = true
            }
        }
        moved
    } catch (_: Exception) {
        false
    }
}

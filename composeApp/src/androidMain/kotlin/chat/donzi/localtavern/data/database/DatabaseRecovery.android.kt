package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.AndroidAppContext
import java.io.File
import kotlin.time.Clock

// Renames the live database files (db + WAL sidecars) aside so a fresh
// database can be created after corruption. Never throws.
actual fun quarantineCorruptDatabaseFiles(): Boolean {
    return try {
        // Same file AndroidSqliteDriver opens ("localtavern.db" in the app
        // database directory); kept in sync by convention, see
        // DriverFactory.android.
        val context = AndroidAppContext.getContext() ?: return false
        val basePath = context.getDatabasePath("localtavern.db")?.absolutePath ?: return false
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

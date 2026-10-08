package chat.donzi.localtavern.data.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException

// Thrown when the database file fails its integrity check (or an open fails
// with a corruption-flavored error): the file cannot be trusted, and the
// recovery path quarantines it so the app can always launch on a fresh file.
class CorruptDatabaseException(message: String) : Exception(message)

// Moves the database files (db + WAL sidecars) aside under a
// `.corrupt-<epochmillis>` suffix so a fresh database can be created.
// Returns true when anything was moved. Never throws: recovery must not
// depend on the filesystem cooperating.
expect fun quarantineCorruptDatabaseFiles(): Boolean

internal fun isCorruptionError(e: Throwable): Boolean {
    // SQLite reports corruption as SQLITE_CORRUPT ("database disk image is
    // malformed") or "file is not a database" across all three drivers (the
    // message text is what survives the platform exception wrapping).
    val message = (e.message ?: "").lowercase()
    return "corrupt" in message || "malformed" in message || "not a database" in message
}

// Runs SQLite's own structural check: exactly one row "ok" on a healthy
// database, anything else (or a throw) means the file cannot be trusted.
internal fun SqlDriver.checkHealthy() {
    val rows = ArrayList<String>()
    executeQuery(
        identifier = null,
        sql = "PRAGMA quick_check;",
        mapper = { cursor ->
            while (cursor.next().value) {
                rows.add(cursor.getString(0) ?: "")
            }
            QueryResult.Value(Unit)
        },
        parameters = 0
    )
    val problems = rows.filter { it != "ok" }
    if (problems.isNotEmpty()) {
        throw CorruptDatabaseException(
            "Database integrity check failed: ${problems.take(3).joinToString("; ")}"
        )
    }
}

/**
 * Opens the database, discarding corrupt data so the app can ALWAYS launch.
 *
 * A crash or kill mid-sync (OOM, OS power killer, full disk) can tear the
 * database file; without recovery the first query of every launch throws and
 * the app dies on the loading screen forever. Corrupt files are quarantined
 * beside the database (kept for forensics, never read again) and a fresh
 * database is created — pairings and local rows are lost, but the app
 * launches and the user can re-pair / re-import. Anything that is NOT
 * corruption (disk full, permission errors) propagates untouched.
 */
fun openHealthyDriver(factory: DriverFactory): SqlDriver {
    val first = try {
        factory.createDriver()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isCorruptionError(e)) throw e
        null
    }
    if (first != null) {
        try {
            first.checkHealthy()
            return first
        } catch (e: CancellationException) {
            runCatching { first.close() }
            throw e
        } catch (e: Exception) {
            runCatching { first.close() }
            if (!isCorruptionError(e)) throw e
        }
    }
    quarantineCorruptDatabaseFiles()
    return factory.createDriver().also { it.checkHealthy() }
}

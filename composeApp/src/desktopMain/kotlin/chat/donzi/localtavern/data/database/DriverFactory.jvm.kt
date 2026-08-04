package chat.donzi.localtavern.data.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // user.home can be null in unusual environments (service accounts);
        // fall back to the working directory rather than crashing.
        val userHome = System.getProperty("user.home") ?: System.getProperty("user.dir")
        val tavernDir = File(userHome, ".localtavern")
        if (!tavernDir.exists() && !tavernDir.mkdirs()) {
            throw IllegalStateException("Could not create LocalTavern data directory: $tavernDir")
        }
        val databaseFile = File(tavernDir, "local_tavern.db")

        // busy_timeout must be part of the URL: the SQLDelight JDBC driver opens
        // a separate connection per thread (ThreadedConnectionManager), and a
        // PRAGMA executed here would only apply to this thread's connection.
        // Without a timeout on every connection, a concurrent write on another
        // thread fails immediately with "SQL is busy" (SQLITE_BUSY).
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}?busy_timeout=5000")

        // The JDBC driver has no built-in schema versioning, so create/migrate
        // against PRAGMA user_version manually. (Android and iOS drivers manage
        // this themselves.) Silently swallowing Schema.create errors left stale
        // schemas behind on app updates.
        val currentVersion = readUserVersion(driver)
        if (currentVersion < LocalTavernDB.Schema.version) {
            val hasTables = queryRow(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'CharacterEntity';") > 0L
            if (!hasTables) {
                LocalTavernDB.Schema.create(driver)
            } else {
                // An existing database whose user_version was never stamped (0)
                // cannot be assumed to be v1: it may already carry the
                // round-trip columns from later versions (e.g. from an
                // intermediate dev build). Re-running an old migration on a
                // newer schema would crash every launch with "duplicate column
                // name". Detect the real schema from the columns instead of
                // trusting the version number. The mappings mirror the
                // migrations: 1.sqm adds systemPrompt (v1->v2), 5.sqm adds
                // inferenceProvider (v5->v6), 6.sqm adds quantization
                // (v6->v7), 7.sqm adds sendWithCtrlEnter (v7->v8), 8.sqm adds
                // syncSeq (v8->v9), 9.sqm adds imageRefs (v9->v10).
                val actualOldVersion = currentVersion.coerceAtLeast(
                    when {
                        hasColumn(driver, "MessageEntity", "imageRefs") -> 10L
                        hasColumn(driver, "MessageEntity", "syncSeq") -> 9L
                        hasColumn(driver, "AppSettings", "sendWithCtrlEnter") -> 8L
                        hasColumn(driver, "ApiConnection", "quantization") -> 7L
                        hasColumn(driver, "ApiConnection", "inferenceProvider") -> 6L
                        hasColumn(driver, "CharacterEntity", "systemPrompt") -> 2L
                        else -> 1L
                    }
                )
                if (actualOldVersion < LocalTavernDB.Schema.version) {
                    LocalTavernDB.Schema.migrate(
                        driver,
                        oldVersion = actualOldVersion,
                        newVersion = LocalTavernDB.Schema.version
                    )
                }
            }
            writeUserVersion(driver, LocalTavernDB.Schema.version)
        }

        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)

        return driver
    }

    private fun readUserVersion(driver: SqlDriver): Long =
        queryRow(driver, "PRAGMA user_version;")

    private fun writeUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version;", 0)
    }

    private fun queryRow(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0
        ).value

    private fun hasColumn(driver: SqlDriver, table: String, column: String): Boolean =
        // pragma_table_info is a table-valued function available since
        // SQLite 3.16; table/column names are compile-time constants.
        queryRow(driver, "SELECT count(*) FROM pragma_table_info('$table') WHERE name = '$column';") > 0L
}

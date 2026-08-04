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
        // this themselves.) The migration history was reset: the schema is
        // created fresh at version 1, and future .sqm files dropped into
        // migrations/ will be run here for older databases.
        val currentVersion = readUserVersion(driver)
        if (currentVersion < LocalTavernDB.Schema.version) {
            val hasTables = queryRow(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'CharacterEntity';") > 0L
            if (!hasTables) {
                LocalTavernDB.Schema.create(driver)
            } else {
                LocalTavernDB.Schema.migrate(driver, currentVersion, LocalTavernDB.Schema.version)
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
}

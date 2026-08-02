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

        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")

        // The JDBC driver has no built-in schema versioning, so create/migrate
        // against PRAGMA user_version manually. (Android and iOS drivers manage
        // this themselves.) Silently swallowing Schema.create errors left stale
        // schemas behind on app updates.
        val currentVersion = readUserVersion(driver)
        if (currentVersion < LocalTavernDB.Schema.version) {
            val hasV1Tables = queryRow(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'CharacterEntity';") > 0L
            if (!hasV1Tables) {
                LocalTavernDB.Schema.create(driver)
            } else {
                // An existing database from an older release: its user_version
                // was never stamped (0), but the tables already exist. Treat it
                // as version 1 and run the migrations from there.
                LocalTavernDB.Schema.migrate(
                    driver,
                    oldVersion = currentVersion.coerceAtLeast(1),
                    newVersion = LocalTavernDB.Schema.version
                )
            }
            writeUserVersion(driver, LocalTavernDB.Schema.version)
        }

        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)

        driver.execute(null, "PRAGMA busy_timeout=5000;", 0)

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

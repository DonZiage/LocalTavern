package chat.donzi.localtavern.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val userHome = System.getProperty("user.home")
        val tavernDir = File(userHome, ".localtavern").apply { mkdirs() }
        val databaseFile = File(tavernDir, "local_tavern.db")

        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")

        try {
            LocalTavernDB.Schema.create(driver)
        } catch (_: Exception) {
        }

        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)

        driver.execute(null, "PRAGMA busy_timeout=5000;", 0)

        return driver
    }
}
package chat.donzi.localtavern.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        // Creates a "LocalTavern" folder in your user home to avoid permission issues
        val userHome = System.getProperty("user.home")
        val tavernDir = File(userHome, ".localtavern").apply { mkdirs() }
        val databaseFile = File(tavernDir, "local_tavern.db")

        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")

        // Only create the schema if the database is brand new
        try {
            LocalTavernDB.Schema.create(driver)
        } catch (e: Exception) {
            // Database already exists, skip creation
        }
        return driver
    }
}

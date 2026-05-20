package chat.donzi.localtavern.data.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(LocalTavernDB.Schema, context, "localtavern.db")

        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)

        driver.execute(null, "PRAGMA busy_timeout=5000;", 0)

        return driver
    }
}
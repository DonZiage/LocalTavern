package chat.donzi.localtavern.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(LocalTavernDB.Schema, "localtavern.db")

        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)

        driver.execute(null, "PRAGMA busy_timeout=5000;", 0)

        return driver
    }
}
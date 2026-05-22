package chat.donzi.localtavern.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = LocalTavernDB.Schema,
            context = context,
            name = "localtavern.db",
            callback = object : AndroidSqliteDriver.Callback(LocalTavernDB.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.enableWriteAheadLogging()
                    db.query("PRAGMA busy_timeout=5000;").use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            }
        )
    }
}
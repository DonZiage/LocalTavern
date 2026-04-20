package chat.donzi.localtavern.database

import app.cash.sqldelight.db.SqlDriver

// We use an 'expect' function here instead of a class
// to avoid the constructor mismatch error you're seeing.
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): LocalTavernDB {
    return LocalTavernDB(driverFactory.createDriver())
}
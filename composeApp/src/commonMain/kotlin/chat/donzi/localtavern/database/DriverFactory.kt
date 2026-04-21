package chat.donzi.localtavern.database

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DriverFactory): LocalTavernDB {
    return LocalTavernDB(driverFactory.createDriver())
}
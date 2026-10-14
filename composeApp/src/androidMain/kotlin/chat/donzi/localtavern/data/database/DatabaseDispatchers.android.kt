package chat.donzi.localtavern.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// The Android driver wraps a single framework SQLiteDatabase, which
// serializes statements internally; with WAL enabled (DriverFactory) reads
// and writes can overlap on multiple threads safely.
actual fun createDatabaseDispatchers(): DatabaseDispatchers =
    DatabaseDispatchers(
        write = Dispatchers.IO.limitedParallelism(1),
        read = Dispatchers.IO.limitedParallelism(3)
    )

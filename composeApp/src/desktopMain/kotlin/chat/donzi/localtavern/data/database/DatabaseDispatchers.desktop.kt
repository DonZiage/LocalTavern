package chat.donzi.localtavern.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// The JDBC driver opens one SQLite connection per thread. All writes funnel
// through a single thread (one connection, never SQLITE_BUSY); reads run on
// a small pool of extra connections — safe under WAL + busy_timeout (see the
// driver URL), which is exactly the configuration this platform uses.
actual fun createDatabaseDispatchers(): DatabaseDispatchers =
    DatabaseDispatchers(
        write = Dispatchers.IO.limitedParallelism(1),
        read = Dispatchers.IO.limitedParallelism(3)
    )

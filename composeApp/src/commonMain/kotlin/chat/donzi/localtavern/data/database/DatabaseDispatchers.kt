package chat.donzi.localtavern.data.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Dispatchers for database access, split between a single write connection
 * and a pool of read connections.
 *
 * SQLite serializes writers anyway, and the JVM driver opens one connection
 * per thread (JdbcSqliteDriver.ThreadedConnectionManager) — so all writes
 * must funnel through ONE thread ([write], parallelism 1) or concurrent
 * transactions hit SQLITE_BUSY. Reads, however, never contend with writers
 * under WAL, so they can run on a pool ([read]) and load-heavy flows (chat
 * timelines, character lists) no longer queue behind the writer.
 *
 * Reads that are part of a write flow (transactions, read-modify-write) must
 * stay on [write]: SQLDelight transaction queries run on the transaction's
 * connection, and a cross-connection read would miss uncommitted state.
 */
class DatabaseDispatchers(
    val write: CoroutineDispatcher,
    val read: CoroutineDispatcher
) {
    companion object {
        /** Fully serialized: tests (in-memory DBs are shared, not pooled) and
         *  platforms whose driver must not be shared across threads. */
        fun serialized(): DatabaseDispatchers =
            DatabaseDispatchers(
                write = Dispatchers.IO.limitedParallelism(1),
                read = Dispatchers.IO.limitedParallelism(1)
            )
    }
}

expect fun createDatabaseDispatchers(): DatabaseDispatchers

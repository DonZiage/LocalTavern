package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Local message writes must stay forwardable over sync: a single row above
// the sync per-row ceiling can never be forwarded again, so it wedges every
// future sync behind a clean error until the row is found and removed. The
// repository refuses such rows up front (far above the UI send cap and any
// legitimate model reply) instead of creating an unwedgeable row.
class MessageBackstopTest {

    private fun newRepos(): Triple<LocalTavernDB, SessionRepository, MessageRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val sessions = SessionRepository(db)
        return Triple(db, sessions, MessageRepository(db, sessionRepository = sessions))
    }

    @Test
    fun insertMessage_refusesOversizedContent() = runTest {
        val (_, _, messages) = newRepos()
        assertFailsWith<IllegalArgumentException> {
            messages.insertMessage("session-1", "user", "x".repeat(3_000_000), null)
        }
    }

    @Test
    fun updatePaths_refuseOversizedContent() = runTest {
        val (_, _, messages) = newRepos()
        val id = messages.insertMessageRaw("session-1", "user", "hello", null, true)
        assertFailsWith<IllegalArgumentException> {
            messages.updateMessageContent(id, "x".repeat(3_000_000))
        }
        assertFailsWith<IllegalArgumentException> {
            messages.updateMessageContentAndReasoning(id, "ok", "y".repeat(3_000_000))
        }
        assertFailsWith<IllegalArgumentException> {
            messages.updateMessageReasoning(id, "z".repeat(3_000_000))
        }
    }
}

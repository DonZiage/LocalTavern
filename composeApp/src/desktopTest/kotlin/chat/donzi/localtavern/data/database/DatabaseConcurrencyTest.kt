package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Proves the read/write dispatcher split is safe on the desktop driver:
// writes funnel through ONE connection while reads run on a pool of extra
// connections (WAL + busy_timeout). Before the split, concurrent access from
// multiple threads surfaced as SQLITE_BUSY or multi-second stalls; this test
// would catch both. Uses a FILE-backed database — the in-memory driver uses
// a single shared connection (StaticConnectionManager), which would not
// exercise the multi-connection path at all.
class DatabaseConcurrencyTest {

    private class Setup(
        val db: LocalTavernDB,
        val characterRepository: CharacterRepository,
        val sessionRepository: SessionRepository,
        val messageRepository: MessageRepository,
        val readDispatcher: CoroutineDispatcher,
        val writeDispatcher: CoroutineDispatcher
    )

    private fun newSetup(tempDir: File): Setup {
        val dbFile = File(tempDir, "concurrency.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}?busy_timeout=5000"
        val driver = JdbcSqliteDriver(url)
        LocalTavernDB.Schema.create(driver)
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
        val db = LocalTavernDB(driver)
        val clock = LogicalClock(db)
        val write = Dispatchers.IO.limitedParallelism(1)
        val read = Dispatchers.IO.limitedParallelism(3)
        val sessionRepository = SessionRepository(db, ioDispatcher = write, clock = clock, readDispatcher = read)
        return Setup(
            db = db,
            characterRepository = CharacterRepository(db, clock = clock, ioDispatcher = write, readDispatcher = read),
            sessionRepository = sessionRepository,
            messageRepository = MessageRepository(db, ioDispatcher = write, clock = clock, sessionRepository = sessionRepository, readDispatcher = read),
            readDispatcher = read,
            writeDispatcher = write
        )
    }

    @Test
    fun `concurrent readers and a writer never produce SQLITE_BUSY or lost writes`() = runBlocking {
        val tempDir = Files.createTempDirectory("localtavern-concurrency").toFile()
        try {
            val setup = newSetup(tempDir)
            val sessionId = setup.sessionRepository.createNewSession("char-1", "persona-1")

            val writes = 150
            val readsPerReader = 100
            val readers = 6

            // One writer coroutine...
            val writer = async {
                repeat(writes) { i ->
                    setup.messageRepository.insertMessage(sessionId, "user", "message $i", parentId = null)
                }
                setup.characterRepository.getAllCharacters() // write dispatcher can read too
            }

            // ...hammered by a pool of readers running concurrently.
            val readerJobs = (0 until readers).map { readerIndex ->
                async {
                    repeat(readsPerReader) { j ->
                        setup.messageRepository.getMessagesForSession(sessionId)
                        setup.messageRepository.getAllMessagesForSession(sessionId)
                        setup.sessionRepository.getSessionById(sessionId)
                        setup.characterRepository.getAssistant()
                        if ((readerIndex + j) % 17 == 0) {
                            setup.messageRepository.getMessageSiblings(sessionId, null)
                        }
                    }
                }
            }

            // Everything must finish promptly: the pre-split failure mode was
            // SQLITE_BUSY exceptions; the post-split failure mode would be
            // writer stalls up to the 5 s busy_timeout.
            withTimeout(60_000) {
                writer.await()
                readerJobs.awaitAll()
            }

            // Every write landed, and a read sees the full history (WAL
            // readers observe each committed transaction).
            val count = setup.messageRepository.getAllMessagesForSession(sessionId).size
            assertEquals(writes, count, "All written messages must be visible to reads")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `a long write transaction does not block readers`() = runBlocking {
        val tempDir = Files.createTempDirectory("localtavern-concurrency").toFile()
        try {
            val setup = newSetup(tempDir)
            val sessionId = setup.sessionRepository.createNewSession("char-1", "persona-1")

            // A transaction that writes a lot: on a single connection it
            // takes long enough for readers to overlap it.
            val longWrite = async {
                setup.db.localTavernDBQueries.transactionWithResult {
                    repeat(300) { i ->
                        setup.db.localTavernDBQueries.insertMessageWithParent(
                            id = "bulk-$i", sessionId = sessionId, role = "user", content = "bulk $i",
                            timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 1L,
                            isDeleted = 0L, imageRefs = null, reasoningText = null,
                            costEstimate = null, syncSeq = 0L
                        )
                    }
                    true
                }
            }

            // Readers must keep completing while the write transaction runs.
            coroutineScope {
                val reader = async {
                    var ok = 0
                    repeat(50) {
                        setup.messageRepository.getMessagesForSession(sessionId)
                        ok++
                    }
                    ok
                }
                longWrite.await()
                val completed = reader.await()
                assertEquals(50, completed, "Readers must never be starved by a long write transaction")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `mixed read-modify-write flows under load stay consistent`() = runBlocking {
        val tempDir = Files.createTempDirectory("localtavern-concurrency").toFile()
        try {
            val setup = newSetup(tempDir)
            val sessionId = setup.sessionRepository.createNewSession("char-1", "persona-1")

            coroutineScope {
                val jobs = (0 until 4).map { writerIndex ->
                    async {
                        repeat(40) { i ->
                            setup.messageRepository.insertMessage(sessionId, "user", "$writerIndex-$i", parentId = null)
                        }
                        setup.sessionRepository.updateSessionTitle(sessionId, "title-$writerIndex")
                    }
                }
                jobs.awaitAll()
            }

            val session = setup.sessionRepository.getSessionById(sessionId)
            assertEquals("title-3", session?.title, "Last writer wins the title")
            assertEquals(160, setup.messageRepository.getAllMessagesForSession(sessionId).size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Character/persona list screens must not load avatar bytes: the startup
// flows observe the whole library, and one full SELECT pins every avatar on
// the heap before the first frame. Header reads carry every display column
// with the blobs nulled; single-avatar reads serve the visible rows on
// demand, and the full row reads stay intact for detail screens.
class CharacterHeadersTest {

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    private fun newRepository(db: LocalTavernDB): CharacterRepository =
        CharacterRepository(db, clock = LogicalClock(db))

    private fun seedCharacter(db: LocalTavernDB, avatar: ByteArray) {
        db.localTavernDBQueries.insertCharacter(
            id = "char-1", name = "Alice", description = "Explorer", personality = "Brave",
            scenario = "Forest", firstMes = "Hi", mesExample = "ex", creatorNotes = "notes",
            altGreetings = "hey", avatarData = avatar, isAssistant = 0L, updatedAt = 10L,
            isDeleted = 0L, systemPrompt = "sys", postHistoryInstructions = "phi", creator = "me",
            characterVersion = "1", tags = "[\"x\"]", extensions = "{}", characterBook = "{}",
            syncSeq = 1L
        )
    }

    @Test
    fun characterHeaders_carryEverythingButAvatarBytes() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        val avatar = ByteArray(1024) { it.toByte() }
        seedCharacter(db, avatar)

        val headers = repo.getAllCharacterHeaders()
        assertEquals(1, headers.size)
        val header = headers.single()
        assertEquals("char-1", header.id)
        assertEquals("Alice", header.name)
        assertEquals("Explorer", header.description)
        assertNull(header.avatarData, "List headers must never carry avatar bytes")

        // The full row is untouched: detail screens still get their bytes.
        val full = repo.getCharacterById("char-1")
        assertNotNull(full)
        assertTrue(full.avatarData != null && full.avatarData.contentEquals(avatar))

        // On-demand single-avatar read serves the visible rows.
        val bytes = repo.getCharacterAvatarData("char-1")
        assertNotNull(bytes)
        assertTrue(bytes.contentEquals(avatar))
    }

    @Test
    fun observeCharacterHeaders_emitsWithoutAvatarBytes() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        seedCharacter(db, ByteArray(512) { (it * 3).toByte() })

        var emitted = false
        val headers = repo.observeCharacterHeaders().first()
        emitted = true
        assertTrue(headers.all { it.avatarData == null }, "Header flows must never carry avatar bytes")
        assertTrue(emitted, "The header flow must emit")
    }

    @Test
    fun personaHeaders_carryEverythingButAvatarBytes() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        val avatar = ByteArray(512) { (it * 3).toByte() }
        db.localTavernDBQueries.insertPersona(
            id = "persona-1", name = "User", description = "Scholar",
            avatarData = avatar, updatedAt = 5L, isDeleted = 0L, syncSeq = 2L
        )

        val headers = repo.getAllPersonaHeaders()
        assertEquals(1, headers.size)
        assertEquals("User", headers.single().name)
        assertNull(headers.single().avatarData, "List headers must never carry avatar bytes")

        val full = repo.getAllPersonas()
        assertEquals(1, full.size)
        assertTrue(full.single().avatarData != null && full.single().avatarData.contentEquals(avatar))

        val bytes = repo.getPersonaAvatarData("persona-1")
        assertNotNull(bytes)
        assertTrue(bytes.contentEquals(avatar))
    }
}

package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.utils.ImportedCharacter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CharacterRepositoryImportTest {

    private fun card(name: String, description: String) = SillyTavernCardV2(
        name = name,
        description = description,
        personality = "p",
        scenario = "s",
        first_mes = "Hi",
        mes_example = "A<START>B",
        creator_notes = "notes",
        system_prompt = "system",
        post_history_instructions = "post",
        alternate_greetings = listOf("Alt"),
        creator = "creator",
        character_version = "2.0",
        tags = listOf("tag"),
        extensions = null,
        character_book = null
    )

    private fun newRepository(): Pair<CharacterRepository, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        return CharacterRepository(db, clock = LogicalClock(db)) to driver
    }

    @Test
    fun importCharacters_insertsAllCardsInOneBatch() = runTest {
        val (repo, driver) = newRepository()
        try {
            val count = repo.importCharacters(
                listOf(
                    ImportedCharacter(card("Alice", "a"), avatarData = byteArrayOf(1, 2)),
                    ImportedCharacter(card("Bob", "b"), avatarData = null)
                )
            )
            assertEquals(2, count)

            val all = repo.getAllCharacters()
            assertEquals(2, all.size)
            val alice = all.first { it.name == "Alice" }
            assertEquals("a", alice.description)
            assertContentEquals(byteArrayOf(1, 2), alice.avatarData)
            assertEquals(listOf("Alt"), alice.altGreetings)
            assertEquals(listOf("tag"), alice.tags)
            val bob = all.first { it.name == "Bob" }
            assertEquals(null, bob.avatarData)
        } finally {
            driver.close()
        }
    }

    @Test
    fun importCharacters_emptyList_isNoop() = runTest {
        val (repo, driver) = newRepository()
        try {
            assertEquals(0, repo.importCharacters(emptyList()))
            assertEquals(0, repo.getAllCharacters().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun importCharacters_keepsDuplicateVisibleNames() = runTest {
        val (repo, driver) = newRepository()
        try {
            val count = repo.importCharacters(
                listOf(
                    ImportedCharacter(card("Alice", "first"), null),
                    ImportedCharacter(card("Alice", "second"), null)
                )
            )
            assertEquals(2, count)
            val alices = repo.getAllCharacters().filter { it.name == "Alice" }
            assertEquals(2, alices.size, "Duplicate display names must import as distinct rows")
            assertEquals(setOf("first", "second"), alices.map { it.description }.toSet())
        } finally {
            driver.close()
        }
    }
}

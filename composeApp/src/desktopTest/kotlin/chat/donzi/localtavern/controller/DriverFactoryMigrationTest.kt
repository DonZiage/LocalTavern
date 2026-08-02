package chat.donzi.localtavern.controller

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.domain.Character
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriverFactoryMigrationTest {

    private val v1CharacterDdl = """
        CREATE TABLE CharacterEntity (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            personality TEXT,
            scenario TEXT,
            firstMes TEXT,
            mesExample TEXT,
            creatorNotes TEXT,
            altGreetings TEXT,
            avatarData BLOB,
            isAssistant INTEGER NOT NULL DEFAULT 0,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
    """.trimIndent()

    private fun tempDir(): File = Files.createTempDirectory("localtavern-test").toFile()

    @Test
    fun `existing v1 database is migrated to v2 and data survives`() = runTest {
        val tempDir = tempDir()
        try {
            val dbFile = File(tempDir, ".localtavern/local_tavern.db")
            dbFile.parentFile.mkdirs()

            // Simulate a database written by a previous release: v1 schema,
            // never-stamped user_version, and a stored character.
            val v1Driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            v1Driver.execute(null, v1CharacterDdl, 0)
            v1Driver.execute(
                null,
                "INSERT INTO CharacterEntity(id, name, description, personality, scenario, firstMes, mesExample, creatorNotes, altGreetings, avatarData, isAssistant, updatedAt, isDeleted) " +
                    "VALUES ('c1', 'Old Character', 'desc', 'personality', 'scenario', 'Hello!', NULL, NULL, NULL, NULL, 0, 1, 0);",
                0
            )

            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                val database = LocalTavernDB(driver)
                val repository = CharacterRepository(database)

                val characters = repository.getAllCharacters()
                assertEquals(1, characters.size)
                val migrated = characters.first()
                assertEquals("Old Character", migrated.name)
                assertEquals("Hello!", migrated.firstMes)

                // New columns are present and writable.
                repository.upsertCharacter(
                    SillyTavernCardV2(
                        name = "New Character",
                        description = "d",
                        personality = "p",
                        scenario = "s",
                        first_mes = "Hi",
                        mes_example = "A<START>B",
                        creator_notes = "notes",
                        system_prompt = "sys",
                        post_history_instructions = "post",
                        creator = "creator",
                        character_version = "1.0",
                        tags = listOf("fantasy", "adventure"),
                        extensions = buildJsonObject { put("key", "value") },
                        character_book = buildJsonObject { put("book", true) }
                    )
                )

                val fresh = repository.getAllCharacters().first { it.name == "New Character" }
                assertEquals("sys", fresh.systemPrompt)
                assertEquals("post", fresh.postHistoryInstructions)
                assertEquals("creator", fresh.creator)
                assertEquals("1.0", fresh.characterVersion)
                assertEquals(listOf("fantasy", "adventure"), fresh.tags)
                assertEquals("value", fresh.extensions?.get("key")?.jsonPrimitive?.content)
                assertNotNull(fresh.characterBook)

                // <START>-separated examples are split on read-back.
                assertEquals(listOf("A", "B"), fresh.mesExample)
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `fresh database is created at the latest schema`() = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                val database = LocalTavernDB(driver)
                val repository = CharacterRepository(database)
                repository.upsertCharacter(
                    SillyTavernCardV2(
                        name = "Fresh",
                        system_prompt = "round trip",
                        creator = "me",
                        tags = listOf("a")
                    )
                )
                val character = repository.getAllCharacters().first()
                assertEquals("Fresh", character.name)
                assertEquals("round trip", character.systemPrompt)
                assertEquals("me", character.creator)
                assertEquals(listOf("a"), character.tags)
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `character editor save preserves round-trip fields`() = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                val database = LocalTavernDB(driver)
                val repository = CharacterRepository(database)

                val id = repository.upsertCharacter(
                    SillyTavernCardV2(
                        name = "Rich",
                        system_prompt = "keep me",
                        creator = "someone",
                        tags = listOf("t1")
                    )
                )
                repository.updateCharacter(
                    id = id,
                    name = "Rich Renamed",
                    personality = "p",
                    scenario = "s",
                    description = null,
                    firstMes = "Hi",
                )
                val updated = repository.getAllCharacters().first { it.id == id }
                assertEquals("Rich Renamed", updated.name)
                assertEquals("keep me", updated.systemPrompt)
                assertEquals("someone", updated.creator)
                assertEquals(listOf("t1"), updated.tags)
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `character domain round-trips through export json`() {
        val character = Character(
            id = "x",
            name = "Card",
            description = "d",
            personality = "p",
            scenario = "s",
            firstMes = "Hello",
            mesExample = listOf("A", "B"),
            creatorNotes = "notes",
            altGreetings = listOf("Alt 1"),
            avatarData = null,
            systemPrompt = "system",
            postHistoryInstructions = "post",
            creator = "creator",
            characterVersion = "2.0",
            tags = listOf("tag1"),
            extensions = buildJsonObject { put("ext", "value") },
            characterBook = buildJsonObject { put("book", "yes") }
        )
        val jsonString = chat.donzi.localtavern.utils.CharacterManager.exportToJson(character).decodeToString()
        val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonString)
        val data = parsed.jsonObject["data"]!!.jsonObject
        assertEquals("Card", data["name"]!!.jsonPrimitive.content)
        assertEquals("A<START>B", data["mes_example"]!!.jsonPrimitive.content)
        assertEquals("system", data["system_prompt"]!!.jsonPrimitive.content)
        assertEquals("post", data["post_history_instructions"]!!.jsonPrimitive.content)
        assertEquals("creator", data["creator"]!!.jsonPrimitive.content)
        assertEquals("2.0", data["character_version"]!!.jsonPrimitive.content)
        assertEquals("tag1", data["tags"]!!.jsonArray.first().jsonPrimitive.content)
        assertTrue(data.containsKey("extensions"))
        assertTrue(data.containsKey("character_book"))
    }
}

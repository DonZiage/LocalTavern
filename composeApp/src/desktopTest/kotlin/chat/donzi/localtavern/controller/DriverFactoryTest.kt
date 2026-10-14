package chat.donzi.localtavern.controller

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
import kotlin.test.assertTrue

class DriverFactoryTest {

    private fun tempDir(): File = Files.createTempDirectory("localtavern-test").toFile()

    @Test
    fun `fresh database is created at the latest schema`() = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
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
                    driver.close()
                }
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
                try {
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
                    driver.close()
                }
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

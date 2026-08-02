package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterManagerTest {

    private val character = Character(
        id = "char1",
        name = "Alice",
        description = "A curious explorer",
        personality = "Brave and kind",
        scenario = "Deep forest",
        firstMes = "Hello there!",
        mesExample = listOf("Example: *waves*", "Example2: *grins*"),
        creatorNotes = null,
        altGreetings = listOf("Greetings!"),
        avatarData = null
    )

    @Test
    fun exportedJson_usesSillyTavernV2Wrapper() {
        val bytes = CharacterManager.exportToJson(character)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

        assertEquals("chara_card_v2", root["spec"]?.jsonPrimitive?.content)
        assertEquals("2.0", root["spec_version"]?.jsonPrimitive?.content)
        assertTrue(root.containsKey("data"), "Exported card must embed the card data under the 'data' key")
        val data = root["data"]!!.jsonObject
        assertEquals("Alice", data["name"]?.jsonPrimitive?.content)
        assertEquals("A curious explorer", data["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun exportedJson_roundTripsThroughProcessImport() {
        val bytes = CharacterManager.exportToJson(character)
        val imported = CharacterManager.processImport(bytes, "Alice.json")

        assertNotNull(imported, "Exported card must be re-importable")
        assertEquals("Alice", imported.card.name)
        assertEquals("A curious explorer", imported.card.description)
        assertEquals("Brave and kind", imported.card.personality)
        assertEquals("Deep forest", imported.card.scenario)
        assertEquals("Hello there!", imported.card.first_mes)
        assertEquals(listOf("Greetings!"), imported.card.alternate_greetings)
    }

    @Test
    fun bareCardJson_withoutWrapper_stillImports() {
        val bare = """
            {"name":"Bob","description":"No wrapper","personality":"","scenario":"",
             "first_mes":"Hi","mes_example":"","creator_notes":"","system_prompt":"",
             "alternate_greetings":[]}
        """.trimIndent()
        val imported = CharacterManager.processImport(bare.encodeToByteArray(), "Bob.json")
        assertNotNull(imported)
        assertEquals("Bob", imported.card.name)
        assertEquals("No wrapper", imported.card.description)
    }

    @Test
    fun tavernAIV1Card_importsAsV2() {
        val v1 = """
            {"char_name":"Old Kate","char_persona":"A grizzled innkeeper","char_greeting":"Welcome, traveler!",
             "world_scenario":"A rainy tavern","example_dialogue":"Kate: Hmm.<START>Kate: *grins*",
             "alternate_greetings":"Alt one|||Alt two"}
        """.trimIndent()
        val imported = CharacterManager.processImport(v1.encodeToByteArray(), "Old Kate.json")

        assertNotNull(imported, "Legacy TavernAI v1 cards must import instead of becoming blank characters")
        assertEquals("Old Kate", imported.card.name)
        assertEquals("A grizzled innkeeper", imported.card.description)
        assertEquals("A rainy tavern", imported.card.scenario)
        assertEquals("Welcome, traveler!", imported.card.first_mes)
        assertEquals(listOf("Alt one", "Alt two"), imported.card.alternate_greetings)
        assertEquals(2, imported.card.mes_example.split("<START>").size,
            "v1 example_dialogue separators must be preserved")
    }

    @Test
    fun corruptAvatar_neverWritesBrokenPng() {
        // A non-PNG avatar that the platform converter cannot convert must
        // fall back to a JSON card instead of writing a corrupt .png.
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 1)
        val (fileName, bytes) = CharacterManager.prepareExportBytes(
            character.copy(avatarData = fakeJpeg)
        )
        assertTrue(fileName.endsWith(".json"), "Unconvertible avatar must fall back to JSON export, got $fileName")
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("chara_card_v2", root["spec"]?.jsonPrimitive?.content)
    }

    @Test
    fun truncatedPng_doesNotCrashExport() {
        // A PNG whose IHDR length field is garbage must not splice outside
        // the buffer (the old code threw ArrayIndexOutOfBoundsException).
        val corruptPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // IHDR length: huge
            0x49, 0x48, 0x44, 0x52
        )
        val (fileName, bytes) = CharacterManager.prepareExportBytes(
            character.copy(avatarData = corruptPng)
        )
        assertTrue(fileName.endsWith(".png"), "Signature-valid PNG must still export as .png, got $fileName")
        assertTrue(bytes.size == corruptPng.size, "Corrupt PNG must pass through unchanged, not crash")
    }
}

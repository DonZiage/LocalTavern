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
}

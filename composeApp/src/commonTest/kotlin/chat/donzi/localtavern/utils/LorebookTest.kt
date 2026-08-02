package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Lorebook
import chat.donzi.localtavern.domain.LorebookEntry
import chat.donzi.localtavern.domain.PromptBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LorebookParserTest {

    @Test
    fun parse_readsEntriesAndFlags() {
        val book = LorebookParser.parse(sampleBook())

        assertEquals("World Lore", book.name)
        assertEquals(2, book.entries.size)
        val tavern = book.entries.first { it.name == "The Tavern" }
        assertEquals(listOf("tavern", "inn"), tavern.keys)
        assertEquals("The tavern is run by dwarves.", tavern.content)
        assertEquals(false, tavern.constant)
        val sword = book.entries.first { it.name == "Sword" }
        assertEquals(true, sword.constant)
        assertEquals(true, sword.selective)
        assertEquals(listOf("legendary"), sword.secondaryKeys)
    }

    @Test
    fun parse_ignoresDisabledEntriesButKeepsThem() {
        val book = LorebookParser.parse(
            buildJsonObject {
                put(
                    "entries", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "Disabled")
                            put("keys", buildJsonArray { add(JsonPrimitive("secret")) })
                            put("content", "never injected")
                            put("enabled", false)
                        })
                    }
                )
            }
        )
        assertEquals(1, book.entries.size)
        assertTrue(book.enabledEntries.isEmpty())
    }

    @Test
    fun toJson_roundTripsEntries() {
        val original = Lorebook(
            name = "B",
            entries = listOf(
                LorebookEntry(name = "E", keys = listOf("k1"), content = "c", constant = true)
            )
        )
        val json = LorebookParser.toJson(original)
        val reparsed = LorebookParser.parse(json)
        assertEquals(original, reparsed)
    }

    @Test
    fun parse_nullOrBlankReturnsEmpty() {
        assertEquals(Lorebook(), LorebookParser.parse(null))
        assertEquals(Lorebook(), LorebookParser.parseJson(""))
        assertEquals(Lorebook(), LorebookParser.parseJson("not json"))
    }

    @Test
    fun parse_handlesSillyTavernStyleSecondaryKeys() {
        val raw = """{"name":"b","entries":[{"name":"e","keys":["moon"],"secondary_keys":["night"],"selective":true,"content":"x"}]}"""
        val book = LorebookParser.parseJson(raw)
        assertEquals(listOf("moon"), book.entries.first().keys)
        assertEquals(listOf("night"), book.entries.first().secondaryKeys)
        assertTrue(book.entries.first().selective)
    }

    companion object {
        fun sampleBook(): JsonObject = Json.parseToJsonElement(
            """
            {
              "name": "World Lore",
              "description": "Settings",
              "entries": [
                {
                  "id": 0,
                  "name": "The Tavern",
                  "keys": ["tavern", "inn"],
                  "secondary_keys": [],
                  "comment": "",
                  "content": "The tavern is run by dwarves.",
                  "constant": false,
                  "selective": false,
                  "insertion_order": 0,
                  "enabled": true,
                  "position": "before_char",
                  "case_sensitive": false
                },
                {
                  "id": 1,
                  "name": "Sword",
                  "keys": ["sword"],
                  "secondary_keys": ["legendary"],
                  "comment": "",
                  "content": "The sword is a family heirloom.",
                  "constant": true,
                  "selective": true,
                  "insertion_order": 1,
                  "enabled": true,
                  "position": "before_char",
                  "case_sensitive": false
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
    }
}

class LorebookTriggerTest {

    private fun charWithBook(bookJson: String?): Character = Character(
        id = "c1",
        name = "Alice",
        description = null,
        personality = "",
        scenario = "",
        firstMes = null,
        creatorNotes = null,
        avatarData = null,
        characterBook = bookJson?.let { Json.parseToJsonElement(it) as? JsonObject }
    )

    @Test
    fun constantEntries_alwaysMatch() {
        val char = charWithBook("""{"entries":[{"name":"C","keys":[],"content":"always in","constant":true}]}""")
        val text = ContextManager.buildLorebookText(char, emptyList())
        assertTrue(text.contains("always in"))
    }

    @Test
    fun keyMatch_isCaseInsensitiveByDefault() {
        val char = charWithBook("""{"entries":[{"name":"E","keys":["tavern"],"content":"dwarf inn","constant":false}]}""")
        val text = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "We walked into the TAVERN."))
        )
        assertTrue(text.contains("dwarf inn"))
    }

    @Test
    fun noKeyMatch_producesEmptyText() {
        val char = charWithBook("""{"entries":[{"name":"E","keys":["tavern"],"content":"dwarf inn","constant":false}]}""")
        val text = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "Nothing about ale here."))
        )
        assertEquals("", text)
    }

    @Test
    fun selectiveEntry_requiresSecondaryKey() {
        val char = charWithBook(
            """{"entries":[{"name":"E","keys":["sword"],"secondary_keys":["legendary"],"content":"heirloom","constant":false,"selective":true}]}"""
        )
        val withoutSecondary = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "I drew my sword."))
        )
        assertEquals("", withoutSecondary)

        val withSecondary = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "I drew my legendary sword."))
        )
        assertTrue(withSecondary.contains("heirloom"))
    }

    @Test
    fun caseSensitiveEntry_respectsCase() {
        val char = charWithBook(
            """{"entries":[{"name":"E","keys":["Tavern"],"content":"dwarf inn","constant":false,"case_sensitive":true}]}"""
        )
        val noMatch = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "the tavern is warm"))
        )
        assertEquals("", noMatch)
        val match = ContextManager.buildLorebookText(
            char,
            listOf(ChatMessage(role = "user", content = "the Tavern is warm"))
        )
        assertTrue(match.contains("dwarf inn"))
    }

    @Test
    fun buildPayload_injectsLorebookWhenNoBlockPlacesIt() {
        val char = charWithBook("""{"entries":[{"name":"E","keys":["tavern"],"content":"dwarf inn","constant":false}]}""")
        val payload = ContextManager.buildPayload(
            blocks = listOf(PromptBlock(id = "s", name = "System", isEnabled = true, template = "You are a narrator.")),
            character = char,
            persona = null,
            chatHistory = listOf(ChatMessage(role = "user", content = "At the tavern, I rest.")),
            contextLimit = 4096,
            responseLimit = 256
        )
        assertTrue(payload.first().content.contains("dwarf inn"), "Lorebook must auto-inject into the system prompt")
    }

    @Test
    fun buildPayload_lorebookMacroPlacesEntriesExplicitly() {
        val char = charWithBook("""{"entries":[{"name":"E","keys":["tavern"],"content":"dwarf inn","constant":false}]}""")
        val payload = ContextManager.buildPayload(
            blocks = listOf(
                PromptBlock(id = "s", name = "System", isEnabled = true, template = "Lore:\n{{lorebook}}"),
                PromptBlock(id = "h", name = "History", isEnabled = true, template = "{{chat_history}}")
            ),
            character = char,
            persona = null,
            chatHistory = listOf(ChatMessage(role = "user", content = "I enter the tavern.")),
            contextLimit = 4096,
            responseLimit = 256
        )
        assertTrue(payload.first().content.contains("Lore:"))
        assertTrue(payload.first().content.contains("dwarf inn"))
        // The macro must not leak into history messages.
        assertFalse(payload.any { it.role != "system" && it.content.contains("{{lorebook}}") })
    }
}

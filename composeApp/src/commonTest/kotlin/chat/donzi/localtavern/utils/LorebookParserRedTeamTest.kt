package chat.donzi.localtavern.utils

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Red-team: hostile characterBook types must degrade, never throw.
class LorebookParserRedTeamTest {

    @Test
    fun objectInStringList_doesNotThrow() {
        val hostile = buildJsonObject {
            putJsonArray("entries") {
                add(buildJsonObject {
                    put("name", "E")
                    putJsonArray("keys") {
                        add(buildJsonObject { put("x", 1) })
                    }
                    put("content", "c")
                })
            }
        }
        val book = LorebookParser.parse(hostile)
        assertEquals(1, book.entries.size)
        assertTrue(book.entries[0].keys.isEmpty())
    }

    @Test
    fun objectScalars_doNotThrow() {
        val hostile = buildJsonObject {
            put("name", buildJsonObject { put("x", 1) })
            put("description", buildJsonArray { add(JsonPrimitive(1)) })
            putJsonArray("entries") {
                add(buildJsonObject {
                    put("id", buildJsonObject { put("x", 1) })
                    put("name", "E")
                    put("keys", buildJsonArray { add(JsonPrimitive("k")) })
                    put("content", "c")
                    put("enabled", buildJsonObject { put("x", 1) })
                    put("constant", buildJsonArray { })
                    put("insertion_order", buildJsonObject { put("x", 1) })
                })
            }
        }
        val book = LorebookParser.parse(hostile)
        assertEquals(1, book.entries.size)
    }

    @Test
    fun nonObjectEntries_skippedWithoutThrow() {
        val hostile = buildJsonObject {
            putJsonArray("entries") {
                add(buildJsonObject {
                    put("name", "Good")
                    putJsonArray("keys") { add(JsonPrimitive("k")) }
                    put("content", "c")
                })
            }
        }
        val notArray = buildJsonObject { put("entries", "nope") }
        assertTrue(LorebookParser.parse(notArray).entries.isEmpty())
        assertEquals(1, LorebookParser.parse(hostile).entries.size)
    }
}

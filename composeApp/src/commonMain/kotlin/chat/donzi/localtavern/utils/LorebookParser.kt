package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Lorebook
import chat.donzi.localtavern.domain.LorebookEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

object LorebookParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a SillyTavern "characterBook" object into a [Lorebook]. */
    fun parse(characterBook: JsonObject?): Lorebook {
        if (characterBook == null) return Lorebook()
        val entries = characterBook["entries"]
            ?.takeIf { it is JsonArray }
            ?.jsonArray
            ?.mapNotNull { element -> parseEntry(element) }
            ?: emptyList()
        return Lorebook(
            name = characterBook["name"]?.jsonPrimitive?.contentOrNull,
            description = characterBook["description"]?.jsonPrimitive?.contentOrNull,
            entries = entries
        )
    }

    private fun parseEntry(element: JsonElement): LorebookEntry? {
        if (element !is JsonObject) return null
        return LorebookEntry(
            id = element["id"]?.jsonPrimitive?.longOrNull,
            name = element["name"]?.jsonPrimitive?.contentOrNull ?: "",
            keys = parseStringList(element["keys"]),
            secondaryKeys = parseStringList(element["secondary_keys"]),
            content = element["content"]?.jsonPrimitive?.contentOrNull ?: "",
            enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            constant = element["constant"]?.jsonPrimitive?.booleanOrNull ?: false,
            selective = element["selective"]?.jsonPrimitive?.booleanOrNull ?: false,
            caseSensitive = element["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
            insertionOrder = element["insertion_order"]?.jsonPrimitive?.longOrNull,
            position = element["position"]?.jsonPrimitive?.contentOrNull,
            priority = element["priority"]?.jsonPrimitive?.longOrNull,
            comment = element["comment"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /** Serializes a [Lorebook] back into the SillyTavern characterBook shape. */
    fun toJson(book: Lorebook): JsonObject {
        return JsonObject(
            buildMap {
                book.name?.let { put("name", JsonPrimitive(it)) }
                book.description?.let { put("description", JsonPrimitive(it)) }
                put("entries", toEntriesJson(book.entries))
            }
        )
    }

    // The standalone world-info (lorebook) file format shares the entry shape
    // with characterBook; only the top-level shape differs.
    fun toWorldInfoJson(book: Lorebook): JsonObject {
        return JsonObject(
            buildMap {
                book.name?.let { put("name", JsonPrimitive(it)) }
                book.description?.let { put("description", JsonPrimitive(it)) }
                put("entries", toEntriesJson(book.entries))
            }
        )
    }

    private fun toEntriesJson(entries: List<LorebookEntry>): JsonArray {
        return JsonArray(entries.map { entry ->
            JsonObject(
                buildMap {
                    entry.id?.let { put("id", JsonPrimitive(it)) }
                    put("name", JsonPrimitive(entry.name))
                    put("keys", JsonArray(entry.keys.map { JsonPrimitive(it) }))
                    put("secondary_keys", JsonArray(entry.secondaryKeys.map { JsonPrimitive(it) }))
                    put("content", JsonPrimitive(entry.content))
                    put("enabled", JsonPrimitive(entry.enabled))
                    put("constant", JsonPrimitive(entry.constant))
                    put("selective", JsonPrimitive(entry.selective))
                    put("case_sensitive", JsonPrimitive(entry.caseSensitive))
                    entry.insertionOrder?.let { put("insertion_order", JsonPrimitive(it)) }
                    entry.position?.let { put("position", JsonPrimitive(it)) }
                    entry.priority?.let { put("priority", JsonPrimitive(it)) }
                    entry.comment?.let { put("comment", JsonPrimitive(it)) }
                }
            )
        })
    }

    fun parseJson(raw: String?): Lorebook {
        if (raw.isNullOrBlank()) return Lorebook()
        return runCatching { parse(json.parseToJsonElement(raw) as? JsonObject) }.getOrElse { Lorebook() }
    }

    /** Pretty-printed standalone world-info JSON for lorebook file exports. */
    fun worldInfoToJsonString(book: Lorebook): String {
        return exportJson.encodeToString(toWorldInfoJson(book))
    }

    // Parses a standalone lorebook file: a SillyTavern world-info object
    // ({"entries": [...]}) or a {"characterBook": {...}} wrapper. Returns null
    // when the file does not look like a lorebook.
    fun parseWorldInfo(raw: String?): Lorebook? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(raw)
            val obj = element as? JsonObject ?: return null
            val bookJson = when {
                obj.containsKey("entries") -> obj
                obj["characterBook"] is JsonObject -> obj["characterBook"] as JsonObject
                else -> return null
            }
            val book = parse(bookJson)
            if (book.entries.isEmpty()) null else book
        }.getOrNull()
    }

    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }
}

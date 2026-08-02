package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.models.SillyTavernWrapper
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.saveFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ImportedCharacter(
    val card: SillyTavernCardV2,
    val avatarData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ImportedCharacter
        if (card != other.card) return false
        if (avatarData != null) {
            if (other.avatarData == null) return false
            if (!avatarData.contentEquals(other.avatarData)) return false
        } else if (other.avatarData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = card.hashCode()
        result = 31 * result + (avatarData?.contentHashCode() ?: 0)
        return result
    }
}

object CharacterManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0].toInt() == 0x89.toByte().toInt() &&
                bytes[1].toInt() == 0x50.toByte().toInt() &&
                bytes[2].toInt() == 0x4E.toByte().toInt() &&
                bytes[3].toInt() == 0x47.toByte().toInt() &&
                bytes[4].toInt() == 0x0D.toByte().toInt() &&
                bytes[5].toInt() == 0x0A.toByte().toInt() &&
                bytes[6].toInt() == 0x1A.toByte().toInt() &&
                bytes[7].toInt() == 0x0A.toByte().toInt()
    }

    fun processImport(bytes: ByteArray, fileName: String? = null): ImportedCharacter? {
        return try {
            val png = isPng(bytes)
            val isJson = fileName?.endsWith(".json", ignoreCase = true) == true

            val jsonString: String? = if (png) {
                PngParser.extractSillyTavernCard(bytes)
            } else if (isJson) {
                bytes.decodeToString()
            } else {
                PngParser.extractSillyTavernCard(bytes)
            }

            if (jsonString.isNullOrBlank()) return null

            val card = parseCardJson(jsonString) ?: return null
            val avatarData: ByteArray? = if (png) bytes else null

            ImportedCharacter(card, avatarData)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCardJson(jsonString: String): SillyTavernCardV2? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            if (element !is JsonObject) return null
            if (element.containsKey("data")) {
                json.decodeFromJsonElement<SillyTavernWrapper>(element).data
            } else if (element.containsKey("char_name") && !element.containsKey("name")) {
                // Legacy TavernAI v1 cards use a flat layout (char_name,
                // char_persona, char_greeting, world_scenario, example_dialogue)
                // instead of the v2 data wrapper; they are still widely
                // circulated and must not import as blank characters.
                parseTavernAIV1Card(element)
            } else {
                json.decodeFromJsonElement<SillyTavernCardV2>(element)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTavernAIV1Card(element: JsonObject): SillyTavernCardV2 {
        fun string(key: String): String = element[key]?.jsonPrimitive?.contentOrNull ?: ""

        // v1 separates entries with "|||" (and "<START>" is also accepted).
        fun list(key: String): List<String> = element[key]?.jsonPrimitive?.contentOrNull
            ?.split(Regex("""\s*<START>\s*|\s*\|\|\|\s*"""))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return SillyTavernCardV2(
            name = string("char_name"),
            description = string("char_persona"),
            personality = string("char_personality"),
            scenario = string("world_scenario"),
            first_mes = string("char_greeting"),
            mes_example = list("example_dialogue").joinToString("<START>"),
            creator_notes = string("creator_notes"),
            system_prompt = string("system_prompt"),
            post_history_instructions = string("post_history_instructions"),
            alternate_greetings = list("alternate_greetings"),
            creator = string("creator"),
            character_version = string("character_version"),
            tags = emptyList(),
            extensions = null,
            character_book = null
        )
    }

    private fun getCardJsonString(character: Character): String {
        val card = SillyTavernCardV2(
            name = character.name,
            description = character.description ?: "",
            personality = character.personality,
            scenario = character.scenario,
            first_mes = character.firstMes ?: "",
            // SillyTavern v2 separates example dialogue with "<START>" (the
            // legacy TavernAI "|||" separator is accepted on import).
            mes_example = character.mesExample.joinToString("<START>"),
            creator_notes = character.creatorNotes ?: "",
            system_prompt = character.systemPrompt ?: "",
            post_history_instructions = character.postHistoryInstructions ?: "",
            alternate_greetings = character.altGreetings,
            creator = character.creator ?: "",
            character_version = character.characterVersion ?: "",
            tags = character.tags,
            extensions = character.extensions,
            character_book = character.characterBook
        )
        return json.encodeToString(
            SillyTavernWrapper(
                spec = "chara_card_v2",
                spec_version = "2.0",
                data = card
            )
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun exportToPng(originalImage: ByteArray, character: Character): ByteArray {
        val pngImageBytes = if (isPng(originalImage)) {
            originalImage
        } else {
            val converted = chat.donzi.localtavern.convertToPng(originalImage)
            // The platform converters return the original bytes on failure;
            // embedding a metadata chunk into non-PNG data would produce a
            // .png file no image viewer can open.
            if (!isPng(converted)) {
                throw IllegalArgumentException("Avatar could not be converted to PNG")
            }
            converted
        }

        val jsonString = getCardJsonString(character)
        val base64Data = Base64.encode(jsonString.encodeToByteArray())
        val chunkData = "chara\u0000$base64Data".encodeToByteArray()

        return insertMetadataChunk(pngImageBytes, chunkData)
    }

    fun exportToJson(character: Character): ByteArray {
        return getCardJsonString(character).encodeToByteArray()
    }

    fun getFileName(character: Character): String {
        val safeName = sanitizeFileName(character.name)
        val hasAvatar = character.avatarData != null && character.avatarData.isNotEmpty()
        return if (hasAvatar) "$safeName.png" else "$safeName.json"
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "character" }
        return sanitized.take(80)
    }

    // A content:// URI (Android MediaStore export) has no real parent
    // directory: returning a truncated URI would break openDirectory() and
    // show a garbage location, so the full URI is returned unchanged (the
    // platform handler then opens the exported file itself).
    fun extractParentDir(savedPath: String): String {
        if (savedPath.startsWith("content://")) return savedPath
        val separatorIndex = maxOf(savedPath.lastIndexOf('/'), savedPath.lastIndexOf('\\'))
        return if (separatorIndex > 0) savedPath.substring(0, separatorIndex) else savedPath
    }

    fun prepareExportBytes(character: Character): Pair<String, ByteArray> {
        val fileName = getFileName(character)
        val avatar = character.avatarData

        val exportedBytes = if (avatar != null && avatar.isNotEmpty()) {
            try {
                exportToPng(avatar, character)
            } catch (e: IllegalArgumentException) {
                // Fall back to a JSON card instead of writing a corrupt .png.
                return "${sanitizeFileName(character.name)}.json" to exportToJson(character)
            }
        } else {
            exportToJson(character)
        }

        return fileName to exportedBytes
    }

    fun saveExportedFile(fileName: String, bytes: ByteArray): String? {
        val savedPath = saveFile(fileName, bytes)
        return savedPath?.let { extractParentDir(it) }
    }

    fun performExport(character: Character): String? {
        val (fileName, exportedBytes) = prepareExportBytes(character)
        return saveExportedFile(fileName, exportedBytes)
    }

    private fun insertMetadataChunk(pngBytes: ByteArray, data: ByteArray): ByteArray {
        if (pngBytes.size < 33) return pngBytes

        val ihdrDataLength = ((pngBytes[8].toInt() and 0xFF) shl 24) or
                ((pngBytes[9].toInt() and 0xFF) shl 16) or
                ((pngBytes[10].toInt() and 0xFF) shl 8) or
                (pngBytes[11].toInt() and 0xFF)
        // A real IHDR data block is exactly 13 bytes; a bogus length (from a
        // truncated or corrupt PNG) must not splice outside the buffer.
        if (ihdrDataLength < 13) return pngBytes
        val endOfIhdrOffset = 8 + 12 + ihdrDataLength
        if (endOfIhdrOffset > pngBytes.size) return pngBytes

        val type = "tEXt".encodeToByteArray()
        val chunkTotalSize = 4 + 4 + data.size + 4

        val result = ByteArray(pngBytes.size + chunkTotalSize)

        pngBytes.copyInto(destination = result, destinationOffset = 0, startIndex = 0, endIndex = endOfIhdrOffset)

        var offset = endOfIhdrOffset
        writeInt(result, offset, data.size)
        offset += 4
        type.copyInto(result, offset)
        offset += type.size
        data.copyInto(result, offset)
        offset += data.size

        val crc = CommonCRC32()
        crc.update(type)
        crc.update(data)
        writeInt(result, offset, crc.value.toInt())
        offset += 4

        if (pngBytes.size > endOfIhdrOffset) {
            pngBytes.copyInto(destination = result, destinationOffset = offset, startIndex = endOfIhdrOffset, endIndex = pngBytes.size)
        }
        return result
    }

    private fun writeInt(array: ByteArray, offset: Int, value: Int) {
        array[offset] = ((value shr 24) and 0xFF).toByte()
        array[offset + 1] = ((value shr 16) and 0xFF).toByte()
        array[offset + 2] = ((value shr 8) and 0xFF).toByte()
        array[offset + 3] = (value and 0xFF).toByte()
    }
}

private class CommonCRC32 {
    private var crc = -1
    fun update(bytes: ByteArray) {
        for (b in bytes) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = (crc ushr 8) xor crcTable[index]
        }
    }
    val value: Long get() = (crc.toLong() xor 0xFFFFFFFFL) and 0xFFFFFFFFL

    companion object {
        private val crcTable = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) -0x12477ce0 xor (c ushr 1) else c ushr 1
            }
            c
        }
    }
}
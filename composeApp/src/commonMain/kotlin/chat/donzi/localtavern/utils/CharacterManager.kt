package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.models.SillyTavernWrapper
import chat.donzi.localtavern.data.database.CharacterEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
                // Try PNG anyway if unknown
                PngParser.extractSillyTavernCard(bytes)
            }

            if (jsonString.isNullOrBlank()) {
                println("[CharacterManager] No character JSON found")
                return null
            }

            val card = parseCardJson(jsonString) ?: run {
                println("[CharacterManager] Failed to parse card JSON")
                return null
            }

            val avatarData: ByteArray? = if (png) bytes else null

            ImportedCharacter(card, avatarData)
        } catch (e: Exception) {
            println("[CharacterManager] Error processing import: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun parseCardJson(jsonString: String): SillyTavernCardV2? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            if (element !is JsonObject) return null
            if (element.containsKey("data")) {
                json.decodeFromJsonElement<SillyTavernWrapper>(element).data
            } else {
                json.decodeFromJsonElement<SillyTavernCardV2>(element)
            }
        } catch (e: Exception) {
            println("[CharacterManager] JSON parsing error: ${e.message}")
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun exportToPng(originalImage: ByteArray, character: CharacterEntity): ByteArray {
        val card = SillyTavernCardV2(
            name = character.name,
            description = character.description ?: "",
            personality = character.personality ?: "",
            scenario = character.scenario ?: "",
            first_mes = character.firstMes ?: "",
            mes_example = character.mesExample ?: "",
            system_prompt = character.systemPrompt ?: "",
            alternate_greetings = character.altGreetings?.split("|||") ?: emptyList()
        )
        val jsonString = json.encodeToString(card)
        val base64Data = Base64.encode(jsonString.encodeToByteArray())
        val chunkData = "chara\u0000$base64Data".encodeToByteArray()
        return insertMetadataChunk(originalImage, chunkData)
    }

    private fun insertMetadataChunk(pngBytes: ByteArray, data: ByteArray): ByteArray {
        if (pngBytes.size < 33) return pngBytes

        val ihdrDataLength = ((pngBytes[8].toInt() and 0xFF) shl 24) or
                             ((pngBytes[9].toInt() and 0xFF) shl 16) or
                             ((pngBytes[10].toInt() and 0xFF) shl 8) or
                             (pngBytes[11].toInt() and 0xFF)
        val ihdrTotalSize = 12 + ihdrDataLength
        
        val type = "tEXt".encodeToByteArray()
        val chunkTotalSize = 4 + 4 + data.size + 4 // length + type + data + crc
        
        val result = ByteArray(pngBytes.size + chunkTotalSize)
        
        // Copy signature + IHDR
        pngBytes.copyInto(result, 0, 0, 8 + ihdrTotalSize)
        
        var offset = 8 + ihdrTotalSize
        
        // Length
        writeInt(result, offset, data.size)
        offset += 4
        
        // Type
        type.copyInto(result, offset)
        offset += type.size
        
        // Data
        data.copyInto(result, offset)
        offset += data.size
        
        // CRC
        val crc = CommonCRC32()
        crc.update(type)
        crc.update(data)
        writeInt(result, offset, crc.value.toInt())
        offset += 4
        
        // Remaining
        val remainingOffset = 8 + ihdrTotalSize
        if (pngBytes.size > remainingOffset) {
            pngBytes.copyInto(result, offset, remainingOffset, pngBytes.size)
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

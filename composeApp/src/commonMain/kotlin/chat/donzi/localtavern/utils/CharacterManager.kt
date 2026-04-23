package chat.donzi.localtavern.utils

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.models.SillyTavernWrapper
import chat.donzi.localtavern.data.database.CharacterEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import java.awt.FileDialog
import java.awt.Frame

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

    fun openImportDialog(): ImportedCharacter? {
        val dialog = FileDialog(Frame(), "Select Character PNG", FileDialog.LOAD)
        dialog.isVisible = true
        return if (dialog.file != null) {
            val file = File(dialog.directory, dialog.file)
            processImport(file)
        } else {
            null
        }
    }

    fun processImport(file: File): ImportedCharacter? {
        return try {
            val bytes = file.readBytes()
            val png = isPng(bytes)

            val jsonString: String? = if (png) {
                PngParser.extractSillyTavernCard(bytes)
            } else if (file.extension.lowercase() == "json") {
                bytes.decodeToString()
            } else {
                PngParser.extractSillyTavernCard(bytes)
            }

            if (jsonString.isNullOrBlank()) {
                println("[CharacterManager] No character JSON found in ${file.name}")
                return null
            }

            val card = parseCardJson(jsonString) ?: run {
                println("[CharacterManager] Failed to parse card JSON from ${file.name}")
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
        val out = ByteArrayOutputStream()
        if (pngBytes.size < 33) return pngBytes

        out.write(pngBytes, 0, 8)
        val ihdrDataLength = ((pngBytes[8].toInt() and 0xFF) shl 24) or
                             ((pngBytes[9].toInt() and 0xFF) shl 16) or
                             ((pngBytes[10].toInt() and 0xFF) shl 8) or
                             (pngBytes[11].toInt() and 0xFF)
        val ihdrTotalSize = 12 + ihdrDataLength
        out.write(pngBytes, 8, ihdrTotalSize)

        val type = "tEXt".encodeToByteArray()
        val length = data.size
        out.write((length shr 24) and 0xFF); out.write((length shr 16) and 0xFF)
        out.write((length shr 8) and 0xFF); out.write(length and 0xFF)
        out.write(type)
        out.write(data)

        val crc = CRC32(); crc.update(type); crc.update(data)
        val crcVal = crc.value.toInt()
        out.write((crcVal shr 24) and 0xFF); out.write((crcVal shr 16) and 0xFF)
        out.write((crcVal shr 8) and 0xFF); out.write(crcVal and 0xFF)

        val remainingOffset = 8 + ihdrTotalSize
        if (pngBytes.size > remainingOffset) {
            out.write(pngBytes, remainingOffset, pngBytes.size - remainingOffset)
        }
        return out.toByteArray()
    }
}

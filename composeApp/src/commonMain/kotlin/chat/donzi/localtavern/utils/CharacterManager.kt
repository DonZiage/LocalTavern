package chat.donzi.localtavern.utils

import chat.donzi.localtavern.models.SillyTavernCardV2
import chat.donzi.localtavern.models.SillyTavernWrapper
import chat.donzi.localtavern.database.CharacterEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ImportedCharacter(
    val card: SillyTavernCardV2,
    val avatarPath: String?
)

object CharacterManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val avatarsDir: File by lazy {
        File(System.getProperty("user.home"), ".localtavern/avatars").apply { mkdirs() }
    }

    fun processImport(file: File): ImportedCharacter? {
        return try {
            val bytes = file.readBytes()
            val isPng = file.extension.lowercase() != "json"

            val jsonString: String? = if (isPng) {
                PngParser.extractSillyTavernCard(bytes)
            } else {
                bytes.decodeToString()
            }
            if (jsonString.isNullOrBlank()) {
                println("[CharacterManager] No character JSON found in ${file.name}")
                return null
            }

            val card = parseCardJson(jsonString) ?: run {
                println("[CharacterManager] Failed to parse card JSON from ${file.name}")
                return null
            }

            val avatarPath: String? = if (isPng) {
                val safeName = card.name.ifBlank { file.nameWithoutExtension }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(avatarsDir, "${safeName}-${System.currentTimeMillis()}.png")
                target.writeBytes(bytes)
                target.absolutePath
            } else null

            ImportedCharacter(card, avatarPath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseCardJson(jsonString: String): SillyTavernCardV2? {
        val element = json.parseToJsonElement(jsonString)
        if (element !is JsonObject) return null
        return if (element.containsKey("data")) {
            json.decodeFromJsonElement<SillyTavernWrapper>(element).data
        } else {
            json.decodeFromJsonElement<SillyTavernCardV2>(element)
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
            system_prompt = character.systemPrompt ?: ""
        )
        val jsonString = json.encodeToString(card)
        val base64Data = Base64.encode(jsonString.encodeToByteArray())
        val chunkData = "chara\u0000$base64Data".encodeToByteArray()
        return insertMetadataChunk(originalImage, chunkData)
    }

    private fun insertMetadataChunk(pngBytes: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG must have at least the 8-byte signature; if the caller passed an empty array,
        // fall back to writing a minimal PNG so downstream code doesn't crash.
        if (pngBytes.size < 8) {
            // Return data as-is; callers should provide a proper PNG.
            return pngBytes
        }
        out.write(pngBytes, 0, 8)
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
        out.write(pngBytes, 8, pngBytes.size - 8)
        return out.toByteArray()
    }
}
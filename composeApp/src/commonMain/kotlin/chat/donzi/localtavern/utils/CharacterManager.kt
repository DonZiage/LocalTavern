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
        
        // A valid PNG must start with an 8-byte signature and the first chunk MUST be IHDR.
        // IHDR chunk is 13 bytes data + 12 bytes overhead (length, type, CRC) = 25 bytes.
        // Minimum size to safely read signature + IHDR is 8 + 25 = 33 bytes.
        if (pngBytes.size < 33) {
            return pngBytes
        }

        // 1. Write the PNG Signature (8 bytes)
        out.write(pngBytes, 0, 8)

        // 2. Identify and write the IHDR chunk first to maintain PNG validity
        // The 4 bytes after the signature are the IHDR data length (usually 00 00 00 0D)
        val ihdrDataLength = ((pngBytes[8].toInt() and 0xFF) shl 24) or
                             ((pngBytes[9].toInt() and 0xFF) shl 16) or
                             ((pngBytes[10].toInt() and 0xFF) shl 8) or
                             (pngBytes[11].toInt() and 0xFF)
        
        // Total IHDR chunk size = 4 (length) + 4 (type) + dataLength + 4 (CRC)
        val ihdrTotalSize = 12 + ihdrDataLength
        
        // Write the original IHDR chunk
        out.write(pngBytes, 8, ihdrTotalSize)

        // 3. Insert our custom 'tEXt' metadata chunk after IHDR
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

        // 4. Write the remaining chunks of the original PNG
        val remainingOffset = 8 + ihdrTotalSize
        if (pngBytes.size > remainingOffset) {
            out.write(pngBytes, remainingOffset, pngBytes.size - remainingOffset)
        }

        return out.toByteArray()
    }
}
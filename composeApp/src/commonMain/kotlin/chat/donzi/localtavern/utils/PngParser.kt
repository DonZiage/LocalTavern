package chat.donzi.localtavern.utils

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object PngParser {

    @OptIn(ExperimentalEncodingApi::class)
    fun extractSillyTavernCard(bytes: ByteArray): String? {
        try {
            if (bytes.size < 8) return null
            var i = 8
            while (i + 8 <= bytes.size) {
                val length = readInt(bytes, i)
                if (length < 0 || i + 8 + length + 4 > bytes.size) return null

                val type = bytes.decodeToString(i + 4, i + 8)

                if (type == "tEXt" || type == "iTXt") {
                    val content = bytes.decodeToString(i + 8, i + 8 + length)
                    if (content.startsWith("chara") || content.startsWith("ccv3")) {
                        val base64Part = content.substringAfterLast('\u0000')
                            .trim()
                            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                        if (base64Part.isNotEmpty()) {
                            return Base64.decode(base64Part).decodeToString()
                        }
                    }
                }
                if (type == "IEND") return null
                i += 12 + length
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
         (b[o + 3].toInt() and 0xFF)
}
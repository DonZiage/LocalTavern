package chat.donzi.localtavern.utils

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object PngParser {

    // PNG signature (8 bytes); the chunk walker must never run on arbitrary
    // data (JPEGs, plain text files picked through the import dialog).
    private fun isPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        for (i in 0 until 8) {
            if (bytes[i] != signature[i]) return false
        }
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun extractSillyTavernCard(bytes: ByteArray): String? {
        if (!isPngSignature(bytes)) return null
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = readInt(bytes, i)
            // Long arithmetic: i + 8 + length + 4 can overflow Int when a
            // hostile chunk claims a length near Int.MAX_VALUE, wrapping
            // negative and bypassing the bounds check (the walker would then
            // read out of range).
            if (length < 0 || i.toLong() + 8 + length + 4 > bytes.size) return null

            val type = bytes.decodeToString(i + 4, i + 8)

            if (type == "tEXt" || type == "iTXt") {
                try {
                    val content = bytes.decodeToString(i + 8, i + 8 + length)
                    if (content.startsWith("chara") || content.startsWith("ccv3")) {
                        val base64Part = content.substringAfterLast('\u0000')
                            .trim()
                            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                        if (base64Part.isNotEmpty()) {
                            return Base64.decode(base64Part).decodeToString()
                        }
                    }
                } catch (_: Exception) {
                    // A malformed chunk (bad UTF-8, invalid base64) must not
                    // abort the scan: a valid chara chunk may follow.
                }
            }
            if (type == "IEND") return null
            i += 12 + length
        }
        return null
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
         (b[o + 3].toInt() and 0xFF)
}
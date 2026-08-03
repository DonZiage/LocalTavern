package chat.donzi.localtavern.utils

import java.util.zip.DataFormatException
import java.util.zip.Inflater

actual fun inflateDeflate(bytes: ByteArray, expectedSize: Int): ByteArray? {
    if (expectedSize < 0) return null
    val inflater = Inflater(true)
    try {
        inflater.setInput(bytes)
        val output = ByteArray(expectedSize)
        val written = inflater.inflate(output)
        if (written != expectedSize || !inflater.finished()) return null
        return output
    } catch (e: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}

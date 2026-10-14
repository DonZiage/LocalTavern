package chat.donzi.localtavern.utils

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

// SHA-256 for content-addressed blob keys (message images). Uses the same
// default cryptography provider as the sync layer, so hashes are identical
// on every platform.
object Hashing {

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    // The canonical key shape: exactly 64 lowercase hex chars (the output of
    // sha256Hex). Keys from any untrusted source (sync envelopes) MUST pass
    // this before touching the file system: the blob store resolves keys as
    // file names, so an unvalidated key is a path-traversal primitive.
    fun isValidSha256Hex(key: String): Boolean {
        if (key.length != 64) return false
        for (char in key) {
            if (char !in HEX_DIGITS) return false
        }
        return true
    }

    suspend fun sha256(data: ByteArray): ByteArray =
        CryptographyProvider.Default.get(SHA256).hasher().hash(data)

    suspend fun sha256Hex(data: ByteArray): String =
        sha256(data).toHexString()

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

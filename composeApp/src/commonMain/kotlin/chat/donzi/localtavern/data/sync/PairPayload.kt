package chat.donzi.localtavern.data.sync

import kotlinx.serialization.Serializable

// The content of the QR code shown by the host device during pairing. The
// receiver scans it to learn the host's address (and who it is); the PIN is
// deliberately NOT in the QR — it is read from the host's screen and typed by
// hand, so a photo of the QR alone can never complete a pairing.
@Serializable
data class PairPayload(
    val host: String,
    val port: Int,
    val deviceId: String,
    val deviceName: String
) {
    fun toQrText(): String = buildString {
        append("localtavern://pair")
        append("?h=").append(percentEncode(host))
        append("&p=").append(port)
        append("&d=").append(percentEncode(deviceId))
        append("&n=").append(percentEncode(deviceName))
    }

    companion object {
        fun fromQrText(text: String): PairPayload? {
            val normalized = text.trim()
            if (!normalized.startsWith("localtavern://pair")) return null
            val query = normalized.substringAfter('?', "").ifEmpty { return null }
            val params = mutableMapOf<String, String>()
            query.split('&').forEach { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "").let(::percentDecode)
                params[key] = value
            }
            val host = params["h"] ?: return null
            val port = params["p"]?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val deviceId = params["d"] ?: return null
            if (deviceId.isBlank() || host.isBlank()) return null
            return PairPayload(host = host, port = port, deviceId = deviceId, deviceName = params["n"] ?: "")
        }
    }
}

private fun percentEncode(text: String): String {
    val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val bytes = text.encodeToByteArray()
    val sb = StringBuilder(bytes.size)
    for (byte in bytes) {
        val c = (byte.toInt() and 0xFF).toChar()
        sb.append(if (c in allowed) c else "%${(byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')}")
    }
    return sb.toString()
}

private fun percentDecode(text: String): String {
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hex = text.substring(i + 1, i + 3)
            val value = hex.toIntOrNull(16)
            if (value != null) {
                bytes.add(value.toByte())
                i += 3
                continue
            }
        }
        // Percent-encoded bytes are decoded individually; any non-ASCII
        // character that survives here is encoded back into its UTF-8 bytes.
        if (c.code <= 0x7F) {
            bytes.add(c.code.toByte())
        } else {
            c.toString().encodeToByteArray().forEach { bytes.add(it) }
        }
        i++
    }
    return bytes.toByteArray().decodeToString()
}

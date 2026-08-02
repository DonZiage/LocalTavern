package chat.donzi.localtavern.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Persistent device identity for P2P sync: a stable device id and an X25519
// keypair. The private key never leaves the device (it is NOT in the synced
// tables); it lives in a platform-private location.
@Serializable
data class SyncIdentity(
    val deviceId: String,
    val deviceName: String,
    val privateKeyBase64: String,
    val publicKeyBase64: String
) {
    val privateKeyBytes: ByteArray get() = base64Decode(privateKeyBase64)
    val publicKeyBytes: ByteArray get() = base64Decode(publicKeyBase64)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun create(deviceName: String, crypto: SyncCrypto, deviceId: String? = null): SyncIdentity {
            val (privateKey, publicKey) = crypto.generateKeyPair()
            return SyncIdentity(
                deviceId = deviceId ?: randomDeviceId(),
                deviceName = deviceName,
                privateKeyBase64 = base64Encode(privateKey),
                publicKeyBase64 = base64Encode(publicKey)
            )
        }

        fun serialize(identity: SyncIdentity): ByteArray =
            json.encodeToString(identity).encodeToByteArray()

        fun deserialize(bytes: ByteArray): SyncIdentity? =
            runCatching { json.decodeFromString<SyncIdentity>(bytes.decodeToString()) }.getOrNull()
    }
}

private fun randomDeviceId(): String {
    // Compact lowercase id built from a random base64 (no dashes needed).
    val bytes = ByteArray(16)
    kotlin.random.Random.nextBytes(bytes)
    return base64Encode(bytes).replace("+", "a").replace("/", "b").replace("=", "").lowercase().take(24)
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun base64Encode(bytes: ByteArray): String =
    kotlin.io.encoding.Base64.encode(bytes)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun base64Decode(text: String): ByteArray =
    kotlin.io.encoding.Base64.decode(text)

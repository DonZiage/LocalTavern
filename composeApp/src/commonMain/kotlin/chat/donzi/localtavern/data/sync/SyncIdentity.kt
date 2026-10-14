package chat.donzi.localtavern.data.sync

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

// Persistent device identity for P2P sync: a stable device id and an X25519
// keypair. The private key never leaves the device (it is NOT in the synced
// tables); it lives in a platform-private location.
//
// deviceName is DISPLAY-ONLY metadata. deviceId and the X25519 keypair are
// the only identity/auth anchors: key derivation, pairing proofs, AAD
// bindings and cursor math all use them, never the name. Renaming keeps the
// id and keys untouched, so it can never affect pairing or sync state, and a
// name can never be used to impersonate a device.
@Serializable
data class SyncIdentity(
    val deviceId: String,
    val deviceName: String,
    val privateKeyBase64: String,
    val publicKeyBase64: String
) {
    val privateKeyBytes: ByteArray get() = base64Decode(privateKeyBase64)
    val publicKeyBytes: ByteArray get() = base64Decode(publicKeyBase64)

    /** Same device id and keypair, new display name. */
    fun withName(newName: String): SyncIdentity = copy(deviceName = newName)

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

        /** New identity with a unique friendly default display name. */
        suspend fun createDefault(crypto: SyncCrypto, deviceId: String? = null): SyncIdentity =
            create(DeviceName.generate(), crypto, deviceId)

        fun serialize(identity: SyncIdentity): ByteArray =
            json.encodeToString(identity).encodeToByteArray()

        fun deserialize(bytes: ByteArray): SyncIdentity? =
            runCatching { json.decodeFromString<SyncIdentity>(bytes.decodeToString()) }.getOrNull()

        /**
         * The display name to keep for an existing identity, or null when it
         * should be regenerated: blank/invalid names and the pre-rename
         * hardcoded default ("Device") are treated as "never set".
         */
        fun migratedName(existingName: String): String? {
            val sanitized = DeviceName.sanitize(existingName)
            return if (sanitized != null && sanitized != LEGACY_DEFAULT_NAME) sanitized else null
        }

        const val LEGACY_DEFAULT_NAME = "Device"
    }
}

// Display-name rules shared by every surface that shows or stores a device
// name (identity file, pairing payloads, peer store, discovery).
object DeviceName {
    const val MAX_LENGTH = 40

    // Human-friendly default names so paired-device lists and discovery stay
    // readable. The numeric suffix guards against duplicates on the LAN; it
    // is for human reference only, never an identity.
    private val ADJECTIVES = listOf(
        "Amber", "Azure", "Blazing", "Bold", "Brave", "Calm", "Clever", "Cobalt",
        "Comet", "Crimson", "Daring", "Dusky", "Eager", "Emerald", "Fabled",
        "Fierce", "Frosty", "Gentle", "Glowing", "Golden", "Grand", "Humble",
        "Jolly", "Kindly", "Lively", "Lucky", "Lunar", "Mellow", "Misty", "Nimble",
        "Noble", "Nova", "Quiet", "Radiant", "Rustic", "Sage", "Silent", "Sunny",
        "Swift", "Timber", "Violet", "Vivid", "Whimsy", "Wild", "Zen"
    )

    private val NOUNS = listOf(
        "Aspen", "Badger", "Birch", "Cedar", "Dahlia", "Ember", "Falcon", "Fjord",
        "Fox", "Grove", "Harbor", "Hawk", "Heron", "Ivy", "Jackal", "Juniper",
        "Kestrel", "Kettle", "Lantern", "Lark", "Lizard", "Lynx", "Marten",
        "Meadow", "Moose", "Narwhal", "North", "Oak", "Ocelot", "Otter", "Owl",
        "Panda", "Pine", "Porpoise", "Quill", "Quokka", "Raccoon", "Raven",
        "River", "Seal", "Sparrow", "Tiger", "Toucan", "Urchin", "Viper",
        "Walrus", "Wolf", "Wren", "Yeti", "Zebra"
    )

    fun generate(random: Random = Random.Default): String {
        val base = "${ADJECTIVES.random(random)} ${NOUNS.random(random)}"
        return if (random.nextBoolean()) "$base ${random.nextInt(0, 100)}" else base
    }

    /**
     * Returns a display-safe name, or null when nothing usable remains.
     * Control characters, zero-width/directional format characters and
     * bidi-override characters are stripped so a hostile name cannot render
     * misleadingly (e.g. visually reordered) in plain lists; whitespace runs
     * collapse and the result is capped at [MAX_LENGTH].
     */
    fun sanitize(input: String): String? {
        if (input.isBlank()) return null
        val cleaned = input
            .filterNot { it.isISOControl() || it.code in UNSAFE_CODEPOINTS }
            .trim()
            .replace(WHITESPACE, " ")
            .take(MAX_LENGTH)
            .trim()
        return cleaned.ifBlank { null }
    }

    private val WHITESPACE = Regex("\\s+")

    private val UNSAFE_CODEPOINTS =
        (0x200B..0x200F) + (0x202A..0x202E) + (0x2060..0x2069)
}

private fun randomDeviceId(): String {
    // Compact lowercase id built from a CSPRNG source (the device id anchors
    // pairing and sync identities, so it must not be guessable).
    val bytes = ByteArray(16)
    CryptographyRandom.Default.nextBytes(bytes)
    return base64Encode(bytes).replace("+", "a").replace("/", "b").replace("=", "").lowercase().take(24)
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun base64Encode(bytes: ByteArray): String =
    kotlin.io.encoding.Base64.encode(bytes)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun base64Decode(text: String): ByteArray =
    kotlin.io.encoding.Base64.decode(text)

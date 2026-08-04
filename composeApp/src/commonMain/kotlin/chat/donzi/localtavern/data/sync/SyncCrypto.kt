package chat.donzi.localtavern.data.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.BinarySize.Companion.bits

// Peer-to-peer sync cryptography:
//  - X25519 key agreement derives a shared secret between the two devices.
//  - HKDF-SHA256 stretches it into an AES-256-GCM key (never use raw
//    agreement output as a key directly).
//  - Payloads are authenticated-encrypted with a random nonce prepended;
//    the AEAD tag proves the sender holds the shared secret, i.e. it IS the
//    paired device, so no separate message signatures are needed.
//
// Forward secrecy: each exchange derives a fresh channel key from BOTH the
// static shared secret (authentication: only paired devices can derive it)
// and a per-exchange ephemeral X25519 shared secret. The scheme is
// static-ephemeral DH, so secrecy is one-sided: compromising the SENDER's
// static key does not reveal what that sender sent (its ephemeral private key
// is discarded), but compromising the RECEIVER's static key reveals
// everything that was sent to it. Compromising either key always reveals
// everything that device received.
//
// Pairing: the PIN never leaves the device in cleartext. The client sends
// HMAC-SHA256(PBKDF2(pin, nonce), deviceId|publicKey|nonce) instead; the host
// verifies with the PIN it displayed. A network sniffer cannot replay the
// proof against a swapped key without the PIN, and the PBKDF2 stretching
// (600k rounds, nonce as salt) multiplies the cost of an offline 10^6-candidate
// brute force of the 6-digit PIN by the full KDF per candidate — minutes on
// dedicated hardware rather than seconds, which is meaningful but not
// absolute. The out-of-band fingerprint comparison is the primary defense
// against an active man-in-the-middle.
//
// AES-GCM (not ChaCha20) is used because the JDK ChaCha20-Poly1305 provider
// rejects re-initializing a pooled cipher with a previously used (key, nonce)
// pair — which is exactly what decrypt-after-encrypt of the same payload does.
class SyncCrypto(
    private val provider: CryptographyProvider = CryptographyProvider.Default
) {
    companion object {
        private const val HKDF_SALT = "LocalTavern-Sync-1"
        private const val HKDF_INFO = "sync-channel"

        // Channel derivation domain: separate salt/info so ephemeral channel
        // keys are never derived in the same domain as the static secret.
        private const val CHANNEL_SALT = "LocalTavern-Sync-Channel-1"
        private const val CHANNEL_INFO = "sync-ephemeral-channel"

        private const val PAIRING_CONTEXT = "localtavern-pairing-v1"

        // PBKDF2 cost for the pairing PIN proof. 600k HMAC-SHA256 rounds
        // (OWASP's current floor for PBKDF2-SHA256) takes roughly 0.3-1 s on
        // current hardware — imperceptible for one pairing attempt, but it
        // multiplies every candidate of an offline 6-digit brute force by
        // that cost.
        private const val PAIRING_PBKDF2_ITERATIONS = 600_000

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }

    /** Generates a fresh X25519 keypair; returns (privateKey, publicKey) RAW bytes. */
    suspend fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val xdh = provider.get(XDH)
        val keyPair = xdh.keyPairGenerator(XDH.Curve.X25519).generateKey()
        val privateKey = keyPair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.RAW)
        val publicKey = keyPair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW)
        return privateKey to publicKey
    }

    /** Derives the 32-byte static shared secret from own private + peer public key. */
    suspend fun deriveSharedSecret(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val xdh = provider.get(XDH)
        val myPrivate = xdh.privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArray(XDH.PrivateKey.Format.RAW, myPrivateKey)
        val peerPublic = xdh.publicKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArray(XDH.PublicKey.Format.RAW, peerPublicKey)

        val rawSecret = myPrivate.sharedSecretGenerator().generateSharedSecretToByteArray(peerPublic)
        return provider.get(HKDF).secretDerivation(
            digest = SHA256,
            outputSize = 256.bits,
            salt = HKDF_SALT.encodeToByteArray(),
            info = HKDF_INFO.encodeToByteArray()
        ).deriveSecretToByteArray(rawSecret)
    }

    /**
     * Derives the per-exchange AES-256-GCM channel key from the static shared
     * secret (authentication) and an ephemeral X25519 shared secret (secrecy).
     * The ephemeral keypair is generated fresh for every exchange and its
     * private key discarded, which gives the channel forward secrecy.
     */
    suspend fun deriveChannelSecret(staticShared: ByteArray, ephemeralShared: ByteArray): ByteArray {
        return provider.get(HKDF).secretDerivation(
            digest = SHA256,
            outputSize = 256.bits,
            salt = CHANNEL_SALT.encodeToByteArray(),
            info = CHANNEL_INFO.encodeToByteArray()
        ).deriveSecretToByteArray(staticShared + ephemeralShared)
    }

    private suspend fun channelKey(sharedSecret: ByteArray): AES.GCM.Key {
        return provider.get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, sharedSecret)
    }

    /**
     * Encrypts [plaintext] with the channel key; the library prepends a fresh
     * random nonce, so the output is [nonce(12) | ciphertext | tag].
     * [associatedData] binds the payload to its context (sender/recipient ids)
     * so a message cannot be replayed against a different peer.
     */
    suspend fun encrypt(sharedSecret: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = channelKey(sharedSecret).cipher()
        return cipher.encrypt(
            plaintext = plaintext,
            associatedData = associatedData
        )
    }

    /** Decrypts a payload produced by [encrypt]; throws on tampering or wrong key. */
    suspend fun decrypt(sharedSecret: ByteArray, associatedData: ByteArray, payload: ByteArray): ByteArray {
        val cipher = channelKey(sharedSecret).cipher()
        return cipher.decrypt(
            ciphertext = payload,
            associatedData = associatedData
        )
    }

    /**
     * PIN proof for pairing: HMAC-SHA256(PBKDF2(pin, nonce), context|deviceId|publicKey|nonce).
     * The PIN itself never appears on the wire; the host recomputes and
     * constant-time-verifies this proof, and the client's nonce prevents
     * cross-session replay.
     *
     * The PIN is stretched with PBKDF2-HMAC-SHA256 ([PAIRING_PBKDF2_ITERATIONS]
     * rounds, the nonce as salt) before the outer HMAC, so a sniffer who
     * captures a proof must repeat the full KDF for every candidate PIN —
     * the 600k-round stretch plus the unique per-attempt salt makes the
     * offline search of the 6-digit space expensive (minutes on dedicated
     * hardware), though not impossible; the out-of-band fingerprint
     * comparison remains the primary MITM defense.
     */
    suspend fun pairingProof(pin: String, deviceId: String, publicKey: ByteArray, nonce: ByteArray): ByteArray {
        val stretchedPin = stretchPin(pin, nonce)
        return hmacSha256(stretchedPin, pairingData(deviceId, publicKey, nonce))
    }

    /** Constant-time verification of a [pairingProof]; true iff [pin] matches. */
    suspend fun verifyPairingProof(pin: String, deviceId: String, publicKey: ByteArray, nonce: ByteArray, proof: ByteArray): Boolean {
        val stretchedPin = stretchPin(pin, nonce)
        val expected = hmacSha256(stretchedPin, pairingData(deviceId, publicKey, nonce))
        return expected.size == proof.size && constantTimeEquals(expected, proof)
    }

    // PBKDF2-HMAC-SHA256 with the pairing nonce as the salt: the derivation
    // is per-attempt unique (fresh nonce) and deliberately expensive, which
    // is what turns the offline 10^6-candidate space of a 6-digit PIN into a
    // costly brute-force target (see the class comment for the honest math).
    private suspend fun stretchPin(pin: String, nonce: ByteArray): ByteArray {
        return provider.get(PBKDF2).secretDerivation(
            digest = SHA256,
            iterations = PAIRING_PBKDF2_ITERATIONS,
            outputSize = 256.bits,
            salt = nonce
        ).deriveSecretToByteArray(pin.encodeToByteArray())
    }

    /**
     * Short out-of-band fingerprint of a public key (SHA-256, first 16 hex
     * chars grouped like "A1B2-C3D4-E5F6-G7H8"). Both devices display the
     * fingerprint of the key they received during pairing; if the two strings
     * match, no man-in-the-middle swapped the keys.
     */
    suspend fun fingerprintOf(publicKey: ByteArray): String =
        formatFingerprint(sha256(publicKey))

    /**
     * Pairing fingerprint computed over BOTH exchanged keys in a canonical
     * (sorted) order, so the two pairing devices display the SAME string even
     * though each received a different key. A man-in-the-middle that swaps
     * either key changes the fingerprint, which the user comparing the two
     * screens will notice.
     */
    suspend fun pairingFingerprint(ownPublicKey: ByteArray, peerPublicKey: ByteArray): String {
        val (first, second) = if (compareBytes(ownPublicKey, peerPublicKey) <= 0) {
            ownPublicKey to peerPublicKey
        } else {
            peerPublicKey to ownPublicKey
        }
        return formatFingerprint(sha256(first + second))
    }

    private fun formatFingerprint(hash: ByteArray): String =
        hash.toHexString().take(16).uppercase().chunked(4).joinToString("-")

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val length = minOf(a.size, b.size)
        for (i in 0 until length) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun pairingData(deviceId: String, publicKey: ByteArray, nonce: ByteArray): ByteArray {
        return "$PAIRING_CONTEXT|$deviceId|${encodeBase64(publicKey)}|${encodeBase64(nonce)}".encodeToByteArray()
    }

    // HMAC-SHA256 built on the library's SHA-256 digest: the JDK provider's
    // HMAC sign/verify implementation in cryptography 0.6.0 is broken (even a
    // self-produced signature fails verification), and the construction below
    // is the standard RFC 2104 algorithm, so it is verifiable against known
    // test vectors and identical on every platform.
    internal suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val normalizedKey = if (key.size > blockSize) {
            digest(key)
        } else {
            ByteArray(blockSize).also { src -> key.copyInto(src) }
        }
        val ipad = normalizedKey.mapIndexed { index, byte -> (byte.toInt() xor 0x36).toByte() }.toByteArray()
        val opad = normalizedKey.mapIndexed { index, byte -> (byte.toInt() xor 0x5C).toByte() }.toByteArray()
        val inner = digest(ipad + data)
        return digest(opad + inner)
    }

    /** SHA-256 digest; exposed for HMAC verification tests. */
    internal suspend fun sha256(data: ByteArray): ByteArray =
        provider.get(SHA256).hasher().hash(data)

    private suspend fun digest(data: ByteArray): ByteArray = sha256(data)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        val length = maxOf(a.size, b.size)
        for (i in 0 until length) {
            val aByte = a.getOrElse(i) { 0 }.toInt()
            val bByte = b.getOrElse(i) { 0 }.toInt()
            diff = diff or (aByte xor bByte)
        }
        return diff == 0
    }

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun encodeBase64(bytes: ByteArray): String =
    kotlin.io.encoding.Base64.encode(bytes)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun decodeBase64(text: String): ByteArray =
    kotlin.io.encoding.Base64.decode(text)

// Authenticated-data prefix binding each encrypted payload to its sender,
// recipient AND exchange: a payload cannot be replayed against a different
// device pair or a different exchange (each request generates a fresh
// exchange id, so the AEAD tag is unique to one round trip even when the
// ciphertext itself is replayed verbatim).
internal fun aad(from: String, to: String, exchangeId: String): ByteArray =
    "localtavern-sync|from=$from|to=$to|ex=$exchangeId".encodeToByteArray()

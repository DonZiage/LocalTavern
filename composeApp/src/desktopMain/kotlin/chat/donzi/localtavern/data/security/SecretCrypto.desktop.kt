package chat.donzi.localtavern.data.security

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Desktop has no OS keychain available from the JVM, so protection is opt-in:
// the user picks a passphrase, a key is derived with PBKDF2-HMAC-SHA256, and
// API keys are encrypted with AES-256-GCM. The salt and a verifier blob are
// stored in ~/.localtavern/secret.v1; without the passphrase the keys stay
// encrypted and the app reports them as unavailable (never wiped).
actual fun createSecretCrypto(): SecretCrypto = DesktopPassphraseSecretCrypto()

class DesktopPassphraseSecretCrypto(
    private val dataDir: File = File(
        System.getProperty("user.home") ?: System.getProperty("user.dir"),
        ".localtavern"
    )
) : SecretCrypto {

    private companion object {
        const val VERIFIER_TEXT = "LocalTavernKeyV1"
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val PBKDF2_ITERATIONS = 200_000
        const val GCM_TAG_BITS = 128
    }

    private val rng = SecureRandom()
    private var key: SecretKey? = null

    override val isAvailable: Boolean get() = key != null
    override val isProtected: Boolean get() = protectionFile.exists()
    override val backendName: String get() = "Desktop passphrase"

    private val protectionFile: File get() = File(dataDir, "secret.v1")

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    override fun unlock(passphrase: String): Boolean {
        if (passphrase.isBlank()) return false
        val stored = protectionFile.readBytesOrNull() ?: return false
        if (stored.size < SALT_SIZE + IV_SIZE + 16) return false
        val salt = stored.copyOfRange(0, SALT_SIZE)
        val iv = stored.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val ciphertext = stored.copyOfRange(SALT_SIZE + IV_SIZE, stored.size)

        val candidate = runCatching {
            val derived = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derived, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()

        if (candidate == VERIFIER_TEXT) {
            key = deriveKey(passphrase, salt)
            return true
        }
        return false
    }

    override fun protect(passphrase: String) {
        require(passphrase.isNotBlank()) { "Passphrase must not be blank." }
        val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
        val derived = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derived)
        val ciphertext = cipher.doFinal(VERIFIER_TEXT.toByteArray(Charsets.UTF_8))

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw IllegalStateException("Could not create LocalTavern data directory: $dataDir")
        }
        protectionFile.writeBytes(salt + cipher.iv + ciphertext)
        key = derived
    }

    override fun removeProtection() {
        key = null
        protectionFile.delete()
    }

    override fun encrypt(plaintext: String): String? {
        val secretKey = key ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
        }.getOrNull()
    }

    override fun decrypt(payload: String): String? {
        val secretKey = key ?: return payload
        return runCatching {
            val bytes = Base64.getDecoder().decode(payload)
            if (bytes.size < IV_SIZE + 16) return@runCatching payload
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse { payload }
    }
}

private fun File.readBytesOrNull(): ByteArray? = try {
    if (exists()) readBytes() else null
} catch (_: Exception) {
    null
}

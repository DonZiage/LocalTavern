package chat.donzi.localtavern.data.security

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
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
// API keys are encrypted with AES-256-GCM. The salt, the KDF iteration count
// and a verifier blob are stored in ~/.localtavern/secret.v1; without the
// passphrase the keys stay encrypted and the app reports them as unavailable
// (never wiped).
//
// File format ("ltv2:"), written atomically with owner-only permissions:
//   magic "ltv2:" | iterations (4 bytes BE) | salt (16) | iv (12) | ciphertext+tag
// Files WITHOUT the magic are the legacy v1 layout (salt | iv | ciphertext)
// stretched with the fixed 200k iteration count. Storing the iteration count
// with the data means the KDF cost can be raised in a future release without
// invalidating existing protection files.
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
        // KDF cost for NEW protection files. The count is stored in the file,
        // so existing files keep their own count and remain unlockable after
        // this constant changes. 600k is OWASP's current floor for
        // PBKDF2-HMAC-SHA256 (an offline attacker has no rate limit, so the
        // KDF strength is the only defense).
        const val PBKDF2_ITERATIONS = 600_000
        // The fixed cost of legacy v1 files (pre-format-versioning releases).
        const val LEGACY_PBKDF2_ITERATIONS = 200_000
        const val GCM_TAG_BITS = 128
        val MAGIC = "ltv2:".encodeToByteArray()
        const val MIN_NEW_FILE_SIZE = 5 + 4 + 16 + 12 + 16
        const val MIN_LEGACY_FILE_SIZE = 16 + 12 + 16
    }

    private val rng = SecureRandom()
    private var key: SecretKey? = null

    override val isAvailable: Boolean get() = key != null
    override val isProtected: Boolean get() = protectionFile.exists()
    override val backendName: String get() = "Desktop passphrase"

    private val protectionFile: File get() = File(dataDir, "secret.v1")

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    override fun unlock(passphrase: String): Boolean {
        if (passphrase.isBlank()) return false
        val stored = protectionFile.readBytesOrNull() ?: return false
        val iterations: Int
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        if (stored.size >= MIN_NEW_FILE_SIZE && stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            val pos = MAGIC.size
            iterations = ((stored[pos].toInt() and 0xFF) shl 24) or
                ((stored[pos + 1].toInt() and 0xFF) shl 16) or
                ((stored[pos + 2].toInt() and 0xFF) shl 8) or
                (stored[pos + 3].toInt() and 0xFF)
            salt = stored.copyOfRange(pos + 4, pos + 4 + SALT_SIZE)
            iv = stored.copyOfRange(pos + 4 + SALT_SIZE, pos + 4 + SALT_SIZE + IV_SIZE)
            ciphertext = stored.copyOfRange(pos + 4 + SALT_SIZE + IV_SIZE, stored.size)
        } else if (stored.size >= MIN_LEGACY_FILE_SIZE) {
            // Legacy v1 layout: fixed 200k iteration cost.
            iterations = LEGACY_PBKDF2_ITERATIONS
            salt = stored.copyOfRange(0, SALT_SIZE)
            iv = stored.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            ciphertext = stored.copyOfRange(SALT_SIZE + IV_SIZE, stored.size)
        } else {
            return false
        }

        val candidate = runCatching {
            val derived = deriveKey(passphrase, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derived, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()

        if (candidate == VERIFIER_TEXT) {
            // GCM tag verification already succeeded above, so this comparison
            // cannot be a timing oracle for wrong passphrases.
            key = deriveKey(passphrase, salt, iterations)
            return true
        }
        return false
    }

    override fun protect(passphrase: String) {
        require(passphrase.isNotBlank()) { "Passphrase must not be blank." }
        val salt = ByteArray(SALT_SIZE).also { rng.nextBytes(it) }
        val derived = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derived)
        val ciphertext = cipher.doFinal(VERIFIER_TEXT.toByteArray(Charsets.UTF_8))

        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw IllegalStateException("Could not create LocalTavern data directory: $dataDir")
        }
        val iterations = PBKDF2_ITERATIONS
        val header = ByteArray(MAGIC.size + 4) { 0 }
        MAGIC.copyInto(header)
        header[MAGIC.size] = ((iterations ushr 24) and 0xFF).toByte()
        header[MAGIC.size + 1] = ((iterations ushr 16) and 0xFF).toByte()
        header[MAGIC.size + 2] = ((iterations ushr 8) and 0xFF).toByte()
        header[MAGIC.size + 3] = (iterations and 0xFF).toByte()
        writeSecret(header + salt + cipher.iv + ciphertext)
        key = derived
    }

    override fun removeProtection() {
        key = null
        protectionFile.delete()
    }

    override fun lock() {
        // Forget the derived key; the protection file stays, so secrets
        // remain encrypted until the passphrase is entered again.
        key = null
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

    // Writes the protection blob atomically (temp + rename, so a crash
    // mid-write never leaves a corrupt file that would brick the passphrase)
    // and restricts it to the owning user: the file carries the salt and
    // verifier, and its contents should not be readable by other local users.
    private fun writeSecret(bytes: ByteArray) {
        val tmp = File(dataDir, "secret.v1.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.setPosixFilePermissions(
                tmp.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem: best-effort via the readable/writable flags.
            tmp.setReadable(false, false)
            tmp.setWritable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
        } catch (_: java.io.IOException) {
            // Filesystem refused the attribute write (e.g. a network mount):
            // the protection content itself is still written, best-effort.
            tmp.setReadable(false, false)
            tmp.setWritable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
        }
        val target = protectionFile.toPath()
        try {
            Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun File.readBytesOrNull(): ByteArray? = try {
    if (exists()) readBytes() else null
} catch (_: Exception) {
    null
}

package chat.donzi.localtavern.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A fake backend that actually encrypts: base64-reverses the plaintext and
// marks it with a prefix, so round-trip, prefix handling and failure paths are
// exercised without platform keystores.
private class FakeCrypto(
    override var isAvailable: Boolean = true,
    override var isProtected: Boolean = true,
    private val failDecrypt: Boolean = false
) : SecretCrypto {
    override val backendName: String get() = "Fake"
    override fun encrypt(plaintext: String): String? = if (isAvailable) plaintext.reversed() else null
    override fun decrypt(payload: String): String? =
        if (failDecrypt) payload else payload.reversed()
    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) = Unit
    override fun removeProtection() = Unit
    override fun lock() {
        isAvailable = false
    }
}

class ApiKeyCipherTest {

    @Test
    fun encryptForStorage_marksEncryptedKeys() {
        val cipher = ApiKeyCipher(FakeCrypto())
        val stored = cipher.encryptForStorage("sk-secret")
        assertTrue(stored!!.startsWith("ltv1:"), "Encrypted keys must carry the ltv1: marker")
        assertNotEquals("sk-secret", stored)
    }

    @Test
    fun decryptFromStorage_roundTrips() {
        val cipher = ApiKeyCipher(FakeCrypto())
        val stored = cipher.encryptForStorage("sk-secret")!!
        assertEquals("sk-secret", cipher.decryptFromStorage(stored))
    }

    @Test
    fun legacyPlaintext_passesThrough() {
        val cipher = ApiKeyCipher(FakeCrypto())
        assertEquals("sk-legacy", cipher.decryptFromStorage("sk-legacy"))
    }

    @Test
    fun blankKeys_stayBlank() {
        val cipher = ApiKeyCipher(FakeCrypto())
        assertNull(cipher.encryptForStorage(null))
        assertEquals("", cipher.encryptForStorage(""))
    }

    @Test
    fun unavailableAndUnconfiguredCrypto_leavesKeysPlaintext() {
        // No backend configured at all (e.g. desktop before the passphrase is
        // set): there is nothing to encrypt with, so plaintext storage is the
        // designed behavior and the UI reports "not protected".
        val cipher = ApiKeyCipher(FakeCrypto(isAvailable = false, isProtected = false))
        assertEquals("sk-secret", cipher.encryptForStorage("sk-secret"))
    }

    @Test
    fun unavailableButProtectedCrypto_failsClosed() {
        // The backend IS configured (protection claimed) but encryption fails:
        // storing plaintext would silently contradict the protection promise,
        // so the write must fail closed instead.
        val cipher = ApiKeyCipher(FakeCrypto(isAvailable = false, isProtected = true))
        assertNull(cipher.encryptForStorage("sk-secret"))
    }

    @Test
    fun undecryptableBlob_returnsRawStoredValue() {
        val cipher = ApiKeyCipher(FakeCrypto(failDecrypt = true))
        val stored = "ltv1:abcdef"
        // Must return the original marked blob (never null), so a later save
        // does not wipe or double-encrypt it.
        assertEquals(stored, cipher.decryptFromStorage(stored))
    }
}

class DesktopPassphraseSecretCryptoTest {

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("localtavern-crypto").toFile()

    @Test
    fun protect_unlock_roundTrips() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            assertFalse(crypto.isAvailable)
            assertFalse(crypto.isProtected)

            crypto.protect("correct horse")
            assertTrue(crypto.isAvailable)
            assertTrue(crypto.isProtected)

            // Wrong passphrase must not unlock.
            val locked = DesktopPassphraseSecretCrypto(dir)
            assertFalse(locked.unlock("wrong passphrase"))
            assertFalse(locked.isAvailable)

            // Correct passphrase unlocks and decrypts blobs from the first run.
            assertTrue(locked.unlock("correct horse"))
            assertTrue(locked.isAvailable)
            val blob = crypto.encrypt("sk-secret")!!
            assertEquals("sk-secret", locked.decrypt(blob))

            locked.removeProtection()
            assertFalse(locked.isAvailable)
            assertFalse(locked.isProtected)
            assertFalse(java.io.File(dir, "secret.v1").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun wrongPassphrase_decryptReturnsRawPayload() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            val encrypted = crypto.encrypt("secret")!!

            val locked = DesktopPassphraseSecretCrypto(dir)
            // Locked (no key): decryption must return the raw payload, not null.
            assertEquals(encrypted, locked.decrypt(encrypted))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blankPassphrase_rejected() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            assertFalse(crypto.unlock(""))
            assertFailsWith<IllegalArgumentException> { crypto.protect("   ") }
            assertFalse(crypto.isAvailable)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun protectionFile_storedWithOwnerOnlyPermissions() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            val path = java.io.File(dir, "secret.v1").toPath()
            val perms = java.nio.file.Files.getPosixFilePermissions(path)
            // No permissions for group or others: the salt/verifier blob must
            // not be readable by other local users.
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyV1File_stillUnlocks() {
        // Simulate a pre-format-versioning protection file: salt | iv |
        // ciphertext with 200k iterations, no "ltv2:" header. Upgrading must
        // not lock users out of their existing passphrase.
        val dir = tempDir()
        try {
            val legacy = java.security.SecureRandom()
            val salt = ByteArray(16).also { legacy.nextBytes(it) }
            val iv = ByteArray(12).also { legacy.nextBytes(it) }
            val key = deriveLegacyKey("pass", salt)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal("LocalTavernKeyV1".toByteArray())
            java.io.File(dir, "secret.v1").writeBytes(salt + iv + ciphertext)

            val crypto = DesktopPassphraseSecretCrypto(dir)
            assertTrue(crypto.unlock("pass"), "A legacy v1 file must unlock after the format upgrade")
            val blob = crypto.encrypt("sk")!!
            assertEquals("sk", crypto.decrypt(blob), "An unlocked legacy backend must encrypt and decrypt")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newFormatFile_storesIterationCountAndRoundTrips() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            val bytes = java.io.File(dir, "secret.v1").readBytes()
            val magic = "ltv2:".encodeToByteArray()
            assertTrue(bytes.size >= magic.size + 4, "New-format file must carry the ltv2: header")
            assertTrue(bytes.copyOfRange(0, magic.size).contentEquals(magic), "New-format file must be marked with ltv2:")
            val iters = ((bytes[magic.size].toInt() and 0xFF) shl 24) or
                ((bytes[magic.size + 1].toInt() and 0xFF) shl 16) or
                ((bytes[magic.size + 2].toInt() and 0xFF) shl 8) or
                (bytes[magic.size + 3].toInt() and 0xFF)
            assertEquals(600_000, iters, "New files must store the current iteration count")

            // A fresh instance must unlock using the count stored in the file.
            val locked = DesktopPassphraseSecretCrypto(dir)
            assertTrue(locked.unlock("pass"))
            val blob = locked.encrypt("sk")!!
            assertEquals("sk", locked.decrypt(blob), "The stored iteration count must derive the same key")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lock_forgetsKeyWithoutDeletingProtection() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            val encrypted = crypto.encrypt("secret")!!

            crypto.lock()
            assertFalse(crypto.isAvailable, "lock() must drop the derived key")
            assertTrue(crypto.isProtected, "lock() must keep the protection file")
            assertEquals(encrypted, crypto.decrypt(encrypted), "A locked backend must not decrypt")
            assertFalse(crypto.unlock("wrong"), "Wrong passphrase stays wrong after a lock")
            assertTrue(crypto.unlock("pass"), "The correct passphrase re-unlocks after a lock")
            assertEquals("secret", crypto.decrypt(encrypted), "Blobs encrypted before the lock decrypt after re-unlock")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun deriveLegacyKey(passphrase: String, salt: ByteArray): javax.crypto.SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 200_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}

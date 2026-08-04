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
}

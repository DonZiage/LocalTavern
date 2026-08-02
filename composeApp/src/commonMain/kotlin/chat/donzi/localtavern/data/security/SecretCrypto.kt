package chat.donzi.localtavern.data.security

// Platform secret store used to protect API keys at rest.
//
// - Android: AndroidKeyStore (non-exportable AES key, always available).
// - iOS: Keychain-held master key + CommonCrypto AES (always available).
// - Desktop: optional passphrase-derived key (opt-in; PBKDF2 + AES/GCM).
//
// The database stores "ltv1:<base64(iv||ciphertext)>" blobs; plaintext keys
// written before protection (or on platforms without a store) are left as-is
// and are transparently readable.
interface SecretCrypto {
    /** True when this backend can encrypt/decrypt right now. */
    val isAvailable: Boolean

    /** True when a secret store/passphrase is configured (vs. plaintext). */
    val isProtected: Boolean

    /** Display name, e.g. "Android Keystore" or "Desktop passphrase". */
    val backendName: String

    /** Encrypts plaintext into a base64 payload; null on failure/unavailable. */
    fun encrypt(plaintext: String): String?

    /** Decrypts a payload. On failure the raw payload is returned so stored
     *  secrets are never lost or double-encrypted by a later save. */
    fun decrypt(payload: String): String?

    /** Desktop only: verifies a passphrase and unlocks the derived key. */
    fun unlock(passphrase: String): Boolean

    /** Desktop only: derives a new key from a passphrase and persists it. */
    fun protect(passphrase: String)

    /** Desktop only: forgets the key and deletes the persisted config. */
    fun removeProtection()
}

expect fun createSecretCrypto(): SecretCrypto

// Marker prefix distinguishing encrypted blobs from legacy plaintext keys.
private const val ENCRYPTED_PREFIX = "ltv1:"

class ApiKeyCipher(private val crypto: SecretCrypto) {

    val backendName: String get() = crypto.backendName
    val isAvailable: Boolean get() = crypto.isAvailable
    val isProtected: Boolean get() = crypto.isProtected

    fun unlock(passphrase: String): Boolean = crypto.unlock(passphrase)
    fun protect(passphrase: String) = crypto.protect(passphrase)
    fun removeProtection() = crypto.removeProtection()

    /** Encrypts a key for storage; blank keys stay blank, and when the crypto
     *  backend is unavailable the plaintext is stored unchanged. */
    fun encryptForStorage(plaintext: String?): String? {
        if (plaintext.isNullOrBlank()) return plaintext
        return crypto.encrypt(plaintext)?.let { ENCRYPTED_PREFIX + it } ?: plaintext
    }

    /** Decrypts a stored key; legacy plaintext passes through unchanged, and
     *  an undecryptable blob is returned as-is (never nulled out). */
    fun decryptFromStorage(stored: String?): String? {
        if (stored == null || !stored.startsWith(ENCRYPTED_PREFIX)) return stored
        val decrypted = crypto.decrypt(stored.removePrefix(ENCRYPTED_PREFIX))
        // decrypt() already falls back to the raw payload on failure; keep the
        // marker off so a re-save of an unreadable blob does not double-encrypt.
        return if (decrypted == stored.removePrefix(ENCRYPTED_PREFIX)) stored else decrypted
    }
}

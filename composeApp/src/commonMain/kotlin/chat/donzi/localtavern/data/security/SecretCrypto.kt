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

    /** Desktop only: forgets the derived key so secrets are locked again
     *  until unlock() succeeds (idle auto-lock, app exit). No-op on
     *  platforms whose backend is always available. */
    fun lock()
}

expect fun createSecretCrypto(): SecretCrypto

// Marker prefix distinguishing encrypted blobs from legacy plaintext keys.
// Exposed so other layers can recognize an unreadable stored blob without
// round-tripping it through the backend (see reencryptAllApiKeys).
internal const val EncryptedMarkerPrefix = "ltv1:"

class ApiKeyCipher(private val crypto: SecretCrypto) {

    val backendName: String get() = crypto.backendName
    val isAvailable: Boolean get() = crypto.isAvailable
    val isProtected: Boolean get() = crypto.isProtected

    fun unlock(passphrase: String): Boolean = crypto.unlock(passphrase)
    fun protect(passphrase: String) = crypto.protect(passphrase)
    fun removeProtection() = crypto.removeProtection()
    fun lock() = crypto.lock()

    /**
     * Encrypts a key for storage; blank keys stay blank.
     *
     * A plaintext fallback exists ONLY while no secret store is configured
     * (desktop before the passphrase is set, or a platform without a store):
     * there is nothing to encrypt with, so the key is stored as-is and the
     * UI reports "not protected". Once the backend IS configured
     * ([SecretCrypto.isProtected]) but encryption fails (lost keystore key,
     * corrupted keychain), this returns null instead of silently storing the
     * plaintext: a key must never sit on disk unencrypted while the UI
     * claims it is protected.
     */
    fun encryptForStorage(plaintext: String?): String? {
        if (plaintext.isNullOrBlank()) return plaintext
        val encrypted = crypto.encrypt(plaintext) ?: return if (crypto.isProtected) null else plaintext
        return EncryptedMarkerPrefix + encrypted
    }

    /** Decrypts a stored key; legacy plaintext passes through unchanged, and
     *  an undecryptable blob is returned as-is (never nulled out). */
    fun decryptFromStorage(stored: String?): String? {
        if (stored == null || !stored.startsWith(EncryptedMarkerPrefix)) return stored
        val decrypted = crypto.decrypt(stored.removePrefix(EncryptedMarkerPrefix))
        // decrypt() already falls back to the raw payload on failure; keep the
        // marker off so a re-save of an unreadable blob does not double-encrypt.
        return if (decrypted == stored.removePrefix(EncryptedMarkerPrefix)) stored else decrypted
    }

    /**
     * Stored form -> portable (plaintext) form for sync envelopes.
     *
     * Stored keys are encrypted under THIS device's backend (Keystore,
     * Keychain or passphrase), so a synced blob would be undecryptable on any
     * other device. The sync envelope is end-to-end encrypted, so the key can
     * safely travel as plaintext inside it; the receiving device re-encrypts
     * it under its own backend.
     *
     * Returns null when the key cannot be read here (locked passphrase): the
     * key is withheld from the sync rather than shipping a blob the peer can
     * never decrypt.
     */
    fun toPortableForm(stored: String?): String? {
        val portable = decryptFromStorage(stored) ?: return null
        // Still carries the encrypted marker: this device could not decrypt
        // it, so no other device can either. Withhold it.
        if (portable.startsWith(EncryptedMarkerPrefix)) return null
        return portable
    }

    /**
     * Portable (plaintext) form -> stored form for THIS device.
     *
     * The plaintext key is encrypted under the local backend, falling back to
     * plaintext when the backend is unavailable (same semantics as a local
     * save). A string that is still marked as encrypted was produced by an
     * older peer version that shipped its local ciphertext: it is unusable
     * here, and null is returned so the caller keeps its existing key instead
     * of storing an undecryptable one.
     */
    fun fromPortableForm(portable: String?): String? {
        if (portable == null || portable.startsWith(EncryptedMarkerPrefix)) return null
        return encryptForStorage(portable)
    }
}

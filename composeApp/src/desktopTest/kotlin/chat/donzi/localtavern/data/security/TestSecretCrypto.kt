package chat.donzi.localtavern.data.security

// No-op crypto for tests: keys pass through untouched.
class TestSecretCrypto : SecretCrypto {
    override val isAvailable: Boolean get() = true
    override val isProtected: Boolean get() = true
    override val backendName: String get() = "Test"
    override fun encrypt(plaintext: String): String? = plaintext
    override fun decrypt(payload: String): String? = payload
    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) = Unit
    override fun removeProtection() = Unit
}

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
    override fun lock() = Unit
}

// Reversible fake crypto for tests that need genuine encrypt/decrypt
// round-trips (e.g. sync key portability: a stored blob that the backend can
// actually decrypt must travel as plaintext).
class ReversibleTestSecretCrypto : SecretCrypto {
    override val isAvailable: Boolean get() = true
    override val isProtected: Boolean get() = true
    override val backendName: String get() = "ReversibleTest"
    override fun encrypt(plaintext: String): String? = "X($plaintext)"
    override fun decrypt(payload: String): String? =
        if (payload.startsWith("X(") && payload.endsWith(")")) payload.removeSurrounding("X(", ")") else payload
    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) = Unit
    override fun removeProtection() = Unit
    override fun lock() = Unit
}

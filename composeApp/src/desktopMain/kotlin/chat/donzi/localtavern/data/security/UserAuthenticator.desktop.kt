package chat.donzi.localtavern.data.security

// Desktop has no OS unlock prompt of its own: the passphrase gate IS the
// unlock mechanism, so the authenticator is a no-op and the gate's
// passphrase flows take over.
actual fun createUserAuthenticator(): UserAuthenticator = NoopUserAuthenticator

object NoopUserAuthenticator : UserAuthenticator {
    override val isLockConfigured: Boolean get() = false
    override suspend fun authenticate(): Boolean? = null
}

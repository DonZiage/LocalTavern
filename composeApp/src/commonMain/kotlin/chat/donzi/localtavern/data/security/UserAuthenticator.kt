package chat.donzi.localtavern.data.security

// Platform user authentication used by the startup gate on mobile: the app
// asks for the device's own unlock method (PIN, password or fingerprint)
// before the main UI opens, mirroring the desktop passphrase gate.
//
// Desktop returns a no-op authenticator: the passphrase gate covers it.
//
// Session authorization model (same ceiling as the desktop passphrase): the
// OS unlock credential IS the session credential. The prompt is answered by
// the OS itself, so the app never sees the PIN/password, and the OS enforces
// its own retry limits.
interface UserAuthenticator {
    /** True when the device has an unlock method configured (PIN, password,
     *  pattern or biometrics) that the app can prompt with. */
    val isLockConfigured: Boolean

    /**
     * Shows the OS unlock prompt.
     * - null: no unlock method available (nothing could be prompted with)
     * - true: the user authenticated successfully
     * - false: authentication failed or was cancelled
     */
    suspend fun authenticate(): Boolean?
}

expect fun createUserAuthenticator(): UserAuthenticator

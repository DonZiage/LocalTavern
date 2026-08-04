package chat.donzi.localtavern.ui

import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.DesktopPassphraseSecretCrypto
import chat.donzi.localtavern.data.security.SecretCrypto
import chat.donzi.localtavern.data.security.UserAuthenticator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// Gate flows are tested against the real desktop backend (temp dir) so the
// protect/unlock/reencrypt wiring is exercised, not just stubbed.
class SecretGateStateTest {

    private class FakeBackend(
        override var isAvailable: Boolean,
        override var isProtected: Boolean,
        val backendNameValue: String = "Some keystore"
    ) : SecretCrypto {
        override val backendName: String get() = backendNameValue
        override fun encrypt(plaintext: String): String? = plaintext
        override fun decrypt(payload: String): String? = payload
        override fun unlock(passphrase: String): Boolean = true
        override fun protect(passphrase: String) = Unit
        override fun removeProtection() {
            isProtected = false
            isAvailable = false
        }
        override fun lock() {
            isAvailable = false
        }
    }

    // Fake device authenticator: lockConfigured decides whether the gate
    // applies at all; result is what the next prompt answers.
    private class FakeUserAuthenticator(
        override val isLockConfigured: Boolean,
        var result: Boolean? = null
    ) : UserAuthenticator {
        var authenticateCalls = 0
        override suspend fun authenticate(): Boolean? {
            authenticateCalls++
            return result
        }
    }

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("localtavern-gate").toFile()

    @Test
    fun nonPassphraseBackendWithoutDeviceLock_isOpenImmediately() {
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = true, isProtected = true)),
            FakeUserAuthenticator(isLockConfigured = false)
        )
        assertEquals(SecretGateMode.Open, state.mode)
    }

    @Test
    fun nonPassphraseBackendWithDeviceLock_isUnlock() {
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = true, isProtected = true)),
            FakeUserAuthenticator(isLockConfigured = true)
        )
        assertEquals(SecretGateMode.Unlock, state.mode, "A locked device must gate the app open")
    }

    @Test
    fun platformUnlock_success_opens() = runTest {
        val auth = FakeUserAuthenticator(isLockConfigured = true, result = true)
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = true, isProtected = true)),
            auth
        )
        state.submitPlatformUnlock()
        assertEquals(SecretGateMode.Open, state.mode)
        assertEquals(1, auth.authenticateCalls)
        assertNull(state.error)
    }

    @Test
    fun platformUnlock_cancelledOrFailed_staysOnGate() = runTest {
        val auth = FakeUserAuthenticator(isLockConfigured = true, result = false)
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = true, isProtected = true)),
            auth
        )
        state.submitPlatformUnlock()
        assertEquals(SecretGateMode.Unlock, state.mode, "A cancelled prompt must not open the app")
        assertEquals("Authentication failed or cancelled.", state.error)
    }

    @Test
    fun platformUnlock_unavailableDevice_opensDirectly() = runTest {
        // The lock method disappeared (or the prompt cannot be shown): never
        // trap the user behind a dead button.
        val auth = FakeUserAuthenticator(isLockConfigured = true, result = null)
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = true, isProtected = true)),
            auth
        )
        state.submitPlatformUnlock()
        assertEquals(SecretGateMode.Open, state.mode)
    }

    @Test
    fun platformUnlock_ignoredOnPassphraseBackend() = runTest {
        val auth = FakeUserAuthenticator(isLockConfigured = true, result = true)
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = false, isProtected = false, backendNameValue = "Desktop passphrase")),
            auth
        )
        assertEquals(SecretGateMode.Setup, state.mode)
        state.submitPlatformUnlock()
        assertEquals(SecretGateMode.Setup, state.mode, "The passphrase gate must ignore platform auth")
        assertEquals(0, auth.authenticateCalls)
    }

    @Test
    fun freshDesktop_isSetup() {
        val state = SecretGateState(
            ApiKeyCipher(FakeBackend(isAvailable = false, isProtected = false, backendNameValue = "Desktop passphrase")),
            FakeUserAuthenticator(isLockConfigured = false)
        )
        assertEquals(SecretGateMode.Setup, state.mode)
    }

    @Test
    fun setupRejectsShortPassphrase() = runTest {
        val dir = tempDir()
        try {
            var protectedCalled = 0
            val state = SecretGateState(
                ApiKeyCipher(DesktopPassphraseSecretCrypto(dir)),
                FakeUserAuthenticator(isLockConfigured = false),
                onProtected = { protectedCalled++ }
            )
            assertEquals(SecretGateMode.Setup, state.mode)

            state.submitSetup("abc", "abc")
            assertEquals(SecretGateMode.Setup, state.mode, "A short passphrase must be rejected")
            assertEquals("Passphrase must be at least 8 characters.", state.error)
            assertEquals(0, protectedCalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setupRejectsWeakPassphrase() = runTest {
        val dir = tempDir()
        try {
            var protectedCalled = 0
            val state = SecretGateState(
                ApiKeyCipher(DesktopPassphraseSecretCrypto(dir)),
                FakeUserAuthenticator(isLockConfigured = false),
                onProtected = { protectedCalled++ }
            )
            assertEquals(SecretGateMode.Setup, state.mode)

            // Meets the old 6-char length rule but fails the composition and
            // digit rules ("correct horse" has no uppercase, digit or symbol).
            state.submitSetup("correct horse", "correct horse")
            assertEquals(SecretGateMode.Setup, state.mode, "A weak passphrase must be rejected by the policy")
            assertEquals("Passphrase must contain at least one uppercase letter.", state.error)
            assertEquals(0, protectedCalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setupRejectsMismatchedConfirmation() = runTest {
        val dir = tempDir()
        try {
            var protectedCalled = 0
            val state = SecretGateState(
                ApiKeyCipher(DesktopPassphraseSecretCrypto(dir)),
                FakeUserAuthenticator(isLockConfigured = false),
                onProtected = { protectedCalled++ }
            )
            state.submitSetup("Saf3-Wolf!", "different")
            assertEquals(SecretGateMode.Setup, state.mode)
            assertEquals("Passphrases do not match.", state.error)
            assertEquals(0, protectedCalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setupProtectsAndReencryptsThenOpens() = runTest {
        val dir = tempDir()
        try {
            var protectedCalled = 0
            val state = SecretGateState(
                ApiKeyCipher(DesktopPassphraseSecretCrypto(dir)),
                FakeUserAuthenticator(isLockConfigured = false),
                onProtected = { protectedCalled++ }
            )
            state.submitSetup("Saf3-Wolf!", "Saf3-Wolf!")
            assertEquals(SecretGateMode.Open, state.mode, "A valid setup must open the gate")
            assertEquals(1, protectedCalled, "Existing keys must be re-encrypted after protect")
            assertNull(state.error)
            assertTrue(state.mode == SecretGateMode.Open)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lockedBackend_isUnlock() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            crypto.lock()
            val state = SecretGateState(ApiKeyCipher(crypto), FakeUserAuthenticator(isLockConfigured = false))
            assertEquals(SecretGateMode.Unlock, state.mode)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unlockWrongPassphrase_decrementsAttemptsThenLocksOut() = runTest {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            crypto.lock()
            val state = SecretGateState(ApiKeyCipher(crypto), FakeUserAuthenticator(isLockConfigured = false))
            assertEquals(SecretGateMode.Unlock, state.mode)

            repeat(SecretGateState.MAX_ATTEMPTS - 1) { remaining ->
                state.submitUnlock("wrong")
                assertEquals(SecretGateMode.Unlock, state.mode, "Wrong guesses before the cap must stay in Unlock")
                assertEquals(SecretGateState.MAX_ATTEMPTS - remaining - 1, state.attemptsRemaining)
                assertTrue(state.error!!.contains("attempt"), "The user must see how many attempts remain")
            }

            state.submitUnlock("wrong")
            assertEquals(SecretGateMode.LockedOut, state.mode, "The final wrong guess must lock the gate")
            assertEquals(0, state.attemptsRemaining)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unlockCorrectPassphrase_opensAndResetsAttempts() = runTest {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            crypto.lock()
            val state = SecretGateState(ApiKeyCipher(crypto), FakeUserAuthenticator(isLockConfigured = false))

            state.submitUnlock("wrong")
            assertEquals(SecretGateState.MAX_ATTEMPTS - 1, state.attemptsRemaining)
            state.submitUnlock("pass")
            assertEquals(SecretGateMode.Open, state.mode)
            assertEquals(SecretGateState.MAX_ATTEMPTS, state.attemptsRemaining, "A successful unlock must reset the counter")
            assertNull(state.error)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lock_returnsToUnlock() {
        val dir = tempDir()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dir)
            crypto.protect("pass")
            val state = SecretGateState(ApiKeyCipher(crypto), FakeUserAuthenticator(isLockConfigured = false))
            assertEquals(SecretGateMode.Open, state.mode)

            state.lock()
            assertEquals(SecretGateMode.Unlock, state.mode, "Idle auto-lock must flip the gate back to Unlock")
            assertFalse(crypto.isAvailable)
            assertTrue(crypto.isProtected, "Auto-lock must not remove the protection file")
        } finally {
            dir.deleteRecursively()
        }
    }
}

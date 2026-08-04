package chat.donzi.localtavern.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.UserAuthenticator

// State machine for the unlock gate that runs BEFORE the main UI is shown.
// Kept as a plain class (not a composable) so the flows are unit-testable
// without a Compose harness.
//
// Two unlock mechanisms, one per platform family:
// - Desktop: a passphrase (Setup on first run, Unlock with attempt
//   limiting afterwards).
// - Android/iOS: the device's own unlock method (PIN/password/fingerprint)
//   via [UserAuthenticator]. The OS shows the prompt and enforces its own
//   retry limits; the app only learns the binary result.
//
// Modes:
// - Setup:      no passphrase configured yet (desktop first run, or after
//               protection was explicitly removed). The user MUST create
//               one: desktop API keys are plaintext otherwise.
// - Unlock:     the backend is configured but not open (desktop: derived key
//               not in memory; mobile: the user has not yet passed the
//               device unlock prompt).
// - LockedOut:  too many wrong passphrases this session (desktop only). The
//               in-memory counter cannot be reset without restarting the
//               app, so an attacker typing guesses is stalled permanently
//               instead of looping.
// - Open:       the backend is open; the main UI may run.
//
// Session authorization model: possession of the credential (passphrase or
// the device's unlocked state) IS the authorization. This protects keys AT
// REST (stolen disk, casual snooping, idle unlocked session); it cannot stop
// an attacker with live access to an unlocked machine.
enum class SecretGateMode { Setup, Unlock, LockedOut, Open }

class SecretGateState(
    private val apiKeyCipher: ApiKeyCipher,
    private val userAuthenticator: UserAuthenticator,
    private val onProtected: suspend () -> Unit = {}
) {

    var mode by mutableStateOf(initialMode())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var attemptsRemaining by mutableStateOf(MAX_ATTEMPTS)
        private set
    var busy by mutableStateOf(false)
        private set

    val isPassphraseBackend: Boolean
        get() = apiKeyCipher.backendName.contains("passphrase", ignoreCase = true)

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MIN_PASSPHRASE_LENGTH = 6
    }

    private fun initialMode(): SecretGateMode = when {
        isPassphraseBackend && !apiKeyCipher.isProtected -> SecretGateMode.Setup
        isPassphraseBackend && !apiKeyCipher.isAvailable -> SecretGateMode.Unlock
        // Mobile: the backend (Keystore/Keychain) is always open, so the
        // gate is purely a user-presence check. Devices without any unlock
        // method open directly (a recommendation is shown over the main UI
        // on first launch instead).
        !isPassphraseBackend && userAuthenticator.isLockConfigured -> SecretGateMode.Unlock
        else -> SecretGateMode.Open
    }

    /** Re-derives the mode from the backend state; call after lock(), after
     *  protection is removed externally, or after a backend state change. */
    fun refresh() {
        mode = initialMode()
    }

    // First-run setup: derive a key from the passphrase, persist the
    // protection file, then re-encrypt any existing plaintext API keys under
    // the new key so nothing stays on disk unprotected.
    suspend fun submitSetup(passphrase: String, confirmation: String) {
        error = null
        when {
            passphrase.length < MIN_PASSPHRASE_LENGTH ->
                error = "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters."
            passphrase != confirmation ->
                error = "Passphrases do not match."
            else -> {
                busy = true
                try {
                    apiKeyCipher.protect(passphrase)
                    onProtected()
                    mode = SecretGateMode.Open
                } catch (e: Exception) {
                    error = e.message ?: "Failed to protect API keys."
                } finally {
                    busy = false
                }
            }
        }
    }

    // Unlock: verify the passphrase against the AES-GCM verifier. Each failed
    // attempt costs a full PBKDF2 stretch; after MAX_ATTEMPTS the gate locks
    // for the rest of the process.
    suspend fun submitUnlock(passphrase: String) {
        if (mode != SecretGateMode.Unlock) return
        error = null
        busy = true
        try {
            if (apiKeyCipher.unlock(passphrase)) {
                attemptsRemaining = MAX_ATTEMPTS
                mode = SecretGateMode.Open
            } else {
                attemptsRemaining--
                if (attemptsRemaining <= 0) {
                    mode = SecretGateMode.LockedOut
                } else {
                    error = "Wrong passphrase. $attemptsRemaining of $MAX_ATTEMPTS attempts remaining."
                }
            }
        } finally {
            busy = false
        }
    }

    // Mobile unlock: shows the OS prompt (PIN/password/fingerprint). The OS
    // enforces its own attempt limits; the app only learns the result.
    //   true  -> the user passed the prompt, open the app.
    //   false -> cancelled or failed, stay on the gate.
    //   null  -> the device no longer has any unlock method (or the prompt
    //            cannot be shown): open directly rather than blocking.
    suspend fun submitPlatformUnlock() {
        if (mode != SecretGateMode.Unlock || isPassphraseBackend) return
        error = null
        busy = true
        try {
            when (userAuthenticator.authenticate()) {
                true, null -> mode = SecretGateMode.Open
                false -> error = "Authentication failed or cancelled."
            }
        } finally {
            busy = false
        }
    }

    // Idle auto-lock: forget the derived key so secrets are locked again.
    // The protection file stays; refresh() flips the gate back to Unlock.
    fun lock() {
        apiKeyCipher.lock()
        refresh()
    }
}

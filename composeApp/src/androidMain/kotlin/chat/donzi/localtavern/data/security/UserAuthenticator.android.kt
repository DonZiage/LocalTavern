package chat.donzi.localtavern.data.security

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import chat.donzi.localtavern.AndroidAppContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// API keys are already encrypted by the Android Keystore, so this gate is
// about ACCESS, not storage: the app asks for the device's own unlock method
// (fingerprint, Face, PIN/pattern/password) before opening. The OS shows the
// prompt and enforces its own attempt limits; the app only learns the binary
// result and never sees the credential.
actual fun createUserAuthenticator(): UserAuthenticator = AndroidUserAuthenticator()

class AndroidUserAuthenticator : UserAuthenticator {

    override val isLockConfigured: Boolean
        get() {
            val context = AndroidAppContext.getContext() ?: return false
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
            return keyguard.isDeviceSecure
        }

    override suspend fun authenticate(): Boolean? {
        val context = AndroidAppContext.getContext() ?: return null
        val activity = AndroidAppContext.getActivity() as? FragmentActivity ?: return null
        if (!isLockConfigured) return null
        // DEVICE_CREDENTIAL makes the prompt fall back to the PIN/pattern/
        // password when no biometrics are enrolled (or are not recognized).
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = BiometricManager.from(context).canAuthenticate(authenticators)
        // Hardware unavailable or nothing enrolled (despite isDeviceSecure):
        // there is no prompt we can show — open directly rather than blocking
        // the user behind a dead button.
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) return null

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Covers user cancel, too many attempts, hardware errors.
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // Biometric was read but not recognized; the prompt
                        // stays open for another try, so do not resolve.
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock LocalTavern")
                .setSubtitle("Verify with your PIN, password or fingerprint")
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
        }
    }
}

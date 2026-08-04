package chat.donzi.localtavern.data.security

import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

// API keys are already encrypted by a Keychain-held key, so this gate is
// about ACCESS: the app asks for the device's own unlock method (Face ID,
// Touch ID or the device passcode) before opening. LAPolicyDeviceOwner
// Authentication picks whatever the user has set up and falls back to the
// passcode; the app only learns the binary result, never the credential.
@OptIn(ExperimentalForeignApi::class)
actual fun createUserAuthenticator(): UserAuthenticator = IosUserAuthenticator()

@OptIn(ExperimentalForeignApi::class)
class IosUserAuthenticator : UserAuthenticator {

    override val isLockConfigured: Boolean
        get() = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)

    override suspend fun authenticate(): Boolean? {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) return null
        return suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(
                LAPolicyDeviceOwnerAuthentication,
                "LocalTavern needs to verify it is you before opening."
            ) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
        }
    }
}

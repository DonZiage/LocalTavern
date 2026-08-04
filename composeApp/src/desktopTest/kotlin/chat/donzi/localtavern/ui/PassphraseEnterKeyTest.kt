package chat.donzi.localtavern.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.SecretCrypto
import chat.donzi.localtavern.data.security.UserAuthenticator
import chat.donzi.localtavern.ui.settings.PassphraseDialog
import chat.donzi.localtavern.ui.settings.PassphraseDialogInput
import kotlin.test.Test
import kotlin.test.assertEquals

// Enter (and NumPadEnter) must submit the passphrase forms on desktop,
// mirroring the button click: unlock gate, setup gate, settings dialog.
private class KeyProbeCrypto(
    var protected: Boolean = false,
    override val isAvailable: Boolean = false
) : SecretCrypto {
    override val isProtected: Boolean get() = protected
    override val backendName: String get() = "Desktop passphrase"
    override fun encrypt(plaintext: String): String? = plaintext
    override fun decrypt(payload: String): String? = payload
    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) {
        protected = true
    }
    override fun removeProtection() = Unit
    override fun lock() = Unit
}

private object KeyProbeAuthenticator : UserAuthenticator {
    override val isLockConfigured: Boolean get() = false
    override suspend fun authenticate(): Boolean? = true
}

@OptIn(ExperimentalTestApi::class)
class PassphraseEnterKeyTest {

    private fun gateFor(crypto: SecretCrypto): SecretGateState =
        SecretGateState(ApiKeyCipher(crypto), KeyProbeAuthenticator)

    @Test
    fun unlock_enterOpensTheGate() = runComposeUiTest {
        val state = gateFor(KeyProbeCrypto(protected = true))
        setContent {
            MaterialTheme { Surface(Modifier) { SecretGate(state) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("secret123")
        onAllNodes(hasSetTextAction())[0].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(SecretGateMode.Open, state.mode)
    }

    @Test
    fun unlock_numPadEnterOpensTheGate() = runComposeUiTest {
        val state = gateFor(KeyProbeCrypto(protected = true))
        setContent {
            MaterialTheme { Surface(Modifier) { SecretGate(state) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("secret123")
        onAllNodes(hasSetTextAction())[0].performKeyInput { pressKey(Key.NumPadEnter) }
        waitForIdle()
        assertEquals(SecretGateMode.Open, state.mode)
    }

    @Test
    fun setup_enterOnConfirmationOpensTheGate() = runComposeUiTest {
        val state = gateFor(KeyProbeCrypto(protected = false))
        setContent {
            MaterialTheme { Surface(Modifier) { SecretGate(state) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("Saf3-Wolf!")
        onAllNodes(hasSetTextAction())[1].performTextInput("Saf3-Wolf!")
        onAllNodes(hasSetTextAction())[1].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(SecretGateMode.Open, state.mode)
    }

    @Test
    fun setup_enterOnPassphraseAdvancesToConfirmation() = runComposeUiTest {
        val state = gateFor(KeyProbeCrypto(protected = false))
        setContent {
            MaterialTheme { Surface(Modifier) { SecretGate(state) } }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("Saf3-Wolf!")
        onAllNodes(hasSetTextAction())[0].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        // Validation cannot pass with an empty confirmation, so Enter must
        // have moved focus (not submitted) and the gate stays on Setup.
        assertEquals(SecretGateMode.Setup, state.mode)
    }

    @Test
    fun dialog_enterOnSingleFieldConfirms() = runComposeUiTest {
        var confirmed: PassphraseDialogInput? = null
        setContent {
            MaterialTheme {
                PassphraseDialog(
                    title = "Remove Passphrase",
                    message = "Enter the passphrase.",
                    confirmLabel = "Remove",
                    requireConfirmation = false,
                    requireCurrent = false,
                    onDismiss = {},
                    onConfirm = { confirmed = it }
                )
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("secret123")
        onAllNodes(hasSetTextAction())[0].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("secret123", confirmed?.value)
    }

    @Test
    fun dialog_enterOnChangeFlowConfirms() = runComposeUiTest {
        var confirmed: PassphraseDialogInput? = null
        setContent {
            MaterialTheme {
                PassphraseDialog(
                    title = "Change Passphrase",
                    message = "Enter your current passphrase, then choose a new one.",
                    confirmLabel = "Change",
                    requireConfirmation = true,
                    requireCurrent = true,
                    onDismiss = {},
                    onConfirm = { confirmed = it }
                )
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("old-pass")
        onAllNodes(hasSetTextAction())[1].performTextInput("new-pass")
        onAllNodes(hasSetTextAction())[2].performTextInput("new-pass")
        onAllNodes(hasSetTextAction())[2].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("old-pass", confirmed?.current)
        assertEquals("new-pass", confirmed?.value)
    }

    @Test
    fun dialog_enterWithShortPassphraseDoesNotConfirm() = runComposeUiTest {
        var confirmed = false
        setContent {
            MaterialTheme {
                PassphraseDialog(
                    title = "Set Passphrase",
                    message = "Choose a passphrase.",
                    confirmLabel = "Set",
                    requireConfirmation = false,
                    requireCurrent = false,
                    enforcePolicy = true,
                    onDismiss = {},
                    onConfirm = { confirmed = true }
                )
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("123")
        onAllNodes(hasSetTextAction())[0].performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(false, confirmed)
    }
}

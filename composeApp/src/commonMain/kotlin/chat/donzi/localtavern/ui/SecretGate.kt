package chat.donzi.localtavern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Full-screen gate shown BEFORE the main UI whenever the unlock backend is
// not open: desktop first-run passphrase setup (obligatory, keys are
// plaintext until then), desktop unlock after a restart / idle auto-lock, or
// the mobile device-unlock prompt (PIN/password/fingerprint). LockedOut has
// no inputs: the attempt counter is process-local, so the only way forward
// is restarting the app.
@Composable
fun SecretGate(state: SecretGateState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.wrapContentWidth().widthIn(max = 320.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (state.mode) {
                    SecretGateMode.Setup -> SetupContent(state)
                    SecretGateMode.Unlock ->
                        if (state.isPassphraseBackend) UnlockContent(state) else PlatformUnlockContent(state)
                    SecretGateMode.LockedOut -> LockedOutContent()
                    SecretGateMode.Open -> Unit
                }
            }
        }
    }
}

// Mobile unlock: the OS prompt (Face ID / fingerprint / PIN / password) is
// triggered by the button. Cancelling keeps the gate up; the OS enforces its
// own attempt limits, so no local counter is needed.
@Composable
private fun PlatformUnlockContent(state: SecretGateState) {
    val scope = rememberCoroutineScope()

    Text(
        "Unlock LocalTavern",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        "Use your device's PIN, password or fingerprint to verify it is you.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.error != null) {
        Text(
            state.error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Button(
        onClick = { scope.launch { state.submitPlatformUnlock() } },
        enabled = !state.busy,
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
        } else {
            Text("Unlock")
        }
    }
}

@Composable
private fun SetupContent(state: SecretGateState) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val firstFieldFocus = remember { FocusRequester() }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = PassphrasePolicy.isValid(passphrase)
    val canSubmit = valid && confirmation == passphrase && !state.busy

    // Desktop users type straight away: focus the passphrase field on entry.
    LaunchedEffect(Unit) { firstFieldFocus.requestFocus() }

    Text(
        "Protect your API keys",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        "LocalTavern encrypts your API keys with a passphrase-derived key before you can use the app. " +
            "This passphrase is never stored anywhere — if you forget it, your encrypted keys cannot be recovered. " +
            "Store it in your password manager.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier
            .widthIn(max = 260.dp).fillMaxWidth()
            .focusRequester(firstFieldFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    if (canSubmit) {
                        scope.launch { state.submitSetup(passphrase, confirmation) }
                    } else {
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                    true
                } else {
                    false
                }
            }
    )
    OutlinedTextField(
        value = confirmation,
        onValueChange = { confirmation = it },
        label = { Text("Confirm passphrase") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        isError = confirmation.isNotEmpty() && confirmation != passphrase,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (canSubmit) scope.launch { state.submitSetup(passphrase, confirmation) }
        }),
        modifier = Modifier
            .widthIn(max = 260.dp).fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    if (canSubmit) {
                        scope.launch { state.submitSetup(passphrase, confirmation) }
                    }
                    true
                } else {
                    false
                }
            }
    )
    if (state.error != null) {
        Text(
            state.error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    } else if (passphrase.isNotEmpty() && !valid) {
        Text(
            PassphrasePolicy.messageFor(PassphrasePolicy.firstIssue(passphrase)!!),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    PassphraseRequirements()

    Button(
        onClick = { scope.launch { state.submitSetup(passphrase, confirmation) } },
        enabled = canSubmit,
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
        } else {
            Text("Protect & Continue")
        }
    }
}

// Compact requirements + password-manager tip shown under the passphrase
// fields wherever a NEW passphrase is created.
@Composable
private fun PassphraseRequirements() {
    Column(
        modifier = Modifier.widthIn(max = 300.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PassphrasePolicy.requirements.forEach { requirement ->
            Text(
                "• $requirement",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            PassphrasePolicy.PASSWORD_MANAGER_TIP,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun UnlockContent(state: SecretGateState) {
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    var passphrase by remember { mutableStateOf("") }
    val canSubmit = passphrase.isNotBlank() && !state.busy

    // Desktop users type straight away: focus the passphrase field on entry.
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    Text(
        "Unlock LocalTavern",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        "Enter your passphrase to decrypt your API keys. The app stays unlocked until it exits or auto-locks after inactivity.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (canSubmit) scope.launch { state.submitUnlock(passphrase) }
        }),
        modifier = Modifier
            .widthIn(max = 260.dp).fillMaxWidth()
            .focusRequester(fieldFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    if (canSubmit) {
                        scope.launch { state.submitUnlock(passphrase) }
                    }
                    true
                } else {
                    false
                }
            }
    )
    if (state.error != null) {
        Text(
            state.error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    } else {
        Text(
            "${state.attemptsRemaining} of ${SecretGateState.MAX_ATTEMPTS} attempts remaining.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Button(
        onClick = { scope.launch { state.submitUnlock(passphrase) } },
        enabled = canSubmit,
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
        } else {
            Text("Unlock")
        }
    }
}

@Composable
private fun LockedOutContent() {
    Text(
        "Too many incorrect attempts",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        "LocalTavern has stopped accepting passphrase guesses for this session. " +
            "Close and restart the app to try again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GateError(error: String?) {
    if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

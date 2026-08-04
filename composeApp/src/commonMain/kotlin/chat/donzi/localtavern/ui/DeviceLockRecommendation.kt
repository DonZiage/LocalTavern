package chat.donzi.localtavern.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import chat.donzi.localtavern.isAndroid
import chat.donzi.localtavern.openDeviceSecuritySettings

// Mobile-only, shown over the main UI on the FIRST launch when the device
// has no unlock method (PIN/password/fingerprint) configured. The app opens
// directly regardless — this is a recommendation, not a gate. Once dismissed
// (either button, or tapping outside), the flag is persisted and it never
// shows again.
@Composable
fun DeviceLockRecommendationDialog(onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Protect LocalTavern with a screen lock") },
        text = {
            Text(
                "Your phone has no PIN, password or fingerprint set up. " +
                    "LocalTavern keeps your API keys on this device — anyone who can unlock your phone " +
                    "can open the app and use them. " +
                    "Set a screen lock in your phone's settings so the app can ask for it before opening.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("Continue") }
        },
        dismissButton = {
            if (isAndroid) {
                TextButton(onClick = { openDeviceSecuritySettings() }) { Text("Open settings") }
            }
        }
    )
}

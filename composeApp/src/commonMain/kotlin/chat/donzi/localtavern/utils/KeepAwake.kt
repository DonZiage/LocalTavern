package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import chat.donzi.localtavern.setKeepScreenOn

// Ref-counted screen-wake leases for long operations (big syncs, mass
// imports): every holder keeps the screen on until it releases, so two
// overlapping operations cannot turn the screen off early. All holds are
// taken and released from the UI layer (see KeepScreenOn), so no locking is
// needed; the platform call itself is best-effort.
object KeepScreenAwake {
    private var leases = 0

    fun acquire() {
        if (leases == 0) {
            runCatching { setKeepScreenOn(true) }
        }
        leases++
    }

    fun release() {
        if (leases <= 0) return
        leases--
        if (leases == 0) {
            runCatching { setKeepScreenOn(false) }
        }
    }
}

// Holds a screen-wake lease while [active] is true (e.g. isSyncing,
// isImporting) and releases it when the flag clears or this composable
// leaves the composition — a big sync/import that outlives its screen still
// releases the lease instead of burning the battery forever.
@Composable
fun KeepScreenOn(active: Boolean) {
    DisposableEffect(active) {
        if (active) KeepScreenAwake.acquire()
        onDispose {
            if (active) KeepScreenAwake.release()
        }
    }
}

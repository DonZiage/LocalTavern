package chat.donzi.localtavern.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// Loads one row's avatar bytes on demand: list screens observe avatar-free
// headers (never the blobs), so only the rows actually on screen hit the
// database — a heavy library no longer pins every avatar on the heap. Misses
// and failures yield null (the NoPic fallback); a reload is triggered by the
// row id changing.
@Composable
fun rememberAvatarBytes(
    id: String?,
    loader: (suspend (String) -> ByteArray?)?
): ByteArray? {
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(id, loader) {
        bytes = null
        if (id != null && loader != null) {
            bytes = try {
                loader(id)
            } catch (_: Exception) {
                null
            }
        }
    }
    return bytes
}

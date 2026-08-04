package chat.donzi.localtavern.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// SAF-based document picker: the system Photo Picker is image-only, but
// character cards include JSON files and ZIP archives, so the import flow
// needs the documents contract.
@Composable
actual fun rememberCharacterCardPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The activity-result callback is registered once but must always invoke
    // the latest lambda (the callers may close over changing state).
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            // A cancelled pick must not invoke the callback.
            if (uris.isEmpty()) return@launch
            val picked = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        val name = queryDisplayName(context, uri) ?: return@mapNotNull null
                        val bytes = context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes() } ?: return@mapNotNull null
                        PickedFile(name, bytes)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (picked.isNotEmpty()) {
                currentOnPicked(picked)
            }
        }
    }

    return remember {
        {
            launcher.launch(
                arrayOf(
                    "image/png",
                    "application/json",
                    "application/zip",
                    "application/octet-stream"
                )
            )
        }
    }
}

// Single-document SAF picker for standalone lorebook (world info) JSON files.
@Composable
actual fun rememberLorebookPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                try {
                    val name = queryDisplayName(context, uri) ?: "lorebook.json"
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: return@withContext null
                    PickedFile(name, bytes)
                } catch (e: Exception) {
                    null
                }
            }
            if (picked != null) {
                currentOnPicked(listOf(picked))
            }
        }
    }

    return remember {
        {
            launcher.launch(arrayOf("application/json", "application/octet-stream"))
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}

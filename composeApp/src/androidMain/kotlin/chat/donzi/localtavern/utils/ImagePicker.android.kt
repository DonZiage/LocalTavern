package chat.donzi.localtavern.utils

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import chat.donzi.localtavern.utils.ImageSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImagePickerLauncher(
    onImagesPicked: (List<ByteArray>) -> Unit,
    preserveOriginal: Boolean
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The activity-result callback is registered once but must always invoke
    // the latest lambda (the callers may close over changing state).
    val currentOnImagesPicked by rememberUpdatedState(onImagesPicked)
    val launcher = rememberLauncherForActivityResult(
        // Cap the selection up front so the picker never yields more images
        // than the sanitizer keeps; otherwise every picked photo is read at
        // full resolution into memory before the truncation happens.
        contract = ActivityResultContracts.PickMultipleVisualMedia(ImageSanitizer.MAX_PICKED_IMAGES)
    ) { uris ->
        scope.launch {
            // A cancelled pick (or one whose files all failed to load) must
            // not invoke the callback: callers that REPLACE the current image
            // set (e.g. the character editor's avatar) would otherwise wipe
            // the existing image on a no-op pick.
            if (uris.isEmpty()) return@launch
            val byteArrays = withContext(Dispatchers.IO) {
                val rawImages = uris.mapNotNull { uri ->
                    try {
                        // A per-URI guard: expired grants and IO failures on
                        // one photo must not kill the whole pick.
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            inputStream.readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                ImageSanitizer.sanitize(rawImages, preserveOriginal)
            }
            if (byteArrays.isNotEmpty()) {
                currentOnImagesPicked(byteArrays)
            }
        }
    }

    return remember {
        {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
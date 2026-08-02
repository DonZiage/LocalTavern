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
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        scope.launch {
            val byteArrays = withContext(Dispatchers.IO) {
                val rawImages = uris.mapNotNull { uri ->
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.readBytes()
                    }
                }
                ImageSanitizer.sanitize(rawImages, preserveOriginal)
            }
            currentOnImagesPicked(byteArrays)
        }
    }

    return remember {
        {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
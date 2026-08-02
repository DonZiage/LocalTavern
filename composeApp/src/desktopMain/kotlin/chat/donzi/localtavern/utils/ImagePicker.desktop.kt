package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import chat.donzi.localtavern.utils.ImageSanitizer
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePickerLauncher(
    onImagesPicked: (List<ByteArray>) -> Unit,
    preserveOriginal: Boolean
): () -> Unit {
    val scope = rememberCoroutineScope()
    // The launch closure is remembered once but must always invoke the latest
    // callback (the callers may close over changing state).
    val currentOnImagesPicked by rememberUpdatedState(onImagesPicked)
    return remember {
        {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (e: Exception) {
            }

            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "webp")
                dialogTitle = "Select Images"
            }

            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val files = chooser.selectedFiles
                scope.launch {
                    val byteArrays = withContext(Dispatchers.IO) {
                        val rawImages = files.mapNotNull { file ->
                            try {
                                file.readBytes()
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
        }
    }
}

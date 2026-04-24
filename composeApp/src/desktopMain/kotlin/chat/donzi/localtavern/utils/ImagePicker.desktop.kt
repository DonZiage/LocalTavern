package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberImagePickerLauncher(onImagePicked: (ByteArray) -> Unit): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(Frame(), "Select Image", FileDialog.LOAD)
            dialog.setFilenameFilter { _, filename ->
                val ext = filename.lowercase()
                ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".webp")
            }
            dialog.isVisible = true
            if (dialog.file != null) {
                val file = File(dialog.directory, dialog.file)
                onImagePicked(file.readBytes())
            }
        }
    }
}

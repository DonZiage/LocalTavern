package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberImagePickerLauncher(onImagesPicked: (List<ByteArray>) -> Unit): () -> Unit {
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
                val byteArrays = files.mapNotNull { file ->
                    try {
                        file.readBytes()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (byteArrays.isNotEmpty()) {
                    onImagesPicked(byteArrays)
                }
            }
        }
    }
}
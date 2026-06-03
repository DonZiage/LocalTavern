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
                dialogTitle = "Select Images"
                isMultiSelectionEnabled = true

                fileFilter = FileNameExtensionFilter(
                    "Supported Images (.png, .jpg, .jpeg, .webp)",
                    "png", "jpg", "jpeg", "webp"
                )

                isAcceptAllFileFilterUsed = false
            }

            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val selectedFiles = chooser.selectedFiles
                if (!selectedFiles.isNullOrEmpty()) {
                    val imageList = selectedFiles.mapNotNull { file ->
                        try {
                            file.readBytes()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (imageList.isNotEmpty()) {
                        onImagesPicked(imageList)
                    }
                }
            }
        }
    }
}
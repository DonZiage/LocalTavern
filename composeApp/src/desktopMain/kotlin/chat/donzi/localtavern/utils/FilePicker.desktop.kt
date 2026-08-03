package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberCharacterCardPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    // The launch closure is remembered once but must always invoke the latest
    // callback (the callers may close over changing state).
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember {
        {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (e: Exception) {
            }

            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("Character cards", "png", "json", "zip")
                dialogTitle = "Select Character Cards"
            }

            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                val files = chooser.selectedFiles.toList()
                scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        files.mapNotNull { file ->
                            try {
                                PickedFile(file.name, file.readBytes())
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
        }
    }
}

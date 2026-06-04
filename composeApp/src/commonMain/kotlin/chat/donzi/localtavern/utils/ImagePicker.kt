package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable

interface ImagePicker {
    fun pickImages()
}

@Composable
expect fun rememberImagePickerLauncher(onImagesPicked: (List<ByteArray>) -> Unit): () -> Unit
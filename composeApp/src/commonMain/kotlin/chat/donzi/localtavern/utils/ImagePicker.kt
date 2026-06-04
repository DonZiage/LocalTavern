package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable

@Composable
expect fun rememberImagePickerLauncher(onImagesPicked: (List<ByteArray>) -> Unit): () -> Unit
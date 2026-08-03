package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable

// Picks character-card sources (PNG/JSON cards and ZIP archives) with
// multi-selection, bypassing the chat image pickers' 4-image cap. The
// platform contracts differ, so each platform supplies its own launcher.
@Composable
expect fun rememberCharacterCardPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit

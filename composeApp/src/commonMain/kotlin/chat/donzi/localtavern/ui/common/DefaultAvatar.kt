package chat.donzi.localtavern.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import localtavern.composeapp.generated.resources.Res
import localtavern.composeapp.generated.resources.nopic
import org.jetbrains.compose.resources.painterResource

/**
 * Default avatar shown for characters and personas that have no custom image.
 * Backed by `nopic.png` (relocated from the project root into compose
 * resources). Callers keep `avatarData = null` in the database and render
 * this instead; picking an image replaces it.
 */
@Composable
fun DefaultAvatarImage(
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Image(
        painter = painterResource(Res.drawable.nopic),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}

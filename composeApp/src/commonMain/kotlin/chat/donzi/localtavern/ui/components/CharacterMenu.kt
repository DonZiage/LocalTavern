package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CharacterMenuHeader() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Characters",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Select a companion for the Tavern",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CharacterList(onCharacterSelected: (String) -> Unit) {
    // These are placeholders. Soon we'll pull these from your SQLDelight database!
    val dummyCharacters = listOf(
        "Tavern Keeper" to "A helpful guide for your local LLM journey.",
        "SillyTavern Bot" to "Ready to parse your PNG metadata cards."
    )

    Column(modifier = Modifier.fillMaxSize()) {
        dummyCharacters.forEach { (name, desc) ->
            // This calls the CharacterItem we just fixed in the other file
            CharacterItem(
                name = name,
                description = desc,
                onClick = { onCharacterSelected(name) }
            )
        }
    }
}
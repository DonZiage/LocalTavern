package chat.donzi.localtavern.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.components.SidePanels
import androidx.compose.ui.geometry.Offset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    chatRepository: ChatRepository,
    chatClient: ChatClient,
    characters: List<CharacterEntity>,
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    activeDrawer: ActiveDrawer,
    onActiveDrawerChange: (ActiveDrawer) -> Unit,
    refreshData: () -> Unit
) {
    val drawerWidth = 300.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("LocalTavern") },
                    navigationIcon = {
                        IconButton(onClick = { onActiveDrawerChange(ActiveDrawer.Settings) }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Settings")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onActiveDrawerChange(ActiveDrawer.Characters) }) {
                            Icon(Icons.Filled.Person, contentDescription = "Characters")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // Main content of your application
                Text("Welcome to LocalTavern", modifier = Modifier.padding(16.dp))
            }
        }

        SidePanels(
            activeDrawer = activeDrawer,
            drawerWidth = drawerWidth,
            onClose = { onActiveDrawerChange(ActiveDrawer.None) },
            // Settings
            chatRepository = chatRepository,
            chatClient = chatClient,
            isDarkMode = isDarkMode,
            onToggleDarkMode = onToggleDarkMode,
            // Personas
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = { _ ->
                refreshData()
            },
            onPersonaAdd = { _, _, _ ->
                refreshData()
            },
            onPersonaUpdate = { _, _, _, _ ->
                refreshData()
            },
            onPersonaDelete = { _ ->
                refreshData()
            },
            // Characters
            characters = characters,
            onCharacterSelect = { _ ->
                // Handle character selection
            },
            onCharactersDelete = { _ ->
                refreshData()
            },
            onCharacterImport = { _, _ ->
                refreshData()
            },
            onCharacterCreate = { _ ->
                refreshData()
            }
        )
    }
}

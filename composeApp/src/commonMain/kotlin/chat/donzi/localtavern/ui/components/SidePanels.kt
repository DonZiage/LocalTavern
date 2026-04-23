package chat.donzi.localtavern.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.network.ChatClient
import androidx.compose.ui.geometry.Offset

enum class ActiveDrawer { None, Settings, Characters }

@Composable
fun SidePanels(
    activeDrawer: ActiveDrawer,
    drawerWidth: Dp,
    onClose: () -> Unit,
    // Settings Dependencies
    chatRepository: ChatRepository,
    chatClient: ChatClient,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    // Persona Dependencies
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    onPersonaSelect: (Long) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (Long, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (Long) -> Unit,
    // Character Dependencies
    characters: List<CharacterEntity>,
    onCharacterSelect: (CharacterEntity) -> Unit,
    onCharactersDelete: (Set<Long>) -> Unit,
    onCharacterImport: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCharacterCreate: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().zIndex(100f)) {
        // Scrim Overlay
        AnimatedVisibility(
            visible = activeDrawer != ActiveDrawer.None,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(100f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )
        }

        // Settings Drawer (Left)
        AnimatedVisibility(
            visible = activeDrawer == ActiveDrawer.Settings,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier
                .zIndex(101f)
                .fillMaxHeight()
                .width(drawerWidth)
                .align(Alignment.CenterStart)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        ThemeToggle(
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = onToggleDarkMode
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        CollapsibleSettingsSection(title = "API Connection") {
                            ApiConnectionSettings(
                                chatRepository = chatRepository,
                                chatClient = chatClient
                            )
                        }
                    }
                }
            }
        }

        // Characters and Personas Drawer (Right)
        AnimatedVisibility(
            visible = activeDrawer == ActiveDrawer.Characters,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier
                .zIndex(101f)
                .fillMaxHeight()
                .width(drawerWidth)
                .align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "Characters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        CollapsibleSettingsSection(title = "Personas") {
                            PersonaManagementSection(
                                personas = personas,
                                activePersonaId = activePersonaId,
                                onSelect = onPersonaSelect,
                                onAdd = onPersonaAdd,
                                onUpdate = onPersonaUpdate,
                                onDelete = onPersonaDelete
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).alpha(0.3f))

                        CollapsibleSettingsSection(title = "Character List") {
                            CharacterListSection(
                                characters = characters,
                                modifier = Modifier.heightIn(max = 1000.dp),
                                onSelect = onCharacterSelect,
                                onDeleteSelected = onCharactersDelete,
                                onImportCharacter = onCharacterImport,
                                onCreateCharacter = onCharacterCreate
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollapsibleSettingsSection(
    title: String,
    initialExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title, 
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
        
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

package chat.donzi.localtavern.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
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
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.network.ChatClient
import androidx.compose.ui.geometry.Offset

enum class ActiveDrawer {
    None,
    Settings,
    Characters
}

@Composable
fun SidePanels(
    activeDrawer: ActiveDrawer,
    drawerWidth: Dp,
    onClose: () -> Unit,
    apiSettingsRepository: ApiSettingsRepository,
    chatClient: ChatClient,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    personas: List<Persona>,
    activePersonaId: String?,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    characters: List<Character>,
    onCharacterSelect: (Character) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onCharacterImport: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCharacterExport: (Character) -> Unit,
    onCharacterCreate: (String) -> Unit,
    onCharacterEdit: (Character) -> Unit,
    autoEditDefaultPersona: Boolean = false,
    onAutoEditConsumed: () -> Unit = {},
    autoShowNewCharacterMenu: Boolean = false,
    onAutoShowMenuConsumed: () -> Unit = {},
    onApiChanged: () -> Unit = {}
) {
    var settingsApiExpanded by remember { mutableStateOf(false) }
    var personasExpanded by remember { mutableStateOf(true) }
    var charactersExpanded by remember { mutableStateOf(true) }

    // Hoisted above the AnimatedVisibility so the scroll position survives
    // drawer close/reopen (a rememberScrollState() inside the drawer content
    // is discarded every time the content leaves composition).
    val settingsScrollState = rememberScrollState()
    val charactersScrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().zIndex(100f)) {
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

        AnimatedVisibility(
            visible = activeDrawer == ActiveDrawer.Settings,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier
                // Entering drawer stays above the exiting one during direct
                // Settings <-> Characters switches.
                .zIndex(if (activeDrawer == ActiveDrawer.Settings) 102f else 101f)
                .fillMaxHeight()
                .width(drawerWidth)
                .align(Alignment.CenterStart)
        ) {
            SettingsPanelContent(
                apiSettingsRepository = apiSettingsRepository,
                chatClient = chatClient,
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode,
                onApiChanged = onApiChanged,
                apiSectionExpanded = settingsApiExpanded,
                onApiSectionExpandedChange = { settingsApiExpanded = it },
                scrollState = settingsScrollState
            )
        }

        AnimatedVisibility(
            visible = activeDrawer == ActiveDrawer.Characters,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier
                // Entering drawer stays above the exiting one during direct
                // Settings <-> Characters switches.
                .zIndex(if (activeDrawer == ActiveDrawer.Characters) 102f else 101f)
                .fillMaxHeight()
                .width(drawerWidth)
                .align(Alignment.CenterEnd)
        ) {
            CharactersPanelContent(
                personas = personas,
                activePersonaId = activePersonaId,
                onPersonaSelect = onPersonaSelect,
                onPersonaAdd = onPersonaAdd,
                onPersonaUpdate = onPersonaUpdate,
                onPersonaDelete = onPersonaDelete,
                characters = characters,
                onCharacterSelect = onCharacterSelect,
                onCharactersDelete = onCharactersDelete,
                onCharacterImport = onCharacterImport,
                onCharacterExport = onCharacterExport,
                onCharacterCreate = onCharacterCreate,
                onCharacterEdit = onCharacterEdit,
                autoEditDefaultPersona = autoEditDefaultPersona,
                onAutoEditConsumed = onAutoEditConsumed,
                autoShowNewCharacterMenu = autoShowNewCharacterMenu,
                onAutoShowMenuConsumed = onAutoShowMenuConsumed,
                personasExpanded = personasExpanded,
                onPersonasExpandedChange = { personasExpanded = it },
                charactersExpanded = charactersExpanded,
                onCharactersExpandedChange = { charactersExpanded = it },
                scrollState = charactersScrollState
            )
        }
    }
}

@Composable
fun SettingsPanelContent(
    apiSettingsRepository: ApiSettingsRepository,
    chatClient: ChatClient,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    onApiChanged: () -> Unit,
    apiSectionExpanded: Boolean,
    onApiSectionExpandedChange: (Boolean) -> Unit,
    scrollState: ScrollState = rememberScrollState()
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
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApiSectionExpandedChange(!apiSectionExpanded) }
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "API Connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        if (apiSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
                AnimatedVisibility(
                    visible = apiSectionExpanded,
                    modifier = Modifier.weight(1f),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        ApiConnectionSettings(
                            apiSettingsRepository = apiSettingsRepository,
                            chatClient = chatClient,
                            onApiChanged = onApiChanged
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CharactersPanelContent(
    personas: List<Persona>,
    activePersonaId: String?,
    onPersonaSelect: (String) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (String, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (String) -> Unit,
    characters: List<Character>,
    onCharacterSelect: (Character) -> Unit,
    onCharactersDelete: (Set<String>) -> Unit,
    onCharacterImport: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCharacterExport: (Character) -> Unit,
    onCharacterCreate: (String) -> Unit,
    onCharacterEdit: (Character) -> Unit,
    autoEditDefaultPersona: Boolean,
    onAutoEditConsumed: () -> Unit,
    autoShowNewCharacterMenu: Boolean,
    onAutoShowMenuConsumed: () -> Unit,
    personasExpanded: Boolean,
    onPersonasExpandedChange: (Boolean) -> Unit,
    charactersExpanded: Boolean,
    onCharactersExpandedChange: (Boolean) -> Unit,
    scrollState: ScrollState = rememberScrollState()
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
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Characters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                CollapsibleSettingsSection(
                    title = "Personas",
                    expanded = personasExpanded,
                    onExpandedChange = onPersonasExpandedChange
                ) {
                    PersonaManagementSection(
                        personas = personas,
                        activePersonaId = activePersonaId,
                        onSelect = onPersonaSelect,
                        onAdd = onPersonaAdd,
                        onUpdate = onPersonaUpdate,
                        onDelete = onPersonaDelete,
                        autoEditDefaultPersona = autoEditDefaultPersona,
                        onAutoEditConsumed = onAutoEditConsumed
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).alpha(0.3f))

                CollapsibleSettingsSection(
                    title = "Characters",
                    expanded = charactersExpanded,
                    onExpandedChange = onCharactersExpandedChange
                ) {
                    CharacterListSection(
                        characters = characters,
                        modifier = Modifier.heightIn(max = 1000.dp),
                        onSelect = onCharacterSelect,
                        onDeleteSelected = onCharactersDelete,
                        onImportCharacter = onCharacterImport,
                        onCreateCharacter = onCharacterCreate,
                        onEditCharacter = onCharacterEdit,
                        onExportCharacter = onCharacterExport,
                        autoShowNewCharacterMenu = autoShowNewCharacterMenu,
                        onAutoShowMenuConsumed = onAutoShowMenuConsumed
                    )
                }
            }
        }
    }
}

@Composable
fun CollapsibleSettingsSection(
    title: String,
    initialExpanded: Boolean = true,
    expanded: Boolean? = null,
    onExpandedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    var internalExpanded by remember { mutableStateOf(initialExpanded) }
    val isExpanded = expanded ?: internalExpanded
    val setExpanded: (Boolean) -> Unit = { value ->
        if (expanded != null) onExpandedChange(value) else internalExpanded = value
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { setExpanded(!isExpanded) }
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
                if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}
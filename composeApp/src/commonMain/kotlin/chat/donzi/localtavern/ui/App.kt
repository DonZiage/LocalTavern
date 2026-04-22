package chat.donzi.localtavern.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.createDatabase
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.utils.CharacterManager
import chat.donzi.localtavern.ui.components.CharacterDefinitionEditor
import chat.donzi.localtavern.ui.components.CharacterListSection
import chat.donzi.localtavern.ui.components.ApiConnectionSettings
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

var repository: ChatRepository? = null

enum class ActiveMenu { None, Settings, Characters }

@Composable
fun App(driverFactory: DriverFactory) {
    val scope = rememberCoroutineScope()
    var isDarkMode by remember { mutableStateOf(false) }
    var toggleOffset by remember { mutableStateOf(Offset.Zero) }
    var isTransitioning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (repository == null) {
            val database = createDatabase(driverFactory)
            repository = ChatRepository(database)
        }
        isDarkMode = repository?.getAppSettings()?.isDarkMode == 1L
    }

    TavernTheme(isDarkMode = isDarkMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                TavernChatScreen(
                    isDarkMode = isDarkMode,
                    onDarkModeChange = { newMode, offset ->
                        if (!isTransitioning) {
                            toggleOffset = offset
                            isDarkMode = newMode
                            isTransitioning = true
                            // Pushed to IO Dispatcher to prevent UI stuttering
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                repository?.updateDarkMode(newMode)
                            }
                        }
                    }
                )
            }

            val waveProgress by animateFloatAsState(
                targetValue = if (isTransitioning) 1f else 0f,
                animationSpec = tween(durationMillis = 800),
                finishedListener = { if (it == 1f) isTransitioning = false }
            )

            if (isTransitioning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1000f)
                        .pointerInput(Unit) {}
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(999f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * (1f - waveProgress)),
                                    Color.Transparent
                                ),
                                center = toggleOffset,
                                radius = waveProgress * 3000f
                            )
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernChatScreen(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean, Offset) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activeMenu by remember { mutableStateOf(ActiveMenu.None) }
    var selectedCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var editingDefinitions by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showCreateCharacterDialog by remember { mutableStateOf(false) }

    val characters by produceState<List<CharacterEntity>>(
        initialValue = emptyList(),
        key1 = refreshTrigger
    ) {
        value = repository?.getAllCharacters() ?: emptyList()
    }

    // Sync selectedCharacter when characters list changes
    LaunchedEffect(characters) {
        selectedCharacter?.let { current ->
            val updated = characters.find { it.id == current.id }
            if (updated != null) {
                selectedCharacter = updated
            } else if (editingDefinitions) {
                // Character was deleted, close the editor
                selectedCharacter = null
                editingDefinitions = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main Content Layer
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            selectedCharacter?.name ?: "LocalTavern",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { activeMenu = if (activeMenu == ActiveMenu.Settings) ActiveMenu.None else ActiveMenu.Settings }) {
                            Icon(Icons.Default.Settings, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { activeMenu = if (activeMenu == ActiveMenu.Characters) ActiveMenu.None else ActiveMenu.Characters }) {
                            Icon(Icons.Default.Person, null)
                        }
                    }
                )
            }

            if (editingDefinitions && selectedCharacter != null) {
                CharacterDefinitionEditor(
                    character = selectedCharacter!!,
                    onClose = { editingDefinitions = false },
                    onSave = { name, description, personality, scenario, firstMes, systemPrompt, altGreetings, avatarData ->
                        scope.launch {
                            repository?.updateCharacter(
                                id = selectedCharacter!!.id,
                                name = name,
                                personality = personality,
                                scenario = scenario,
                                description = description,
                                firstMes = firstMes,
                                systemPrompt = systemPrompt,
                                altGreetings = altGreetings,
                                avatarData = avatarData
                            )
                            refreshTrigger++
                        }
                    },
                    onDelete = {
                        val target = selectedCharacter ?: return@CharacterDefinitionEditor
                        scope.launch {
                            repository?.deleteCharacters(listOf(target.id))
                            selectedCharacter = null
                            editingDefinitions = false
                            refreshTrigger++
                        }
                    }
                )
            } else {
                ChatLayout()
            }
        }

        // Scrim Overlay
        AnimatedVisibility(
            visible = activeMenu != ActiveMenu.None,
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
                        onClick = { activeMenu = ActiveMenu.None }
                    )
            )
        }

        // Settings Menu (Left)
        AnimatedVisibility(
            visible = activeMenu == ActiveMenu.Settings,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier
                .zIndex(101f)
                .fillMaxHeight()
                .width(320.dp)
                .align(Alignment.CenterStart)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                SettingsPanel(
                    isDarkMode = isDarkMode,
                    onDarkModeChange = onDarkModeChange
                )
            }
        }

        // Characters Menu (Right)
        AnimatedVisibility(
            visible = activeMenu == ActiveMenu.Characters,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier
                .zIndex(101f)
                .fillMaxHeight()
                .width(320.dp)
                .align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PersonaSelectorSection()
                    HorizontalDivider()
                    CharacterListSection(
                        characters = characters,
                        modifier = Modifier.weight(1f),
                        onSelect = { char ->
                            selectedCharacter = char
                            activeMenu = ActiveMenu.None
                            editingDefinitions = true
                        },
                        onDeleteSelected = { ids ->
                            scope.launch {
                                repository?.deleteCharacters(ids)
                                refreshTrigger++
                            }
                        },
                        actions = {
                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                Button(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("New", style = MaterialTheme.typography.labelLarge)
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Import") },
                                        onClick = {
                                            menuExpanded = false
                                            triggerCharacterImport(scope) { refreshTrigger++ }
                                        },
                                        leadingIcon = { Icon(Icons.Default.FileUpload, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Create") },
                                        onClick = {
                                            menuExpanded = false
                                            showCreateCharacterDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.Create, null) }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        if (showCreateCharacterDialog) {
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateCharacterDialog = false },
                title = { Text("New Character") },
                text = {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Character Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val name = newName
                            showCreateCharacterDialog = false
                            scope.launch {
                                val newId = repository?.upsertCharacter(SillyTavernCardV2(name = name))
                                if (newId != null) {
                                    refreshTrigger++
                                    // Fetch it immediately to open the editor
                                    val newChar = repository?.getAllCharacters()?.find { it.id == newId }
                                    if (newChar != null) {
                                        selectedCharacter = newChar
                                        activeMenu = ActiveMenu.None
                                        editingDefinitions = true
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateCharacterDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

fun triggerCharacterImport(scope: kotlinx.coroutines.CoroutineScope, onComplete: () -> Unit) {
    val dialog = FileDialog(Frame(), "Select Character", FileDialog.LOAD)
    dialog.isVisible = true
    if (dialog.file != null) {
        val file = File(dialog.directory, dialog.file)
        val imported = CharacterManager.processImport(file)
        if (imported != null) {
            scope.launch {
                repository?.upsertCharacter(imported.card, imported.avatarData)
                onComplete()
            }
        } else {
            println("[App] Import returned null for ${file.absolutePath}")
        }
    }
}

@Composable
fun SettingsPanel(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean, Offset) -> Unit
) {
    var switchOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dark Mode",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    modifier = Modifier.onGloballyPositioned { 
                        switchOffset = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                    },
                    checked = isDarkMode,
                    onCheckedChange = { onDarkModeChange(it, switchOffset) }
                )
            }
        }
        
        CollapsibleSection(title = "API Connection") {
            ApiConnectionSettings()
        }

        CollapsibleSection(title = "General") {
            Text("Coming soon...", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

@Composable fun PersonaSelectorSection(){ Text("Personas",  modifier = Modifier.padding(16.dp)) }
@Composable fun ChatLayout()            { Text("Chat Area", modifier = Modifier.padding(16.dp)) }

@Composable
fun TavernTheme(
    isDarkMode: Boolean = false,
    content: @Composable () -> Unit
) {
    // Instant switch avoids the full-tree recomposition freeze, letting the overlay handle the visual transition
    val colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

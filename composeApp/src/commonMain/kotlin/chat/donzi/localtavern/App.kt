package chat.donzi.localtavern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.database.CharacterEntity
import chat.donzi.localtavern.database.DriverFactory
import chat.donzi.localtavern.database.createDatabase
import chat.donzi.localtavern.utils.CharacterManager
import chat.donzi.localtavern.ui.components.CharacterDefinitionEditor
import chat.donzi.localtavern.ui.components.CharacterListSection
import chat.donzi.localtavern.ui.components.ApiConnectionSettings
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

var repository: ChatRepository? = null

@Composable
fun App(driverFactory: DriverFactory) {
    LaunchedEffect(Unit) {
        if (repository == null) {
            val database = createDatabase(driverFactory)
            repository = ChatRepository(database)
        }
    }
    TavernTheme { TavernChatScreen() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernChatScreen() {
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showCharacterMenu by remember { mutableStateOf(false) }
    var selectedCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var editingDefinitions by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val characters by produceState<List<CharacterEntity>>(
        initialValue = emptyList(),
        key1 = refreshTrigger
    ) {
        value = repository?.getAllCharacters() ?: emptyList()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (showSettings) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) { SettingsPanel() }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CenterAlignedTopAppBar(
                title = { Text(selectedCharacter?.name ?: "LocalTavern") },
                navigationIcon = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, null)
                    }
                },
                actions = {
                    IconButton(onClick = { showCharacterMenu = !showCharacterMenu }) {
                        Icon(Icons.Default.Person, null)
                    }
                }
            )

            if (editingDefinitions && selectedCharacter != null) {
                CharacterDefinitionEditor(
                    character = selectedCharacter!!,
                    onClose = { editingDefinitions = false },
                    onSave = { name, description, personality, scenario, firstMes, systemPrompt, altGreetings ->
                        scope.launch {
                            repository?.updateCharacter(
                                id = selectedCharacter!!.id,
                                name = name,
                                personality = personality,
                                scenario = scenario,
                                description = description,
                                firstMes = firstMes,
                                systemPrompt = systemPrompt,
                                altGreetings = altGreetings
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

        if (showCharacterMenu) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                PersonaSelectorSection()
                HorizontalDivider()

                Text(
                    "Characters",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                CharacterListSection(
                    characters = characters,
                    modifier = Modifier.weight(1f),
                    onSelect = { char ->
                        selectedCharacter = char
                        editingDefinitions = true
                    },
                    onDeleteSelected = { ids ->
                        scope.launch {
                            repository?.deleteCharacters(ids)
                            if (selectedCharacter?.id in ids) {
                                selectedCharacter = null
                                editingDefinitions = false
                            }
                            refreshTrigger++
                        }
                    },
                    actions = {
                        Button(
                            onClick = { triggerCharacterImport(scope) { refreshTrigger++ } },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            Text("Import")
                        }
                    }
                )
            }
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
                repository?.upsertCharacter(imported.card, imported.avatarPath)
                onComplete()
            }
        } else {
            println("[App] Import returned null for ${file.absolutePath}")
        }
    }
}

@Composable
fun SettingsPanel() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(8.dp)
        )
        
        CollapsibleSection(title = "API Connection") {
            ApiConnectionSettings()
        }
        
        CollapsibleSection(title = "Appearance") {
            Text("Coming soon...", modifier = Modifier.padding(16.dp))
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
@Composable fun TavernTheme(content: @Composable () -> Unit) { MaterialTheme { content() } }
package chat.donzi.localtavern.ui

import androidx.compose.runtime.*
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.theme.ThemeTransition
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun App(driverFactory: DriverFactory) {
    val database = remember { LocalTavernDB(driverFactory.createDriver()) }
    val chatRepository = remember { ChatRepository(database) }
    val coroutineScope = rememberCoroutineScope()

    val httpClient = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
    val chatClient = remember { ChatClient(httpClient) }

    var characters by remember { mutableStateOf(emptyList<CharacterEntity>()) }
    var activePersonaId by remember { mutableStateOf<Long?>(null) }
    var personas by remember { mutableStateOf(emptyList<PersonaEntity>()) }

    // Initial theme state loaded from DB
    var isDarkMode by remember { mutableStateOf(true) }

    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    fun refreshData() {
        coroutineScope.launch {
            // 1. Ensure at least one Persona exists
            var currentPersonas = chatRepository.getAllPersonas()
            if (currentPersonas.isEmpty()) {
                chatRepository.insertPersona("User", "A mysterious traveler.", null)
                currentPersonas = chatRepository.getAllPersonas()
            }
            personas = currentPersonas

            // 2. Ensure a default API connection exists
            if (chatRepository.getAllApiConnections().isEmpty()) {
                chatRepository.insertApiConnection(
                    provider = "OpenAI Compatible",
                    name = "Local Model",
                    baseUrl = "http://localhost:11434/v1", // Default Ollama/Local address
                    apiKey = "ollama",
                    model = "llama3",
                    isActive = true,
                    isChatCompletion = true
                )
            }

            // 3. Load settings and ensure activePersonaId is set
            val settings = chatRepository.getAppSettings()
            var pId = settings.activePersonaId
            if (pId == null && personas.isNotEmpty()) {
                pId = personas.first().id
                chatRepository.updateActivePersonaId(pId)
            }
            activePersonaId = pId

            // 4. Load characters (includes Assistant if it exists)
            characters = chatRepository.getAllCharacters()

            // 5. Sync the initial theme from the database
            isDarkMode = settings.isDarkMode != 0L
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    // Wrap the app using the new, race-condition-proof ThemeTransition
    ThemeTransition(
        initialThemeIsDark = isDarkMode,
        onThemeSaved = { darkMode ->
            // Fire-and-forget the database update when the animation finishes
            coroutineScope.launch {
                chatRepository.updateDarkMode(darkMode)
            }
        }
    ) { syncedDarkTheme, triggerTransition ->
        MainScreen(
            chatRepository = chatRepository,
            chatClient = chatClient,
            characters = characters,
            personas = personas,
            activePersonaId = activePersonaId,

            // Pass the perfectly synced theme from the transition controller
            isDarkMode = syncedDarkTheme,

            // Trigger the snapshot lock and animation!
            onToggleDarkMode = { _, centerOffset ->
                triggerTransition(centerOffset)
            },

            activeDrawer = activeDrawer,
            onActiveDrawerChange = { activeDrawer = it },
            refreshData = ::refreshData,
            onPersonaSelect = { personaId ->
                coroutineScope.launch {
                    chatRepository.updateActivePersonaId(personaId)
                    refreshData()
                }
            },
            onPersonaAdd = { name, desc, avatar ->
                coroutineScope.launch {
                    chatRepository.insertPersona(name, desc, avatar)
                    refreshData()
                }
            },
            onPersonaUpdate = { id, name, desc, avatar ->
                coroutineScope.launch {
                    chatRepository.updatePersona(id, name, desc, avatar)
                    refreshData()
                }
            },
            onPersonaDelete = { personaId ->
                coroutineScope.launch {
                    chatRepository.deletePersona(personaId)
                    refreshData()
                }
            },
            onCharactersDelete = { ids ->
                coroutineScope.launch {
                    chatRepository.deleteCharacters(ids)
                    refreshData()
                }
            },
            onCharacterImport = { card, avatar ->
                coroutineScope.launch {
                    chatRepository.upsertCharacter(card, avatar)
                    refreshData()
                }
            },
            onCharacterCreate = { name ->
                coroutineScope.launch {
                    chatRepository.createCharacter(name)
                    refreshData()
                }
            }
        )
    }
}
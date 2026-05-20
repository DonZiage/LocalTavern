package chat.donzi.localtavern.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.theme.LocalTavernTheme
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

    val systemDark = isSystemInDarkTheme()
    var isDarkMode by remember { mutableStateOf(systemDark) }
    var isInitialized by remember { mutableStateOf(false) }

    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    fun refreshData() {
        coroutineScope.launch {
            var currentPersonas = chatRepository.getAllPersonas()
            if (currentPersonas.isEmpty()) {
                chatRepository.insertPersona("User", "", null)
                currentPersonas = chatRepository.getAllPersonas()
            }
            personas = currentPersonas

            if (chatRepository.getAllApiConnections().isEmpty()) {
                chatRepository.insertApiConnection(
                    provider = "OpenAI Compatible",
                    name = "Local Model",
                    baseUrl = "http://localhost:11434/v1",
                    apiKey = "ollama",
                    model = "llama3",
                    isActive = true,
                    isChatCompletion = true
                )
            }
            val settings = chatRepository.getAppSettings()
            var pId = settings.activePersonaId
            if (pId == null && personas.isNotEmpty()) {
                pId = personas.first().id
                chatRepository.updateActivePersonaId(pId)
            }
            activePersonaId = pId

            characters = chatRepository.getAllCharacters()

            isDarkMode = settings.isDarkMode != 0L
            isInitialized = true
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    if (!isInitialized) {
        LocalTavernTheme(darkTheme = isDarkMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    } else {
        ThemeTransition(
            initialThemeIsDark = isDarkMode,
            onThemeSaved = { darkMode ->
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

                isDarkMode = syncedDarkTheme,

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
}
package chat.donzi.localtavern.ui

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import chat.donzi.localtavern.data.database.ChatRepository
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.ui.theme.ThemeTransitionContainer
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import chat.donzi.localtavern.ui.components.ActiveDrawer
import kotlin.time.Duration.Companion.milliseconds

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
    
    // The "intended" theme state (what the Switch and UI show)
    var isDarkMode by remember { mutableStateOf(true) }
    
    // The theme currently applied to the base layer
    var baseLayerDarkMode by remember { mutableStateOf(true) }
    
    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    // Wave animation state
    var isAnimatingTheme by remember { mutableStateOf(false) }
    var animationProgress by remember { mutableFloatStateOf(0f) }
    var animationCenter by remember { mutableStateOf(Offset.Zero) }

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

            isDarkMode = settings.isDarkMode != 0L
            baseLayerDarkMode = isDarkMode
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    val onToggleDarkMode: (Boolean, Offset) -> Unit = { darkMode, center ->
        if (!isAnimatingTheme && darkMode != isDarkMode) {
            animationCenter = center
            isDarkMode = darkMode // Update UI state immediately
            isAnimatingTheme = true
            coroutineScope.launch {
                chatRepository.updateDarkMode(darkMode)
                
                // 1. Run the circular reveal animation
                // The overlay layer uses 'isDarkMode' (the new theme)
                // The base layer uses 'baseLayerDarkMode' (the old theme)
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 1000, 
                        easing = CubicBezierEasing(0.7f, 0.0f, 0.3f, 1.0f)
                    )
                ) { value, _ ->
                    animationProgress = value
                }
                
                // 2. Synchronize the base layer theme to the new state
                baseLayerDarkMode = darkMode
                
                // 3. WAIT for the base layer to fully recompose and render.
                // We use a generous delay to ensure Material 3 components settle.
                delay(500.milliseconds)
                
                // 4. Finally, remove the animation overlay
                isAnimatingTheme = false
                animationProgress = 0f
            }
        }
    }

    ThemeTransitionContainer(
        isDarkMode = isDarkMode,
        baseLayerDarkMode = baseLayerDarkMode,
        isAnimatingTheme = isAnimatingTheme,
        animationProgress = animationProgress,
        animationCenter = animationCenter
    ) { darkThemeForContent ->
        MainScreen(
            chatRepository = chatRepository,
            chatClient = chatClient,
            characters = characters,
            personas = personas,
            activePersonaId = activePersonaId,
            isDarkMode = darkThemeForContent,
            onToggleDarkMode = onToggleDarkMode,
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

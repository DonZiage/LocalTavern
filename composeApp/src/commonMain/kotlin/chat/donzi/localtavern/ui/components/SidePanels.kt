package chat.donzi.localtavern.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
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
    pricingRepository: chat.donzi.localtavern.data.database.PricingRepository,
    apiKeyCipher: chat.donzi.localtavern.data.security.ApiKeyCipher,
    syncService: chat.donzi.localtavern.data.sync.SyncService,
    syncRepository: chat.donzi.localtavern.data.sync.SyncRepository,
    syncDiscovery: chat.donzi.localtavern.data.sync.SyncDiscovery,
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
                pricingRepository = pricingRepository,
                apiKeyCipher = apiKeyCipher,
                syncService = syncService,
                syncRepository = syncRepository,
                syncDiscovery = syncDiscovery,
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

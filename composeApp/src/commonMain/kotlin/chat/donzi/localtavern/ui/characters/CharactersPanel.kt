package chat.donzi.localtavern.ui.characters
import chat.donzi.localtavern.ui.layout.CollapsibleSettingsSection

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.utils.BatchImportResult

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
    onImportCharacters: (BatchImportResult) -> Unit,
    onExportSelected: (Set<String>) -> Unit,
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
    confirmBeforeDelete: Boolean = true,
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
                        onAutoEditConsumed = onAutoEditConsumed,
                        confirmBeforeDelete = confirmBeforeDelete
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
                        onImportCharacters = onImportCharacters,
                        onExportSelected = onExportSelected,
                        onCreateCharacter = onCharacterCreate,
                        onEditCharacter = onCharacterEdit,
                        onExportCharacter = onCharacterExport,
                        autoShowNewCharacterMenu = autoShowNewCharacterMenu,
                        onAutoShowMenuConsumed = onAutoShowMenuConsumed,
                        confirmBeforeDelete = confirmBeforeDelete
                    )
                }
            }
        }
    }
}

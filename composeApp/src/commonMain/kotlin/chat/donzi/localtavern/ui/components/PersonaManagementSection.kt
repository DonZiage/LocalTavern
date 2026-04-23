package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.data.database.PersonaEntity

@Composable
fun PersonaManagementSection(
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    onSelect: (Long) -> Unit,
    onAdd: (String, String?, ByteArray?) -> Unit,
    onUpdate: (Long, String, String?, ByteArray?) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PersonaManagement(
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onSelect,
            onPersonaAdd = onAdd,
            onPersonaUpdate = onUpdate,
            onPersonaDelete = onDelete
        )
    }
}

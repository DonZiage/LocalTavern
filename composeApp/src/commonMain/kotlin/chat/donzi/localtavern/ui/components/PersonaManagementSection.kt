package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.domain.Persona

@Composable
fun PersonaManagementSection(
    personas: List<Persona>,
    activePersonaId: String?,
    onSelect: (String) -> Unit,
    onAdd: (String, String?, ByteArray?) -> Unit,
    onUpdate: (String, String, String?, ByteArray?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoEditDefaultPersona: Boolean = false,
    onAutoEditConsumed: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PersonaManagement(
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onSelect,
            onPersonaAdd = onAdd,
            onPersonaUpdate = onUpdate,
            onPersonaDelete = onDelete,
            autoEditDefaultPersona = autoEditDefaultPersona,
            onAutoEditConsumed = onAutoEditConsumed
        )
    }
}
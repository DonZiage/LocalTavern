package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import coil3.compose.AsyncImage

@Composable
fun PersonaManagement(
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    onPersonaSelect: (Long) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (Long, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (Long) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<PersonaEntity?>(null) }

    val activeIndex = remember(personas, activePersonaId) {
        personas.indexOfFirst { it.id == activePersonaId }.coerceAtLeast(0)
    }

    CardCarousel(
        title = "Persona Profiles",
        items = personas,
        key = { it.id },
        initialIndex = activeIndex,
        onAddClick = { showAddDialog = true },
        onDelete = { persona -> onPersonaDelete(persona.id) },
        addLabel = "New",
        cardHeight = 90.dp,
        carouselHeight = 115.dp,
        itemContent = { persona, _, modifier, requestCenter, onDeleteRequest ->
            PersonaCard(
                persona = persona,
                isActive = persona.id == activePersonaId,
                onSelect = { onPersonaSelect(persona.id) },
                onEdit = { editingPersona = persona },
                onDelete = onDeleteRequest,
                onCenterRequest = requestCenter,
                modifier = modifier
            )
        }
    )

    if (showAddDialog) {
        PersonaEditDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, desc, avatar ->
                onPersonaAdd(name, desc, avatar)
                showAddDialog = false
            }
        )
    }

    if (editingPersona != null) {
        val persona = editingPersona!!
        PersonaEditDialog(
            initialName = persona.name,
            initialDescription = persona.description ?: "",
            initialAvatar = persona.avatarData,
            onDismiss = { editingPersona = null },
            onSave = { name, desc, avatar ->
                onPersonaUpdate(persona.id, name, desc, avatar)
                editingPersona = null
            }
        )
    }
}

@Composable
fun PersonaCard(
    persona: PersonaEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCenterRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(12.dp)
    Card(
        modifier = modifier.clip(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    onSelect()
                    onCenterRequest()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (persona.avatarData != null) {
                    AsyncImage(
                        model = persona.avatarData,
                        contentDescription = "Persona Avatar",
                        modifier = Modifier.size(56.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                    ) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        persona.name, 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!persona.description.isNullOrBlank()) {
                        Text(
                            persona.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit, 
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete, 
                        null, 
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun PersonaEditDialog(
    initialName: String = "",
    initialDescription: String = "",
    initialAvatar: ByteArray? = null,
    onDismiss: () -> Unit,
    onSave: (String, String?, ByteArray?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var avatarData by remember { mutableStateOf(initialAvatar) }
    
    var showImageMenu by remember { mutableStateOf(false) }

    val pickImage = rememberImagePickerLauncher { bytes ->
        avatarData = bytes
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialName.isEmpty()) "Add Persona" else "Edit Persona") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                            .clickable { showImageMenu = true }
                    ) {
                        if (avatarData != null) {
                            AsyncImage(
                                model = avatarData,
                                contentDescription = "Persona Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "Add Photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp).align(Alignment.Center)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (avatarData == null) "Add Photo" else "Update Photo") },
                            leadingIcon = { Icon(if (avatarData == null) Icons.Default.AddAPhoto else Icons.Default.Refresh, null) },
                            onClick = {
                                showImageMenu = false
                                pickImage()
                            }
                        )
                        if (avatarData != null) {
                            DropdownMenuItem(
                                text = { Text("Remove Photo") },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showImageMenu = false
                                    avatarData = null
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description.ifBlank { null }, avatarData) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

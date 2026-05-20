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
import androidx.compose.ui.window.Dialog // Added for full screen modal popup behavior
import chat.donzi.localtavern.data.database.PersonaEntity
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import chat.donzi.localtavern.utils.DefaultTokenizer
import coil3.compose.AsyncImage

@Composable
fun PersonaManagement(
    personas: List<PersonaEntity>,
    activePersonaId: Long?,
    onPersonaSelect: (Long) -> Unit,
    onPersonaAdd: (String, String?, ByteArray?) -> Unit,
    onPersonaUpdate: (Long, String, String?, ByteArray?) -> Unit,
    onPersonaDelete: (Long) -> Unit,
    autoEditDefaultPersona: Boolean = false,
    onAutoEditConsumed: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<PersonaEntity?>(null) }

    val activeIndex = remember(personas, activePersonaId) {
        personas.indexOfFirst { it.id == activePersonaId }.coerceAtLeast(0)
    }

    LaunchedEffect(autoEditDefaultPersona, personas) {
        if (autoEditDefaultPersona && personas.isNotEmpty()) {
            editingPersona = personas.firstOrNull()
            onAutoEditConsumed()
        }
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

    // Conditional rendering matching ApiConnectionDialog patterns
    if (showAddDialog) {
        PersonaEditDialog(
            title = "Add Persona",
            initialName = "",
            initialDescription = "",
            initialAvatar = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, desc, avatar ->
                onPersonaAdd(name, desc, avatar)
                showAddDialog = false
            }
        )
    }

    if (editingPersona != null) {
        PersonaEditDialog(
            title = "Edit Persona",
            initialName = editingPersona?.name ?: "",
            initialDescription = editingPersona?.description ?: "",
            initialAvatar = editingPersona?.avatarData,
            onDismiss = { editingPersona = null },
            onSave = { name, desc, avatar ->
                editingPersona?.let { onPersonaUpdate(it.id, name, desc, avatar) }
                editingPersona = null
            }
        )
    }
}

@Composable
private fun PersonaEditDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    initialAvatar: ByteArray?,
    onDismiss: () -> Unit,
    onSave: (String, String?, ByteArray?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var avatarData by remember { mutableStateOf(initialAvatar) }

    var showImageMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    // Initialized cleanly inside the dialog context
    val pickImage = rememberImagePickerLauncher { bytes ->
        avatarData = bytes
    }

    val totalPersonaTokens = remember(name, description) {
        DefaultTokenizer.countTokens(name) + DefaultTokenizer.countTokens(description)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 480.dp)
                .wrapContentSize()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
                            .clickable { showImageMenu = true }
                    ) {
                        if (avatarData != null) {
                            AsyncImage(
                                model = avatarData,
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "Add Avatar Photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp).align(Alignment.Center)
                            )
                        }
                    }

                    AvatarDropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false },
                        hasAvatar = avatarData != null,
                        onAddOrUpdate = { pickImage() },
                        onView = { showFullImage = true },
                        onRemove = { avatarData = null }
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("${DefaultTokenizer.countTokens(name)} tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Description", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("${DefaultTokenizer.countTokens(description)} tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total: $totalPersonaTokens Tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name, description.ifBlank { null }, avatarData) },
                        enabled = name.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        if (showFullImage && avatarData != null) {
            FullscreenImageViewer(avatarData = avatarData!!, onDismiss = { showFullImage = false })
        }
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
package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.network.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    labelStep: String,
    allModels: List<ModelInfo>,
    filteredModels: List<ModelInfo>,
    modelSearch: String,
    selectedModelFullId: String,
    onModelSearchChange: (String) -> Unit,
    onModelSelected: (ModelInfo) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (selectedModelFullId.isEmpty() || modelSearch.isBlank()) "$labelStep. Select Model (Required)" else "$labelStep. Model Selected",
            style = MaterialTheme.typography.labelMedium,
            color = if (selectedModelFullId.isEmpty() || modelSearch.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        var modelDropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = modelDropdownExpanded,
            onExpandedChange = { modelDropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = modelSearch,
                onValueChange = {
                    onModelSearchChange(it)
                    modelDropdownExpanded = true
                },
                label = { Text("Model Name (Required)") },
                trailingIcon = {
                    IconButton(onClick = { modelDropdownExpanded = !modelDropdownExpanded }) {
                        Icon(Icons.Default.Search, null)
                    }
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Tab)) {
                            if (modelDropdownExpanded && filteredModels.isNotEmpty()) {
                                val model = filteredModels.first()
                                onModelSelected(model)
                                modelDropdownExpanded = false
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    },
                isError = modelSearch.isBlank() || selectedModelFullId.isEmpty(),
                singleLine = true
            )
            if (filteredModels.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                    modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 280.dp)
                ) {
                    filteredModels.take(50).forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text("${model.provider} | ${model.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                onModelSelected(model)
                                modelDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// Multi-select of the cloud backends that may serve the selected model. The
// selection is kept as-is even when it includes a provider that does not
// serve this model; the warning explains the consequence instead of the app
// silently rewriting the config.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostProvidersPicker(
    labelStep: String,
    providers: List<String>,
    selectedProviders: Set<String>,
    zdrKnown: Boolean,
    zdrProviders: Set<String>,
    isLoading: Boolean,
    unservedProviders: Set<String>,
    onToggleProvider: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (selectedProviders.isEmpty()) "$labelStep. Select Host Providers (Optional)" else "$labelStep. Host Providers Selected",
            style = MaterialTheme.typography.labelMedium,
            color = if (selectedProviders.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it }
        ) {
            OutlinedTextField(
                value = if (selectedProviders.isEmpty()) "Endpoint default" else selectedProviders.sorted().joinToString(" + "),
                onValueChange = { },
                readOnly = true,
                label = { Text("Host Providers") },
                placeholder = { Text("Endpoint default") },
                supportingText = {
                    Text(
                        "Which backends may serve this model. Selecting several lets OpenRouter route between them. ZDR = prompts are not stored or trained on; No ZDR = prompts may be retained or shared.",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                trailingIcon = {
                    if (selectedProviders.isNotEmpty()) {
                        IconButton(onClick = onClearAll) {
                            Icon(Icons.Default.Clear, "Clear host providers")
                        }
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    }
                },
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 280.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Endpoint default (auto-route)") },
                    onClick = {
                        onClearAll()
                        menuExpanded = false
                    }
                )
                if (isLoading) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Loading providers…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {}
                    )
                }
                // Cap the composed items defensively: a provider returning
                // thousands of models must not lay out every one of them,
                // whatever the caller passed in.
                providers.take(50).forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = provider in selectedProviders,
                                    onCheckedChange = null
                                )
                                Text(provider, style = MaterialTheme.typography.bodyMedium)
                                if (zdrKnown) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    ZdrLabel(isZdr = provider in zdrProviders)
                                }
                            }
                        },
                        onClick = {
                            onToggleProvider(provider)
                        }
                    )
                }
            }
        }

        if (unservedProviders.isNotEmpty()) {
            Text(
                "${unservedProviders.joinToString(" + ")} does not serve this model — requests will not be routed to it. Prompts may end up with another provider (possibly sharing your data).",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// Pill badge showing the privacy status of a provider.
@Composable
private fun ZdrLabel(isZdr: Boolean) {
    val color = if (isZdr) ZdrGreen else MaterialTheme.colorScheme.error
    Surface(
        color = color,
        contentColor = androidx.compose.ui.graphics.Color.White,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Text(
            if (isZdr) "ZDR" else "No ZDR",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// Shared "privacy-safe" color used for the [ZDR] label.
internal val ZdrGreen = Color(0xFF43A047)

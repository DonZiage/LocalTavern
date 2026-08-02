package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.network.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    isCloudInference: Boolean,
    labelStep: String,
    allModels: List<ModelInfo>,
    providerSuggestions: List<String>,
    filteredModels: List<ModelInfo>,
    modelProviderFilter: String,
    modelSearch: String,
    selectedModelFullId: String,
    onProviderFilterChange: (String) -> Unit,
    onModelSearchChange: (String) -> Unit,
    onModelSelected: (ModelInfo) -> Unit
) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text(
            if (selectedModelFullId.isEmpty() || modelSearch.isBlank()) "$labelStep. Select Model (Required)" else "$labelStep. Model Selected",
            style = MaterialTheme.typography.labelMedium,
            color = if (selectedModelFullId.isEmpty() || modelSearch.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        if (allModels.isNotEmpty()) {
            var providerDropdownExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = providerDropdownExpanded,
                onExpandedChange = { providerDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = modelProviderFilter,
                    onValueChange = {
                        onProviderFilterChange(it)
                        providerDropdownExpanded = true
                    },
                    label = { Text("Model Provider") },
                    placeholder = { Text("All Providers") },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Tab)) {
                                if (providerDropdownExpanded && providerSuggestions.isNotEmpty()) {
                                    onProviderFilterChange(providerSuggestions.first())
                                    providerDropdownExpanded = false
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        },
                    trailingIcon = {
                        if (modelProviderFilter.isNotEmpty()) {
                            IconButton(onClick = { onProviderFilterChange("") }) {
                                Icon(Icons.Default.Clear, "Clear filter")
                            }
                        } else {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded)
                        }
                    },
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = providerDropdownExpanded,
                    onDismissRequest = { providerDropdownExpanded = false },
                    modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 240.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("All Providers") },
                        onClick = {
                            onProviderFilterChange("")
                            providerDropdownExpanded = false
                        }
                    )
                    providerSuggestions.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider) },
                            onClick = {
                                onProviderFilterChange(provider)
                                providerDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

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
                    filteredModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text("${model.provider} | ${model.id}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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

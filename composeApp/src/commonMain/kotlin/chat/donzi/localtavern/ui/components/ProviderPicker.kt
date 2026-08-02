package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPicker(
    selectedProvider: String,
    onProviderSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var mainProviderExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = mainProviderExpanded,
        onExpandedChange = { mainProviderExpanded = it }
    ) {
        OutlinedTextField(
            value = selectedProvider,
            onValueChange = {},
            readOnly = true,
            placeholder = { Text("Select Provider") },
            label = { Text("1. Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mainProviderExpanded) },
            modifier = modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = mainProviderExpanded,
            onDismissRequest = { mainProviderExpanded = false },
            modifier = Modifier.exposedDropdownSize().requiredHeightIn(max = 240.dp)
        ) {
            ProviderCatalog.providerSections.forEach { (sectionName, providersInSection) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            sectionName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
                providersInSection.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider) },
                        onClick = {
                            onProviderSelected(provider)
                            mainProviderExpanded = false
                        }
                    )
                }
            }
        }
    }
}

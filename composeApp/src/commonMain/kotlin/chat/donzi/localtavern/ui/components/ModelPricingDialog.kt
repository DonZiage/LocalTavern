package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import chat.donzi.localtavern.data.database.PricingRepository
import chat.donzi.localtavern.data.pricing.PricingCatalog
import chat.donzi.localtavern.domain.ApiConfig
import kotlinx.coroutines.launch

// Edits the per-model pricing override for the given connection. The override
// row is keyed by (provider, exact model name) and wins over the bundled
// catalog; deleting it falls back to the bundled price.
@Composable
fun ModelPricingDialog(
    connection: ApiConfig,
    pricingRepository: PricingRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val model = connection.model.orEmpty()
    val catalogPrice = remember(connection.provider, model) { PricingCatalog.lookup(connection.provider, model) }

    var inputPrice by remember(connection.id) { mutableStateOf("") }
    var outputPrice by remember(connection.id) { mutableStateOf("") }
    var isLoading by remember(connection.id) { mutableStateOf(true) }

    // Load the stored override (if any) as the starting point; otherwise
    // prefill from the bundled catalog so saving is a simple adjust.
    LaunchedEffect(connection.id) {
        val override = pricingRepository.getPricing(connection.provider, model)
        inputPrice = override?.inputPerMillion?.toString() ?: catalogPrice?.inputPerMillion?.toString() ?: ""
        outputPrice = override?.outputPerMillion?.toString() ?: catalogPrice?.outputPerMillion?.toString() ?: ""
        isLoading = false
    }

    val inputValue = inputPrice.toDoubleOrNull()
    val outputValue = outputPrice.toDoubleOrNull()
    val hasOverride = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasOverride.value = pricingRepository.getPricing(connection.provider, model) != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        title = { Text("Model Pricing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "USD per 1M tokens for ${model.ifBlank { connection.name }}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    OutlinedTextField(
                        value = inputPrice,
                        onValueChange = { inputPrice = it },
                        label = { Text("Input $/1M tokens") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = inputPrice.isNotBlank() && inputValue == null
                    )
                    OutlinedTextField(
                        value = outputPrice,
                        onValueChange = { outputPrice = it },
                        label = { Text("Output $/1M tokens") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = outputPrice.isNotBlank() && outputValue == null
                    )
                    Text(
                        text = "Bundled: ${PricingCatalog.lookup(connection.provider, model)?.let { "input ${it.inputPerMillion} / output ${it.outputPerMillion}" } ?: "no bundled price (leave blank to estimate as free/unknown)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (inputValue != null && outputValue != null) {
                            pricingRepository.upsertPricing(
                                chat.donzi.localtavern.data.database.ModelPricing(
                                    provider = connection.provider,
                                    modelPattern = model,
                                    inputPerMillion = inputValue,
                                    outputPerMillion = outputValue,
                                    currency = "USD"
                                )
                            )
                        } else {
                            // Blank/invalid input clears the override and falls
                            // back to the bundled catalog.
                            pricingRepository.deletePricing(connection.provider, model)
                        }
                        onSaved()
                        onDismiss()
                    }
                },
                enabled = !isLoading
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (hasOverride.value) {
                            pricingRepository.deletePricing(connection.provider, model)
                            onSaved()
                        }
                        onDismiss()
                    }
                }
            ) {
                Text("Remove Override")
            }
        }
    )
}

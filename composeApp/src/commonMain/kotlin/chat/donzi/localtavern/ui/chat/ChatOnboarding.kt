package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class OnboardingStep {
    API, PERSONA, CHARACTER
}

// Empty-state welcome screen shown before any character is opened: lists the
// setup steps the user still has to complete (API profile, persona,
// character), renumbered so a missing earlier step does not break the
// ordinal display.
@Composable
fun ChatOnboarding(
    hasApiProfile: Boolean,
    hasPersona: Boolean,
    hasCharacter: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToPersonas: () -> Unit,
    onNavigateToCharacters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = "Welcome to LocalTavern",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            // Ordinal numbering: the visible steps are renumbered 1..N after
            // filtering (previously a missing API step would make the persona
            // card show "2 -").
            val visibleSteps = remember(hasApiProfile, hasPersona, hasCharacter) {
                listOf(
                    OnboardingStep.API,
                    OnboardingStep.PERSONA,
                    OnboardingStep.CHARACTER
                )
                    .filter { step ->
                        when (step) {
                            OnboardingStep.API -> !hasApiProfile
                            OnboardingStep.PERSONA -> !hasPersona
                            OnboardingStep.CHARACTER -> !hasCharacter
                        }
                    }
                    .mapIndexed { index, step -> step to index + 1 }
            }

            if (visibleSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    visibleSteps.forEach { (step, stepDisplayNumber) ->

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$stepDisplayNumber - ",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 6.dp)
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        when (step) {
                                            OnboardingStep.API -> onNavigateToSettings()
                                            OnboardingStep.PERSONA -> onNavigateToPersonas()
                                            OnboardingStep.CHARACTER -> onNavigateToCharacters()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (step) {
                                            OnboardingStep.API -> "Add an API connection in Settings"
                                            OnboardingStep.PERSONA -> "Introduce yourself in Personas"
                                            OnboardingStep.CHARACTER -> "Meet your first Character"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = when (step) {
                                            OnboardingStep.API -> Icons.Default.Settings
                                            OnboardingStep.PERSONA -> Icons.Default.Person
                                            OnboardingStep.CHARACTER -> Icons.Default.AccountBox
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Select a character from the menu side panel to begin your conversation.",
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = "Or type below to talk with the Assistant",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
    }
}

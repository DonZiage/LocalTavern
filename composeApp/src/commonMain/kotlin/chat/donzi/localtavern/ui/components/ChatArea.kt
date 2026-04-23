package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.database.MessageEntity

@Composable
fun ChatArea(
    activeCharacter: CharacterEntity?,
    messages: List<MessageEntity>,
    onSendMessage: (String) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onRegenerate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (activeCharacter == null && messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(
                        text = "Welcome to LocalTavern",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = "Select a character from the menu to begin your journey, or simply type below to talk with the Assistant.",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 28.sp
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { message ->
                    MessageBubble(
                        content = message.content,
                        isUser = message.role == "user",
                        onEdit = { newContent -> onEditMessage(message.id, newContent) }
                    )
                }
            }
        }

        ChatInputBar(
            onSendMessage = onSendMessage,
            onRegenerate = onRegenerate,
            canRegenerate = messages.lastOrNull()?.role == "assistant"
        )
    }
}

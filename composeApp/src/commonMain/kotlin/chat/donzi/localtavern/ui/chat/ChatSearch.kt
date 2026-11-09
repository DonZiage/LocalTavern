package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Message
import kotlinx.coroutines.delay

// Pure search-matching logic for the in-conversation search. Kept as plain
// top-level functions so they are unit-testable without Compose.

// Message ids whose content contains [query] (case-insensitive substring),
// in timeline order. Blank queries match nothing.
fun findSearchMatchIds(messages: List<Message>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.filter { it.content.contains(q, ignoreCase = true) }.map { it.id }
}

fun nextSearchMatchIndex(current: Int, total: Int): Int =
    if (total <= 0) 0 else (current + 1) % total

fun previousSearchMatchIndex(current: Int, total: Int): Int =
    if (total <= 0) 0 else (current - 1 + total) % total

// The match currently in focus, or null when there are no matches.
// The index is clamped so a shrinking match list can never point past the end.
fun currentSearchMatchId(matches: List<String>, currentIndex: Int): String? =
    if (matches.isEmpty()) null else matches[currentIndex.coerceIn(0, matches.lastIndex)]

// "3 / 12" for the bar, "0 / 0" when there are no matches.
fun searchMatchPositionText(currentIndex: Int, total: Int): String =
    if (total <= 0) "0 / 0" else "${currentIndex.coerceIn(0, total - 1) + 1} / $total"

// Bar shown above the message list while the conversation search is
// active: the query field plus prev/next navigation, the match counter
// and a close (X) that leaves search. Opening it must SHOW the keyboard
// (the field requests focus); closing must hide it again.
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    matchCount: Int,
    currentIndex: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        // The field may not be attached yet while it animates in;
        // requesting focus immediately can silently no-op.
        delay(50)
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search messages") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Text(
            text = searchMatchPositionText(currentIndex, matchCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(
            onClick = onPrev,
            enabled = matchCount > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(
            onClick = onNext,
            enabled = matchCount > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(
            onClick = {
                keyboardController?.hide()
                focusManager.clearFocus()
                onClose()
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close search")
        }
    }
}

package chat.donzi.localtavern.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

@Composable
fun parseMarkdownToAnnotatedString(text: String, defaultColor: Color): AnnotatedString {
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    return remember(text, defaultColor, codeBackgroundColor) {
        buildAnnotatedString {
            // Backticks are excluded from bold/italic bodies so inline code is
            // never swallowed inside emphasis (e.g. "**use `x` here**").
            val pattern = """(`[^`\n]+`|\*\*\*[^*`\n]+\*\*\*|\*\*[^*`\n]+\*\*|\*[^*`\n]+\*|(?<![A-Za-z0-9_])_[^_`\n]+_(?![A-Za-z0-9_]))""".toRegex()
            var lastIndex = 0

            pattern.findAll(text).forEach { matchResult ->
                if (matchResult.range.first > lastIndex) {
                    append(text.substring(lastIndex, matchResult.range.first))
                }

                val token = matchResult.value
                when {
                    token.startsWith("`") && token.endsWith("`") -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackgroundColor,
                                fontSize = 14.sp
                            )
                        ) {
                            append(token.removeSurrounding("`"))
                        }
                    }
                    token.startsWith("***") && token.endsWith("***") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(token.removeSurrounding("***"))
                        }
                    }
                    token.startsWith("**") && token.endsWith("**") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(token.removeSurrounding("**"))
                        }
                    }
                    token.startsWith("*") && token.endsWith("*") -> {
                        val inner = token.substring(1, token.length - 1)
                        // Reject pseudo-emphasis like "3 * 4 * 5": real emphasis
                        // does not wrap in whitespace and carries a word.
                        if (inner.isNotBlank() && !inner.startsWith(" ") && !inner.endsWith(" ") && inner.any { it.isLetterOrDigit() }) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor.copy(alpha = 0.85f))) {
                                append(inner)
                            }
                        } else {
                            append(token)
                        }
                    }
                    // Every remaining match is an underscore-delimited italic token.
                    else -> {
                        val inner = token.removeSurrounding("_")
                        if (inner.isNotBlank() && !inner.startsWith(" ") && !inner.endsWith(" ") && inner.any { it.isLetterOrDigit() }) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor.copy(alpha = 0.85f))) {
                                append(inner)
                            }
                        } else {
                            append(token)
                        }
                    }
                }
                lastIndex = matchResult.range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }
}

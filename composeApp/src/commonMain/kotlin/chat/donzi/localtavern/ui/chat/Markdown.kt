package chat.donzi.localtavern.ui.chat

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Block-level markdown renderer for message bubbles.
 *
 * Produces one [MarkdownBlock] per markdown block (paragraph, heading, code
 * fence, list, blockquote, horizontal rule), each fully styled via SpanStyle
 * so the caller only needs to lay out a Column of Texts. Links render as
 * accent-colored underlined labels (not clickable); table syntax is left as
 * literal text.
 *
 * The parser is deliberately lenient: LLM output is rarely spec-perfect, and
 * plain text must always render as readable paragraphs.
 */
data class MarkdownBlock(
    val text: AnnotatedString,
    val isCodeBlock: Boolean = false
)

/** Colors the renderer needs; kept as a plain data holder so parsing is
 *  testable outside composition. */
data class MarkdownStyle(
    val textColor: Color,
    val accentColor: Color,
    val codeBackground: Color
)

@Composable
fun rememberMarkdown(text: String, defaultColor: Color): List<MarkdownBlock> {
    val accentColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, defaultColor, accentColor, codeBackground) {
        parseMarkdown(text, MarkdownStyle(defaultColor, accentColor, codeBackground))
    }
}

fun parseMarkdown(text: String, style: MarkdownStyle): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split('\n')
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MarkdownBlock(renderInline(paragraph.toString().trim(), style, SpanStyle())))
        }
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        when {
            trimmed.isEmpty() -> flushParagraph()

            // Fenced code block: everything until the closing fence is raw.
            trimmed.startsWith("```") -> {
                flushParagraph()
                val content = StringBuilder()
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                    content.append(lines[j]).append('\n')
                    j++
                }
                i = j + 1
                blocks.add(renderCodeBlock(content.toString().trimEnd('\n'), style))
                continue
            }

            // Thematic breaks: ---, ***, ___, ### (3+). Checked before headings
            // so an all-hash line is a rule, not an empty heading.
            isThematicBreak(trimmed) -> {
                flushParagraph()
                blocks.add(MarkdownBlock(buildAnnotatedString {
                    withStyle(SpanStyle(color = style.textColor.copy(alpha = 0.3f))) { append("— — —") }
                }))
            }

            // ATX headings: # through ###### followed by a space (or EOL).
            headingOf(trimmed) != null -> {
                flushParagraph()
                val (level, headingText) = headingOf(trimmed)!!
                if (headingText.isNotBlank()) {
                    blocks.add(MarkdownBlock(renderInline(headingText, style, headingStyle(level))))
                }
            }

            // Blockquote: consecutive ">" lines, one "│" marker per line.
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size) {
                    val line = lines[i].trimStart()
                    if (!line.startsWith(">")) break
                    quote.append(line.removePrefix(">").removePrefix(" ")).append('\n')
                    i++
                }
                val quoteText = quote.toString().trim().replace("\n", "\n│ ")
                if (quoteText.isNotBlank()) {
                    blocks.add(MarkdownBlock(
                        renderInline(
                            "│ $quoteText",
                            style,
                            SpanStyle(fontStyle = FontStyle.Italic, color = style.textColor.copy(alpha = 0.85f))
                        )
                    ))
                }
                continue
            }

            // Bullet lists: consecutive "- ", "* ", "+ " lines.
            BULLET_ITEM.containsMatchIn(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (!BULLET_ITEM.containsMatchIn(line)) break
                    val marker = line.takeWhile { it == '-' || it == '*' || it == '+' }
                    items.add(line.removePrefix(marker).trimStart())
                    i++
                }
                blocks.add(MarkdownBlock(renderInline(
                    items.joinToString("\n") { "•  $it" }, style, SpanStyle()
                )))
                continue
            }

            // Ordered lists: consecutive "1. " / "1) " lines.
            ORDERED_ITEM.containsMatchIn(trimmed) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (!ORDERED_ITEM.containsMatchIn(line)) break
                    val number = line.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                    items.add("$number.  ${line.substringAfter('.').trimStart()}")
                    i++
                }
                blocks.add(MarkdownBlock(renderInline(items.joinToString("\n"), style, SpanStyle())))
                continue
            }

            else -> paragraph.append(raw).append('\n')
        }
        i++
    }
    flushParagraph()
    return blocks
}

// ---------------------------------------------------------------------------
// Inline rendering
// ---------------------------------------------------------------------------

// Ordered so longer spans win: code, strikethrough, links, bold-italic, bold,
// underscore-italic, single-star italic. Single-star emphasis is matched last
// so "**bold**" is never split into two "*" italics.
private val INLINE_REGEX = Regex(
    """(`[^`\n]+`|~~[^~\n]+~~|\[[^\]\n]+]\([^)\s]+\)|\*\*\*[^*\n]+?\*\*\*|\*\*[^*\n]+?\*\*|(?<![A-Za-z0-9_])_[^_`\n]+_(?![A-Za-z0-9_])|\*[^*\n]+?\*)"""
)

private data class InlineSpan(val text: String, val span: SpanStyle)

private fun renderInline(text: String, style: MarkdownStyle, baseStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        fun appendRun(part: String) {
            if (baseStyle == SpanStyle()) append(part) else withStyle(baseStyle) { append(part) }
        }
        var lastIndex = 0
        INLINE_REGEX.findAll(text).forEach { match ->
            if (match.range.first > lastIndex) appendRun(text.substring(lastIndex, match.range.first))
            val span = inlineSpan(match.value, style)
            if (span != null) {
                withStyle(baseStyle.merge(span.span)) { append(span.text) }
            } else {
                appendRun(match.value)
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) appendRun(text.substring(lastIndex))
    }

private fun inlineSpan(token: String, style: MarkdownStyle): InlineSpan? = when {
    token.startsWith("`") && token.endsWith("`") && token.length >= 2 ->
        InlineSpan(
            token.removeSurrounding("`"),
            SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground)
        )
    token.startsWith("~~") && token.endsWith("~~") ->
        InlineSpan(token.removeSurrounding("~~"), SpanStyle(textDecoration = TextDecoration.LineThrough))
    token.startsWith("[") && token.endsWith(")") ->
        // [label](url): the URL is not shown or opened, so only the label is
        // rendered, styled as a link.
        InlineSpan(
            token.substringAfter('[').substringBeforeLast(']'),
            SpanStyle(color = style.accentColor, textDecoration = TextDecoration.Underline)
        )
    token.startsWith("***") && token.endsWith("***") ->
        InlineSpan(
            token.removeSurrounding("***"),
            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        )
    token.startsWith("**") && token.endsWith("**") ->
        InlineSpan(token.removeSurrounding("**"), SpanStyle(fontWeight = FontWeight.Bold))
    token.startsWith("_") && token.endsWith("_") -> emphasize(token.removeSurrounding("_"), style)
    token.startsWith("*") && token.endsWith("*") -> emphasize(token.removeSurrounding("*"), style)
    else -> null
}

private fun emphasize(inner: String, style: MarkdownStyle): InlineSpan? {
    // Reject pseudo-emphasis like "3 * 4 * 5": real emphasis does not wrap in
    // whitespace and carries a word.
    if (inner.isBlank() || inner.startsWith(" ") || inner.endsWith(" ") || !inner.any { it.isLetterOrDigit() }) {
        return null
    }
    return InlineSpan(inner, SpanStyle(fontStyle = FontStyle.Italic, color = style.textColor.copy(alpha = 0.85f)))
}

// ---------------------------------------------------------------------------
// Block helpers
// ---------------------------------------------------------------------------

private val BULLET_ITEM = Regex("""^[-*+]\s+\S""")
private val ORDERED_ITEM = Regex("""^\d+[.)]\s+\S""")
private val THEMATIC_BREAK = Regex("""(?:#{3,}|\*{3,}|_{3,}|-{3,})\s*""")

private fun headingOf(trimmed: String): Pair<Int, String>? {
    var level = 0
    while (level < trimmed.length && trimmed[level] == '#') level++
    if (level == 0 || level > 6) return null
    if (level < trimmed.length && trimmed[level] != ' ') return null
    return level to trimmed.substring(level).trim()
}

private fun isThematicBreak(trimmed: String): Boolean = THEMATIC_BREAK.matches(trimmed)

private fun headingStyle(level: Int): SpanStyle = SpanStyle(
    fontSize = when (level) {
        1 -> 21.sp
        2 -> 19.sp
        3 -> 18.sp
        4 -> 17.sp
        else -> 16.sp
    },
    fontWeight = FontWeight.Bold
)

private fun renderCodeBlock(content: String, style: MarkdownStyle): MarkdownBlock {
    val text = buildAnnotatedString {
        if (content.isNotBlank()) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground)) {
                append(content)
            }
        }
    }
    return MarkdownBlock(text, isCodeBlock = true)
}

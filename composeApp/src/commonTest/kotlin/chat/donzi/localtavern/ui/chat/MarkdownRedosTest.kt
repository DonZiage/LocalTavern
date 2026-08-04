package chat.donzi.localtavern.ui.chat

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

// Red-team probe: a long run of "[" must not cause quadratic regex
// backtracking in the inline renderer. LLM output is untrusted (prompt
// injection / malicious model), so parseMarkdown must stay near-linear.
class MarkdownRedosTest {

    private val style = MarkdownStyle(
        textColor = Color(0xFF000000),
        accentColor = Color(0xFF123456),
        codeBackground = Color(0xFFEEEEEE)
    )

    @Test
    fun longRunOfOpenBrackets_parsesInLinearTime() {
        val attack = "[".repeat(100_000)
        val start = System.nanoTime()
        val blocks = parseMarkdown(attack, style)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("REDOS-PROBE elapsedMs=$elapsedMs blocks=${blocks.size}")
        assertTrue(
            elapsedMs < 2_000,
            "parseMarkdown took ${elapsedMs}ms on 100k '[' chars (quadratic backtracking)"
        )
    }
}

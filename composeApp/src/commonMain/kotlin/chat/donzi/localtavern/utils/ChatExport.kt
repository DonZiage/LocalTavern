package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Message

/**
 * Chat transcript export: renders a session's visible timeline as Markdown or
 * plain text so users can save/share it through the platform save-file flow.
 *
 * Pure formatting (no I/O, no platform code) so it is unit-testable in
 * commonTest. Image-carrying messages are annotated with a placeholder line;
 * the bytes themselves are never embedded.
 */
object ChatExport {

    /** Renders [messages] as a Markdown transcript. */
    fun formatMarkdown(
        sessionTitle: String?,
        characterName: String,
        personaName: String,
        messages: List<Message>
    ): String {
        val sb = StringBuilder()
        val title = sessionTitle?.takeIf { it.isNotBlank() } ?: "Chat with $characterName"
        sb.append("# ").append(title).append("\n\n")
        messages.forEach { msg ->
            val speaker = if (msg.role == "user") personaName.ifBlank { "User" } else characterName.ifBlank { "Assistant" }
            sb.append("## ").append(speaker).append("\n\n")
            val body = msg.content.ifBlank { "*[no text]*" }
            sb.append(body).append("\n\n")
            if (msg.images.isNotEmpty() || msg.imageRefs.isNotEmpty()) {
                val count = msg.images.size.coerceAtLeast(msg.imageRefs.size)
                sb.append("*[").append(count).append(if (count == 1) " image" else " images")
                    .append(" attached]*\n\n")
            }
            msg.reasoningText?.takeIf { it.isNotBlank() }?.let { reasoning ->
                sb.append("<details><summary>Reasoning</summary>\n\n")
                    .append(reasoning).append("\n\n</details>\n\n")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** Renders [messages] as plain text (chat logs, clipboard-unfriendly targets). */
    fun formatText(
        sessionTitle: String?,
        characterName: String,
        personaName: String,
        messages: List<Message>
    ): String {
        val sb = StringBuilder()
        val title = sessionTitle?.takeIf { it.isNotBlank() } ?: "Chat with $characterName"
        sb.append(title).append("\n")
        sb.append("=".repeat(title.length)).append("\n\n")
        messages.forEach { msg ->
            val speaker = if (msg.role == "user") personaName.ifBlank { "User" } else characterName.ifBlank { "Assistant" }
            sb.append(speaker).append(": ")
            val body = msg.content.ifBlank { "[no text]" }
            // Keep multi-line messages readable: continuation lines are indented.
            sb.append(body.replace("\n", "\n  "))
            if (msg.images.isNotEmpty() || msg.imageRefs.isNotEmpty()) {
                val count = msg.images.size.coerceAtLeast(msg.imageRefs.size)
                sb.append(" [").append(count).append(if (count == 1) " image" else " images").append(" attached]")
            }
            sb.append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** File name for a transcript export (sanitized, capped, Markdown suffix). */
    fun transcriptFileName(sessionTitle: String?, characterName: String): String {
        val base = sessionTitle?.takeIf { it.isNotBlank() } ?: characterName.takeIf { it.isNotBlank() } ?: "chat"
        val sanitized = base
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "chat" }
            .take(80)
        return "$sanitized.md"
    }
}

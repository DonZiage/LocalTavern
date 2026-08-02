package chat.donzi.localtavern.utils

interface Tokenizer {
    fun countTokens(text: String): Int
    fun truncateByTokens(text: String, maxTokens: Int): String
}


object DefaultTokenizer : Tokenizer {
    private const val CHARS_PER_TOKEN = 4

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return text.length / CHARS_PER_TOKEN
    }

    override fun truncateByTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val maxChars = maxTokens * CHARS_PER_TOKEN
        if (text.length <= maxChars) return text
        var end = maxChars
        // Avoid splitting a UTF-16 surrogate pair (e.g. emoji) at the cut point.
        if (end > 0 && end < text.length &&
            text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
        ) {
            end -= 1
        }
        return text.substring(0, end)
    }
}
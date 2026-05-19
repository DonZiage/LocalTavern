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
        return if (text.length > maxChars) {
            text.substring(0, maxChars)
        } else {
            text
        }
    }
}
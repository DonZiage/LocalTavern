package chat.donzi.localtavern.utils

interface Tokenizer {
    fun countTokens(text: String): Int
    fun truncateByTokens(text: String, maxTokens: Int): String
}


object DefaultTokenizer : Tokenizer {
    private const val CHARS_PER_TOKEN = 4

    // CJK text is roughly one token per character (vs one per 4 chars for
    // Latin script); treating it like English undercounts the budget by up to
    // 4x, so the payload can overshoot the real context window.
    private fun Char.isCjk(): Boolean {
        val code = this.code
        return (code in 0x2E80..0x9FFF) || // CJK radicals, punctuation, unified ideographs
                (code in 0xAC00..0xD7AF) || // Hangul syllables
                (code in 0xF900..0xFAFF) || // CJK compatibility ideographs
                (code in 0xFF00..0xFF60)    // fullwidth forms
    }

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0
        var pendingUnits = 0
        for (char in text) {
            if (char.isCjk()) {
                // The pending non-CJK units round down to whole tokens, then
                // the CJK character itself costs one token.
                tokens += 1 + pendingUnits / CHARS_PER_TOKEN
                pendingUnits = 0
            } else {
                pendingUnits++
            }
        }
        tokens += pendingUnits / CHARS_PER_TOKEN
        return tokens
    }

    override fun truncateByTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (text.isEmpty()) return text
        if (countTokens(text) <= maxTokens) return text

        var tokens = 0
        var pendingUnits = 0
        var end = 0
        while (end < text.length) {
            val char = text[end]
            if (char.isCjk()) {
                val cost = 1 + pendingUnits / CHARS_PER_TOKEN
                if (tokens + cost > maxTokens) break
                tokens += cost
                pendingUnits = 0
            } else {
                if (pendingUnits + 1 >= CHARS_PER_TOKEN) {
                    if (tokens + 1 > maxTokens) break
                    tokens += 1
                    pendingUnits = 0
                } else {
                    pendingUnits++
                }
            }
            end++
        }
        // The cut may have landed between a UTF-16 surrogate pair (e.g. emoji).
        // Drop the high surrogate instead of pulling the low one in: including
        // it would leave a lone high surrogate and complete a 4-unit group the
        // loop never counted, exceeding the requested token budget.
        if (end > 0 && end < text.length &&
            text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
        ) {
            end -= 1
        }
        return text.substring(0, end.coerceAtMost(text.length))
    }
}
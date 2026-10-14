package chat.donzi.localtavern.utils

fun String.fuzzyScore(query: String): Int {
    if (query.isBlank()) return 100
    val target = this.lowercase()
    val q = query.lowercase()

    if (target == q) return 1000
    if (target.startsWith(q)) return 500
    if (target.contains(q)) return 200

    val cleanTarget = target.filter { it.isLetterOrDigit() }
    val cleanQ = q.filter { it.isLetterOrDigit() }
    // A query with no searchable characters (punctuation/symbols only) cannot
    // meaningfully match anything; without this guard it would score 150 for
    // every target ("" is contained in every string).
    if (cleanQ.isEmpty()) return 0
    if (cleanTarget.contains(cleanQ)) return 150

    var score = 0
    var qIdx = 0
    for (char in cleanTarget) {
        if (qIdx < cleanQ.length && char == cleanQ[qIdx]) {
            score += 10
            qIdx++
        }
    }
    // Cap the subsequence score below the contains-tier (200) so a long fuzzy
    // query can never outrank (or tie) an exact contains match.
    return if (qIdx == cleanQ.length) score.coerceAtMost(199) else 0
}

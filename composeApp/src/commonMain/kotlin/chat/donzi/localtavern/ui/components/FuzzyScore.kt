package chat.donzi.localtavern.ui.components

fun String.fuzzyScore(query: String): Int {
    if (query.isBlank()) return 100
    val target = this.lowercase()
    val q = query.lowercase()
    
    if (target == q) return 1000
    if (target.startsWith(q)) return 500
    if (target.contains(q)) return 200
    
    val cleanTarget = target.filter { it.isLetterOrDigit() }
    val cleanQ = q.filter { it.isLetterOrDigit() }
    if (cleanTarget.contains(cleanQ)) return 150

    var score = 0
    var qIdx = 0
    for (char in cleanTarget) {
        if (qIdx < cleanQ.length && char == cleanQ[qIdx]) {
            score += 10
            qIdx++
        }
    }
    return if (qIdx == cleanQ.length) score else 0
}

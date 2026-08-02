package chat.donzi.localtavern.domain

data class LorebookEntry(
    val id: Long? = null,
    val name: String,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val caseSensitive: Boolean = false,
    val insertionOrder: Long? = null,
    val position: String? = null,
    val priority: Long? = null,
    val comment: String? = null
)

data class Lorebook(
    val name: String? = null,
    val description: String? = null,
    val entries: List<LorebookEntry> = emptyList()
) {
    val enabledEntries: List<LorebookEntry>
        get() = entries.filter { it.enabled }
}

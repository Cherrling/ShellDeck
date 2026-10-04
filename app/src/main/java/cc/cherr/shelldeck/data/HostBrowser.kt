package cc.cherr.shelldeck.data

import java.util.Locale

enum class HostFilter(val label: String) { ALL("全部"), FAVORITES("收藏"), RECENT("最近") }

/** Exact/substring matches precede abbreviated matches. Stable ties never depend on list position. */
fun browseHosts(hosts: List<HostRecord>, query: String, filter: HostFilter): List<HostRecord> {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun match(word: String, field: String): Int? = when {
        field == word -> 0
        field.startsWith(word) -> 1
        field.contains(word) -> 2
        word.length < 3 -> null
        else -> {
            var cursor = 0
            for (char in field) if (cursor < word.length && char == word[cursor]) cursor++
            if (cursor == word.length) 3 else null
        }
    }
    return hosts.asSequence().filter {
        when (filter) { HostFilter.ALL -> true; HostFilter.FAVORITES -> it.favorite; HostFilter.RECENT -> it.lastUsedAt > 0 }
    }.mapNotNull { host ->
        val fields = listOf(host.label, host.hostname, host.username, host.port.toString()).map { it.lowercase(Locale.ROOT) }
        var score = 0
        for (word in words) score += fields.mapNotNull { match(word, it) }.minOrNull() ?: return@mapNotNull null
        host to score
    }.sortedWith(compareBy<Pair<HostRecord, Int>> { it.second }
        .thenByDescending { filter != HostFilter.RECENT && it.first.favorite }
        .thenByDescending { it.first.lastUsedAt }
        .thenBy { it.first.label.lowercase(Locale.ROOT) }.thenBy { it.first.id })
        .map { it.first }.toList()
}

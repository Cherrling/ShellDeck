package cc.cherr.shelldeck.sftp

/** Main-thread, session-local LRU; no credentials or disk persistence. */
class DirectoryCache(private val maxDirectories: Int = 16, private val maxEntries: Int = 20_000) {
    private val values = LinkedHashMap<String, List<SftpFiles.Entry>>(16, 0.75f, true)
    operator fun get(path: String): List<SftpFiles.Entry>? = values[path]
    fun put(path: String, entries: List<SftpFiles.Entry>) {
        values.remove(path)
        if (entries.size > maxEntries) return
        values[path] = entries
        while (values.size > maxDirectories || values.values.sumOf { it.size } > maxEntries) {
            values.remove(values.entries.first().key)
        }
    }
    fun clear() = values.clear()
}

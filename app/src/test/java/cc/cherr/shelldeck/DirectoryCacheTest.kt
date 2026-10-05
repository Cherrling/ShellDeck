package cc.cherr.shelldeck

import cc.cherr.shelldeck.sftp.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryCacheTest {
    private fun entries(n: Int) = List(n) { SftpFiles.Entry("$it", "/$it", false, false, 0) }
    @Test fun limitsBothDirectoriesAndEntriesAndEvictsLeastRecentlyUsed() {
        val cache = DirectoryCache(2, 3)
        cache.put("/a", entries(1)); cache.put("/b", entries(1))
        assertNotNull(cache["/a"])
        cache.put("/c", entries(1)); assertNull(cache["/b"])
        cache.put("/d", entries(3)); assertNull(cache["/a"]); assertNull(cache["/c"])
        assertEquals(3, cache["/d"]!!.size)
        cache.put("/huge", entries(4)); assertNull(cache["/huge"])
        cache.clear(); assertNull(cache["/d"])
    }
}

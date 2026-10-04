package cc.cherr.shelldeck

import cc.cherr.shelldeck.data.*
import org.junit.Assert.*
import org.junit.Test

class HostBrowserTest {
    private fun host(id: String, label: String, favorite: Boolean = false, used: Long = 0) = HostRecord().apply {
        this.id = id; this.label = label; hostname = "$id.example.com"; username = "deploy"; port = 2222
        this.favorite = favorite; lastUsedAt = used
    }
    @Test fun favoriteAndRecentFiltersHaveStableDifferentOrders() {
        val hosts = listOf(host("c", "same", used = 30), host("b", "same", true, 10), host("a", "same", true, 10), host("d", "never"))
        assertEquals(listOf("a", "b", "c", "d"), browseHosts(hosts, "", HostFilter.ALL).map { it.id })
        assertEquals(listOf("a", "b"), browseHosts(hosts, "", HostFilter.FAVORITES).map { it.id })
        assertEquals(listOf("c", "a", "b"), browseHosts(hosts, "", HostFilter.RECENT).map { it.id })
    }
    @Test fun searchesAcrossFieldsAndSupportsChineseAndAbbreviations() {
        val hosts = listOf(host("dev-server", "开发服务器"), host("prod", "Production"))
        assertEquals("dev-server", browseHosts(hosts, "开发 DEPLOY 2222", HostFilter.ALL).single().id)
        assertEquals("dev-server", browseHosts(hosts, "dvsrv", HostFilter.ALL).single().id)
        assertTrue(browseHosts(hosts, "no-such-host", HostFilter.ALL).isEmpty())
        assertEquals(2, browseHosts(hosts, "   ", HostFilter.ALL).size)
    }
    @Test fun exactResultsPrecedeFuzzyFavoritesAndInputIsNotMutated() {
        val fuzzy = host("d-e-v", "d-e-v", true, 100)
        val exact = host("dev", "dev")
        val input = listOf(fuzzy, exact)
        assertEquals(listOf(exact, fuzzy), browseHosts(input, "dev", HostFilter.ALL))
        assertEquals(listOf(fuzzy, exact), input)
    }
}

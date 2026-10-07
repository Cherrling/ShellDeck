package cc.cherr.shelldeck

import cc.cherr.shelldeck.terminal.TerminalLinks
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    @Test fun targetsRequireExplicitWebSchemeAndValidAuthority() {
        assertTrue(TerminalLinks.canOpen("https://example.com/a?q=中文"))
        assertTrue(TerminalLinks.canOpen("http://127.0.0.1:8080/"))
        assertTrue(TerminalLinks.canOpen("https://[::1]:8080/"))
        listOf("javascript:alert(1)", "intent://abc", "file:///etc/passwd", "https://", "https://a.test/\n", "https://a.test:99999/", "https://a.test\\@b.test", "https://a.test/%xx").forEach {
            assertFalse(it, TerminalLinks.canOpen(it))
        }
        assertEquals("https://a.test/foo(bar)", TerminalLinks.find("(https://a.test/foo(bar)).", 5))
        assertNull(TerminalLinks.find("https://a.test.", 14))
        assertNull(TerminalLinks.find("not a link", 2))
    }
}

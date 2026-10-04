package cc.cherr.shelldeck

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.cherr.shelldeck.ui.rememberSessionTitle
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionTitleDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun animatedTitlesAreCoalescedAndHiddenCardsCatchUpImmediately() {
        lateinit var terminal: TerminalController
        lateinit var listener: TerminalTransport.Listener
        val visible = mutableStateOf(true)
        val displayed = mutableListOf<String>()
        val transport = object : TerminalTransport {
            override fun start(size: TerminalSize, value: TerminalTransport.Listener) { listener = value; value.onReady() }
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
            override fun close() = Unit
        }
        ui.runOnUiThread {
            terminal = TerminalController(ui.activity.application) {}
            terminal.session = TerminalSession(transport, 100, terminal)
            terminal.session.updateSize(80, 24, 8, 16)
            ui.activity.setContent {
                if (visible.value) {
                    val title = rememberSessionTitle(terminal)
                    Text(title)
                    SideEffect { if (displayed.lastOrNull() != title) displayed.add(title) }
                }
            }
        }
        fun title(value: String) {
            val bytes = "\u001b]2;$value\u0007".toByteArray()
            listener.onBytes(bytes, bytes.size)
        }
        try {
            ui.waitForIdle()
            for (i in 0..29) { title("frame-$i"); Thread.sleep(20) }
            ui.waitUntil(3000) { displayed.lastOrNull() == "frame-29" }
            ui.runOnIdle { assertTrue("30 updates should not become 30 displayed titles", displayed.size <= 4) }
            ui.runOnIdle { visible.value = false }
            ui.waitForIdle()
            val count = displayed.size
            title("hidden-latest")
            Thread.sleep(600)
            ui.runOnIdle { assertEquals(count, displayed.size); assertEquals("hidden-latest", terminal.title); visible.value = true }
            ui.onNodeWithText("hidden-latest").assertIsDisplayed()
            val idleCount = displayed.size
            Thread.sleep(600)
            ui.runOnIdle { assertEquals(idleCount, displayed.size) }
        } finally { ui.runOnUiThread { terminal.close() } }
    }
}

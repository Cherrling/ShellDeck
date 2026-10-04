package cc.cherr.shelldeck

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import cc.cherr.shelldeck.keyboard.*
import cc.cherr.shelldeck.ui.ShellDeckTheme
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExtraKeysGestureDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun holdingShiftWhileTappingTabAndCancellingASwipeDoNotLatch() {
        lateinit var terminal: TerminalController
        val output = ByteArrayOutputStream()
        val transport = object : TerminalTransport {
            override fun start(size: TerminalSize, listener: TerminalTransport.Listener) { listener.onReady() }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count) }
            override fun resize(size: TerminalSize, applied: Runnable) { applied.run() }
            override fun close() = Unit
        }
        ui.runOnUiThread {
            terminal = TerminalController(ui.activity.application) {}
            terminal.session = TerminalSession(transport, 100, terminal)
            ui.activity.setContent { ShellDeckTheme { Column(Modifier.fillMaxSize()) {
                AndroidView(factory = terminal::createView, modifier = Modifier.weight(1f), onRelease = terminal::releaseView)
                ExtraKeysBar(KeyboardProfile.default(), terminal)
            } } }
        }
        try {
            ui.waitUntil(5000) { terminal.session.isReady }
            val shift = ui.onNodeWithContentDescription("SHIFT").fetchSemanticsNode().boundsInRoot.center
            val tab = ui.onNodeWithText("TAB").fetchSemanticsNode().boundsInRoot.center
            ui.onRoot().performTouchInput {
                down(0, shift); down(1, tab); up(1); up(0)
            }
            ui.runOnIdle {
                assertEquals("\u001b[Z", output.toString("UTF-8"))
                assertFalse(terminal.modifiers.active(ModifierKey.SHIFT))
            }
            ui.onRoot().performTouchInput {
                down(shift); moveTo(Offset(shift.x - 180f, shift.y), delayMillis = 100); up()
            }
            ui.runOnIdle { assertFalse(terminal.modifiers.active(ModifierKey.SHIFT)) }
        } finally { ui.runOnUiThread { terminal.close() } }
    }
}

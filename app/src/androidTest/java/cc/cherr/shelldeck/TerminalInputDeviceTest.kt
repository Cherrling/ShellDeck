package cc.cherr.shelldeck

import android.app.Application
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.keyboard.*
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TerminalInputDeviceTest {
    @Test fun imeAndExtraKeysShareShiftControlStateWithoutConsumingOnTerminalReplies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val output = ByteArrayOutputStream()
        lateinit var listener: TerminalTransport.Listener
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, callback: TerminalTransport.Listener) { listener = callback; callback.onReady() }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count) }
            override fun resize(size: TerminalSize, applied: Runnable) { applied.run() }
            override fun close() = Unit
        }
        lateinit var terminal: TerminalController
        lateinit var view: com.termux.view.TerminalView
        instrumentation.runOnMainSync {
            terminal = TerminalController(app) {}; terminal.session = TerminalSession(transport, 100, terminal)
            terminal.session.updateSize(80, 24, 8, 16); view = terminal.createView(app); view.layout(0, 0, 800, 600)
        }
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                val state = terminal.modifiers
                state.press(ModifierKey.SHIFT); state.release(ModifierKey.SHIFT, false)
                view.onCreateInputConnection(EditorInfo()).commitText("ab", 1)
                assertEquals("Ab", output.toString("UTF-8")); output.reset()
                state.press(ModifierKey.SHIFT); state.release(ModifierKey.SHIFT, true)
                repeat(2) { terminal.perform(KeyAction.Special(SpecialKey.TAB)) }
                assertEquals("\u001b[Z\u001b[Z", output.toString("UTF-8")); output.reset()
                assertTrue(state.active(ModifierKey.SHIFT)); state.clear()
                state.press(ModifierKey.CTRL); state.release(ModifierKey.CTRL, false)
                terminal.perform(KeyAction.Character("c"))
                assertEquals("\u0003", output.toString("UTF-8")); output.reset()
                state.press(ModifierKey.SHIFT); state.release(ModifierKey.SHIFT, false)
            }
            val query = "\u001b[6n".toByteArray()
            listener.onBytes(query, query.size)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(output.size() > 0)
                assertTrue(terminal.modifiers.active(ModifierKey.SHIFT))
                terminal.leave(); assertFalse(terminal.modifiers.active(ModifierKey.SHIFT))
            }
        } finally { instrumentation.runOnMainSync { terminal.releaseView(view); terminal.close() } }
    }
}

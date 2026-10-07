package cc.cherr.shelldeck

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.terminal.ShellTerminalView
import cc.cherr.shelldeck.terminal.TerminalLinks
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalLinksDeviceTest {
    @Test fun wrappedUrlsAndWidePrefixResolveTheTouchedCell() {
        val terminal = TerminalEmulator(object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 12, 5, 8, 16, 20, null)
        fun emit(s: String) { val bytes = s.toByteArray(); terminal.append(bytes, bytes.size) }
        emit("中 https://example.com/a")
        assertNull(TerminalLinks.at(terminal, 0, 0))
        assertEquals("https://example.com/a", TerminalLinks.at(terminal, 4, 0))
        assertEquals("https://example.com/a", TerminalLinks.at(terminal, 1, 1))
        emit("\r\nhttps://a.\r\ntest")
        assertNull(TerminalLinks.at(terminal, 1, 3)) // Hard line breaks must not join.
        emit("\r\n\u001b]8;;file://server/path\u0007文件\u001b]8;;\u0007")
        assertEquals("file://server/path", TerminalLinks.at(terminal, 1, 4))
        assertNull(TerminalLinks.at(terminal, -1, 4))
    }
    @Test fun actualTapLongPressSelectionAndMouseReporting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = ByteArrayOutputStream()
        lateinit var controller: TerminalController
        lateinit var view: ShellTerminalView
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, callback: TerminalTransport.Listener) = callback.onReady()
            override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count) }
            override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
            override fun close() = Unit
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun main(block: () -> Unit) { scenario.onActivity { block() } }
            fun append(text: String) {
                val bytes = text.toByteArray(); controller.session.emulator.append(bytes, bytes.size)
                controller.onTextChanged(controller.session)
            }
            fun touch(action: Int, down: Long) {
                val renderer = view.mRenderer
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, renderer.fontWidth * 3.5f,
                    renderer.fontLineSpacing / 2f, 0).let {
                    view.onTouchEvent(it); it.recycle()
                }
            }
            scenario.onActivity { activity ->
                controller = TerminalController(activity.application) {}
                controller.session = TerminalSession(transport, 5000, controller)
                activity.setContent { AndroidView(factory = { controller.createView(it).also { v -> view = v as ShellTerminalView } },
                    modifier = Modifier.fillMaxSize(), onRelease = controller::releaseView) }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(100)
            try {
                main { append("https://example.com/path") }
                var down = SystemClock.uptimeMillis()
                main { touch(MotionEvent.ACTION_DOWN, down); touch(MotionEvent.ACTION_UP, down) }
                Thread.sleep(400) // Termux confirms single taps after the double-tap window.
                main {
                    assertEquals("https://example.com/path", controller.pendingLink)
                    assertEquals(0, output.size())
                    controller.selectLinkText()
                    assertTrue(view.isSelectingText)
                }
                Thread.sleep(350) // Upstream protects a newly shown selection from immediate dismissal.
                main {
                    view.stopTextSelectionMode()
                    assertFalse(view.isSelectingText)
                    append("\u001b[2J\u001b[H\u001b]8;;https://example.org/target\u0007查看文档\u001b]8;;\u0007")
                    append("\u001b[?1000h\u001b[?1006h")
                }
                down = SystemClock.uptimeMillis()
                main { touch(MotionEvent.ACTION_DOWN, down); touch(MotionEvent.ACTION_UP, down) }
                Thread.sleep(400)
                main {
                    assertNull(controller.pendingLink)
                    assertTrue("mouse=${controller.session.emulator.isMouseTrackingActive}, selecting=${view.isSelectingText}, bytes=${output.toByteArray().contentToString()}", output.toString("UTF-8").contains("\u001b[<0;")); output.reset()
                }
                down = SystemClock.uptimeMillis()
                main { touch(MotionEvent.ACTION_DOWN, down) }
                Thread.sleep(650)
                main { touch(MotionEvent.ACTION_UP, down) }
                main {
                    assertEquals("https://example.org/target", controller.pendingLink)
                    assertEquals("long press must not click the remote TUI", 0, output.size())
                    controller.leave(); assertNull(controller.pendingLink)
                }
            } finally { main { controller.close() } }
        }
    }
}

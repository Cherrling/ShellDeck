package cc.cherr.shelldeck

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.terminal.*
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalInteractionDeviceTest {
    @Test fun pasteSelectionMouseScrollAndFrameUpdatesFollowTerminalModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = ByteArrayOutputStream()
        lateinit var terminal: TerminalController
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
                val bytes = text.toByteArray(); terminal.session.emulator.append(bytes, bytes.size)
                terminal.onTextChanged(terminal.session)
            }
            scenario.onActivity { activity ->
                terminal = TerminalController(activity.application) {}
                terminal.session = TerminalSession(transport, 5000, terminal)
                activity.setContent { AndroidView(factory = { terminal.createView(it).also { v -> view = v as ShellTerminalView } },
                    modifier = Modifier.fillMaxSize(), onRelease = terminal::releaseView) }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(100)
            try {
                main {
                    val emulator = terminal.session.emulator
                    append("\u001b[?2004h")
                    terminal.requestPaste("中文\nsecond")
                    assertNotNull(terminal.pendingPaste); assertEquals(0, output.size())
                    terminal.confirmPaste()
                    assertEquals("\u001b[200~中文\rsecond\u001b[201~", output.toString("UTF-8")); output.reset()
                    terminal.requestPaste("do not send\r")
                    terminal.dismissPaste(); assertEquals(0, output.size())
                    view.onCreateInputConnection(EditorInfo())!!.commitText("IME\n粘贴", 1)
                    assertNotNull(terminal.pendingPaste); assertEquals(0, output.size()); terminal.confirmPaste()
                    assertEquals("\u001b[200~IME\r粘贴\u001b[201~", output.toString("UTF-8")); output.reset()
                    terminal.requestPaste("x".repeat(PasteRequest.MAX_CHARS + 1))
                    assertNotNull(terminal.pasteError); assertEquals(0, output.size()); terminal.dismissPaste()
                    append("\u001b[?2004l")
                    terminal.requestPaste("plain\ntext"); terminal.confirmPaste()
                    assertEquals("plain\rtext", output.toString("UTF-8")); output.reset()
                    append("\u001b[2J\u001b[H中文AB")
                    assertEquals("中文", emulator.screen.getSelectedText(0, 0, 3, 0))
                    terminal.onCopyTextToClipboard(terminal.session, emulator.screen.getSelectedText(0, 0, 3, 0))
                    val clipboard = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java)
                    assertEquals("中文", clipboard.primaryClip!!.getItemAt(0).text.toString())
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", "middle\ntext"))
                    append("\u001b[?1000h\u001b[?1006h")
                    val now = SystemClock.uptimeMillis()
                    val mouse = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                    val coords = MotionEvent.PointerCoords().apply { x = 40f; y = 40f }
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(now, now, action, 1, arrayOf(mouse), arrayOf(coords), 0,
                            if (action == MotionEvent.ACTION_UP) 0 else MotionEvent.BUTTON_TERTIARY,
                            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0).let { view.onTouchEvent(it); it.recycle() }
                    }
                    assertEquals("middle\ntext", terminal.pendingPaste!!.text)
                    assertEquals("middle release must not emit a stray left mouse event", 0, output.size())
                    terminal.confirmPaste(); assertEquals("middle\rtext", output.toString("UTF-8")); output.reset()
                    append("\u001b[?1000l")
                    fun wheel(): MotionEvent {
                        val now = SystemClock.uptimeMillis()
                        val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                        val c = MotionEvent.PointerCoords().apply { x = 40f; y = 40f; setAxisValue(MotionEvent.AXIS_VSCROLL, 1f) }
                        return MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(p), arrayOf(c), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                    }
                    append((1..200).joinToString("\r\n") { "line-$it" }); view.onScreenUpdated(true)
                    wheel().let { view.onGenericMotionEvent(it); it.recycle() }
                    assertEquals(0, output.size()) // Main buffer scroll is local.
                    assertTrue(view.topRow < 0)
                    append("\u001b[?1049h\u001b[?1000h\u001b[?1006h")
                    wheel().let { view.onGenericMotionEvent(it); it.recycle() }
                    assertTrue(output.toString("UTF-8").contains("\u001b[<64;")); output.reset()
                    append("\u001b[?1000l")
                    wheel().let { view.onGenericMotionEvent(it); it.recycle() }
                    assertTrue(output.size() > 0); output.reset() // Alternate screen without mouse => arrows.
                    append("\u001b[?1049l")
                }
                Thread.sleep(100)
                var before = 0L
                main { before = view.screenUpdates; repeat(2000) { terminal.onTextChanged(terminal.session) } }
                Thread.sleep(100)
                main { assertEquals("burst merges to one frame", 1, view.screenUpdates - before) }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(100)
                instrumentation.runOnMainSync { before = view.screenUpdates; repeat(2000) { append("\u001b]2;hidden-$it\u0007") } }
                Thread.sleep(100)
                instrumentation.runOnMainSync { assertEquals("background renders none", before, view.screenUpdates); assertEquals("hidden-1999", terminal.title) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                Thread.sleep(150)
                main { assertTrue("resume catches up", view.screenUpdates > before) }
                main {
                    append("\u001b[2J\u001b[H中文AB\r\n第二行XYZ\r\n末行")
                    view.setTopRow(0)
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, view.mRenderer.fontWidth, view.mRenderer.fontLineSpacing / 2f, 0).let {
                        view.onTouchEvent(it); it.recycle()
                    }
                }
                Thread.sleep(650) // Real GestureDetector long press and floating selection handles.
                main {
                    assertTrue(view.isSelectingText)
                    assertTrue(view.selectedText.orEmpty().contains("中文"))
                    val field = com.termux.view.TerminalView::class.java.getDeclaredField("mTextSelectionCursorController").apply { isAccessible = true }
                    val cursor = field.get(view) as com.termux.view.textselection.TextSelectionCursorController
                    val handleField = cursor.javaClass.getDeclaredField("mEndHandle").apply { isAccessible = true }
                    val handle = handleField.get(cursor) as com.termux.view.textselection.TextSelectionHandleView
                    val now = SystemClock.uptimeMillis()
                    for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 0f, 0f),
                        Triple(MotionEvent.ACTION_MOVE, view.mRenderer.fontWidth * 5, view.mRenderer.fontLineSpacing * 6f),
                        Triple(MotionEvent.ACTION_UP, view.mRenderer.fontWidth * 5, view.mRenderer.fontLineSpacing * 6f))) {
                        MotionEvent.obtain(now, now, action, x, y, 0).let { handle.onTouchEvent(it); it.recycle() }
                    }
                    assertTrue("drag spans Chinese lines: ${view.selectedText}", view.selectedText.orEmpty().contains("第二行"))
                    view.stopTextSelectionMode()
                    terminal.requestPaste("pending\n"); terminal.close(); assertNull(terminal.pendingPaste); assertEquals(0, output.size())
                }
            } finally { main { terminal.close() } }
        }
    }
}

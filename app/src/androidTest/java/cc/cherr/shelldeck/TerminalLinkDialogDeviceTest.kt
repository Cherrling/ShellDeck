package cc.cherr.shelldeck

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.terminal.LinkDialog
import cc.cherr.shelldeck.ui.ShellDeckTheme
import com.termux.terminal.*
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class TerminalLinkDialogDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun displaysActualTargetCopiesAndOnlyOpensWebLinksAfterConfirmation() {
        lateinit var controller: TerminalController
        lateinit var view: TerminalView
        val opened = AtomicReference<Intent?>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, callback: TerminalTransport.Listener) = callback.onReady()
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
            override fun close() = Unit
        }
        ui.runOnUiThread {
            controller = TerminalController(ui.activity.application) {}
            controller.session = TerminalSession(transport, 5000, controller)
            ui.activity.setContent { ShellDeckTheme {
                AndroidView(factory = { controller.createView(it).also { v -> view = v } }, modifier = Modifier.fillMaxSize(), onRelease = controller::releaseView)
                LinkDialog(controller)
            } }
        }
        ui.waitForIdle()
        fun link(target: String) = ui.runOnIdle {
            val bytes = "\u001b[2J\u001b[H\u001b]8;;$target\u0007查看文档\u001b]8;;\u0007".toByteArray()
            controller.session.emulator.append(bytes, bytes.size)
            val now = android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, view.mRenderer.fontWidth,
                view.mRenderer.fontLineSpacing / 2f, 0).let {
                controller.onSingleTapUp(it); it.recycle()
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            link("https://example.org/real-target")
            ui.onNodeWithText("https://example.org/real-target").assertIsDisplayed()
            ui.onNode(isDialog()).saveScreenshot("terminal-link-dialog")
            assertNull(opened.get())
            ui.onNodeWithText("复制链接").performClick()
            ui.runOnIdle {
                val clip = ui.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                assertEquals("https://example.org/real-target", clip!!.getItemAt(0).text.toString())
                assertNull(controller.pendingLink)
            }
            link("file://remote/etc/config")
            ui.onNodeWithText("打开链接").assertDoesNotExist()
            ui.onNodeWithText("取消").performClick()
            link("javascript:alert(1)")
            ui.onNodeWithText("打开链接").assertDoesNotExist()
            ui.onNodeWithText("取消").performClick()
            link("https://example.org/confirmed")
            ui.onNodeWithText("打开链接").performClick()
            ui.runOnIdle {
                assertEquals("https://example.org/confirmed", opened.get()!!.dataString)
                assertTrue(opened.get()!!.hasCategory(Intent.CATEGORY_BROWSABLE))
                assertNull(controller.pendingLink)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            ui.runOnUiThread { controller.close() }
        }
    }
}

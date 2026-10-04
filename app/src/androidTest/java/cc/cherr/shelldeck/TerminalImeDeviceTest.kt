package cc.cherr.shelldeck

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TerminalImeDeviceTest {
    @Test fun keyboardAnimationProducesOneRemoteResizePerTransition() {
        val sizes = CopyOnWriteArrayList<TerminalSize>()
        lateinit var controller: TerminalController
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, listener: TerminalTransport.Listener) {
                sizes.add(initial)
                listener.onReady()
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            override fun resize(size: TerminalSize, onApplied: Runnable) { sizes.add(size); onApplied.run() }
            override fun close() = Unit
        }
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(description, condition())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                controller = TerminalController(activity.application) {}
                controller.session = TerminalSession(transport, 5000, controller)
                activity.setContent {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(terminalKeyboardInsets(true))) {
                        AndroidView(factory = controller::createView, modifier = Modifier.fillMaxSize(),
                            onRelease = controller::releaseView)
                    }
                }
            }
            try {
                waitFor("initial terminal size") { sizes.isNotEmpty() }
                Thread.sleep(500)
                repeat(3) {
                    val original = sizes.last()
                    val beforeShow = sizes.size
                    scenario.onActivity { controller.showKeyboard() }
                    waitFor("keyboard reduces rows") { sizes.last().rows < original.rows }
                    Thread.sleep(600) // Include the whole real IME animation, not just its first frame.
                    assertEquals("show must not stream intermediate PTY sizes", beforeShow + 1, sizes.size)
                    val beforeHide = sizes.size
                    scenario.onActivity { activity ->
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                    }
                    waitFor("keyboard restores rows") { sizes.last().rows == original.rows }
                    Thread.sleep(600)
                    assertEquals("hide must not stream intermediate PTY sizes", beforeHide + 1, sizes.size)
                }
            } finally { scenario.onActivity { controller.close() } }
        }
    }
}

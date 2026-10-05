package cc.cherr.shelldeck

import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.terminal.ShellTerminalView
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Test

/** Reproducible rendering workload, not a battery-consumption estimate. */
class TerminalWorkloadDeviceTest {
    @Test fun sustainedOutputKeepsParsingWhileHiddenWithoutRenderingOrIdlePolling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var terminal: TerminalController
        lateinit var view: ShellTerminalView
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, callback: TerminalTransport.Listener) = callback.onReady()
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
            override fun close() = Unit
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                terminal = TerminalController(activity.application) {}
                terminal.session = TerminalSession(transport, 5000, terminal)
                activity.setContent { AndroidView(factory = { terminal.createView(it).also { v -> view = v as ShellTerminalView } },
                    modifier = Modifier.fillMaxSize(), onRelease = terminal::releaseView) }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(100)
            fun phase(name: String, baseline: Boolean): Long {
                var frames = 0L
                instrumentation.runOnMainSync { frames = view.screenUpdates }
                val cpu = Process.getElapsedCpuTime()
                val start = SystemClock.uptimeMillis()
                repeat(500) { index ->
                    // A short burst per network delivery; title and TUI-like redraw in the same packet.
                    val bytes = "\u001b]2;work-$index\u0007\rrow-$index 中文\u001b[K".toByteArray()
                    instrumentation.runOnMainSync {
                        repeat(10) {
                            terminal.session.emulator.append(bytes, bytes.size)
                            if (baseline) view.onScreenUpdated(false) else terminal.onTextChanged(terminal.session)
                        }
                    }
                    Thread.sleep(10)
                }
                Thread.sleep(100)
                var updates = 0L
                instrumentation.runOnMainSync {
                    updates = view.screenUpdates - frames
                    assertEquals("work-499", terminal.title)
                }
                println("TERMINAL_WORKLOAD phase=$name inputUpdates=5000 viewUpdates=${if (baseline) 5000 else updates} cpuMs=${Process.getElapsedCpuTime() - cpu} elapsedMs=${SystemClock.uptimeMillis() - start}")
                return updates
            }
            try {
                phase("upstream-immediate", true)
                val visible = phase("visible-coalesced", false)
                assertTrue(visible in 1..1000)
                scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(100)
                assertEquals(0, phase("background", false))
                var before = 0L
                instrumentation.runOnMainSync { before = view.screenUpdates }
                Thread.sleep(1000)
                instrumentation.runOnMainSync { assertEquals(before, view.screenUpdates) }
                scenario.moveToState(Lifecycle.State.RESUMED); Thread.sleep(150)
                scenario.onActivity { assertTrue(view.screenUpdates > before) }
            } finally { instrumentation.runOnMainSync { terminal.close() } }
        }
    }
}

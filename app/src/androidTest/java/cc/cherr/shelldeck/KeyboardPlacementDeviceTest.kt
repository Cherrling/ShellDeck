package cc.cherr.shelldeck

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.keyboard.*
import cc.cherr.shelldeck.settings.*
import cc.cherr.shelldeck.terminal.*
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import com.termux.terminal.TerminalSize
import com.termux.view.TerminalView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class KeyboardPlacementDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsPageOffersAllPositions() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = androidx.lifecycle.ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("快捷键", substring = false).performScrollTo().performClick()
            for (position in KeyboardPlacement.entries) {
                ui.onNodeWithText(position.label).performScrollTo().performClick()
                ui.runOnIdle { assertEquals(position, model.settings.keyboardPlacement) }
            }
        } finally { ui.runOnIdle { model.updateSettings(original) } }
    }

    @Test fun settingsRoundTripAndLegacyDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context, "placement-test")
        try {
            for (position in KeyboardPlacement.entries) {
                val settings = AppSettings(keyboardPlacement = position)
                assertEquals(settings, SettingsStore.decode(SettingsStore.encode(settings)))
                store.save(settings)
                assertEquals(position, SettingsStore(context, "placement-test").read().keyboardPlacement)
            }
            val old = JSONObject(SettingsStore.encode(AppSettings())).apply { remove("keyboardPlacement") }
            assertEquals(KeyboardPlacement.BOTTOM, SettingsStore.decode(old.toString()).keyboardPlacement)
            old.put("keyboardPlacement", "invalid")
            assertThrows(IllegalArgumentException::class.java) { SettingsStore.decode(old.toString()) }
            assertEquals(KeyboardPlacement.BOTTOM, SettingsStore.decode(old.toString(), strict = false).keyboardPlacement)
        } finally { context.deleteSharedPreferences("placement-test") }
    }

    @Test fun movingBarPreservesViewSessionInputAndScrollsOnShortWindows() {
        var placement by mutableStateOf(KeyboardPlacement.BOTTOM)
        var landscape by mutableStateOf(false)
        var short by mutableStateOf(false)
        lateinit var terminal: TerminalController
        val starts = AtomicInteger()
        val output = ByteArrayOutputStream()
        val transport = object : TerminalTransport {
            override fun start(initial: TerminalSize, callback: TerminalTransport.Listener) { starts.incrementAndGet(); callback.onReady() }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { synchronized(output) { output.write(bytes, offset, count) } }
            override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
            override fun close() = Unit
        }
        fun findView(view: View): TerminalView? {
            if (view is TerminalView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findView(view.getChildAt(i))?.let { return it }
            return null
        }
        ui.runOnUiThread {
            terminal = TerminalController(ui.activity.application) {}
            terminal.session = TerminalSession(transport, 5000, terminal)
            ui.activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    Box(Modifier.fillMaxWidth().height(if (short) 180.dp else 500.dp)) {
                        TerminalWorkspace(terminal, KeyboardProfile.default(), KeyboardSizing(), placement, Modifier.fillMaxSize())
                    }
                }
            }
        }
        ui.waitUntil(10000) { terminal.session.isReady }
        var original: TerminalView? = null
        ui.runOnIdle { original = findView(ui.activity.window.decorView); assertNotNull(original) }
        fun check(expected: KeyboardPlacement) {
            ui.waitForIdle()
            val bar = ui.onNodeWithTag("extra-keys-bar").fetchSemanticsNode().boundsInRoot
            val surface = ui.onNodeWithTag("terminal-surface").fetchSemanticsNode().boundsInRoot
            when (expected) {
                KeyboardPlacement.BOTTOM -> assertEquals(surface.bottom, bar.top, 1f)
                KeyboardPlacement.RIGHT -> assertEquals(surface.right, bar.left, 1f)
                KeyboardPlacement.LEFT -> assertEquals(bar.right, surface.left, 1f)
                else -> error("unresolved position")
            }
            ui.runOnIdle { assertSame(original, findView(ui.activity.window.decorView)); assertEquals(1, starts.get()) }
        }
        try {
            check(KeyboardPlacement.BOTTOM)
            for (side in listOf(KeyboardPlacement.RIGHT, KeyboardPlacement.LEFT, KeyboardPlacement.BOTTOM)) {
                ui.runOnIdle { placement = side }; check(side)
            }
            ui.runOnIdle { placement = KeyboardPlacement.AUTO }; check(KeyboardPlacement.BOTTOM)
            ui.runOnIdle { landscape = true }; check(KeyboardPlacement.RIGHT)
            ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(ui.activity.cacheDir, "keyboard-right.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            ui.onNodeWithText("CTRL").performClick()
            ui.runOnIdle { assertTrue(terminal.modifiers.active(ModifierKey.CTRL)); terminal.modifiers.clear() }
            synchronized(output) { output.reset() }
            ui.onNodeWithText("/").performClick()
            ui.waitUntil(3000) { synchronized(output) { output.toString("UTF-8").contains("/") } }
            ui.runOnIdle { short = true }; check(KeyboardPlacement.RIGHT)
            ui.onNodeWithTag("extra-keys-bar").performTouchInput { swipeUp() }
            ui.onNodeWithText("SHIFT").assertIsDisplayed().performClick()
            ui.runOnIdle { assertTrue(terminal.modifiers.active(ModifierKey.SHIFT)); terminal.modifiers.clear() }
            ui.runOnIdle { landscape = false }; check(KeyboardPlacement.BOTTOM)
        } finally { ui.runOnIdle { terminal.close() } }
    }
}

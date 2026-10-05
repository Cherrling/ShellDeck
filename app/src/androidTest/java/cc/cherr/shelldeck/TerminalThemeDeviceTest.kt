package cc.cherr.shelldeck

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.settings.*
import com.termux.terminal.*
import com.termux.view.TerminalRenderer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class TerminalThemeDeviceTest {
    @Test fun paletteFilesSettingsAndLegacyBackupsRoundTripWithStrictBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val custom = TerminalTheme.preset(TerminalPalette.LIGHT).withColor(1, TerminalTheme.parse("#aBc"))
            .withColor(19, TerminalTheme.parse("#112233")).withColor(20, TerminalTheme.parse("#445566"))
        assertEquals("#AABBCC", TerminalTheme.hex(custom.colors[1]))
        assertEquals(custom, TerminalTheme.decode(custom.json().toString()))
        assertEquals(custom, TerminalTheme.read(ByteArrayInputStream(custom.json().toString().toByteArray()), custom))
        val partial = TerminalTheme.decode("# Termux colors\nforeground=#abc\nbackground=#123456\ncolor1=#f00", custom)
        assertEquals(TerminalTheme.parse("#aabbcc"), partial.colors[16]); assertEquals(partial.colors[16], partial.colors[18])
        assertEquals(TerminalTheme.parse("#123456"), partial.colors[17]); assertEquals(custom.colors[2], partial.colors[2])
        for (invalid in listOf("foreground=red", "color999=#123456", "cursor=#00112233", "", "[".repeat(40000),
            "{\"colors\":" + "[".repeat(20) + "]".repeat(20) + "}", custom.json().put("version", 2).toString())) {
            assertThrows(Exception::class.java) { TerminalTheme.decode(invalid) }
        }
        assertThrows(Exception::class.java) { TerminalTheme.read(ByteArrayInputStream(ByteArray(TerminalTheme.MAX_BYTES + 1)), custom) }
        val settings = AppSettings(palette = TerminalPalette.LIGHT, terminalTheme = custom)
        assertEquals(settings, SettingsStore.decode(SettingsStore.encode(settings)))
        val old = JSONObject(SettingsStore.encode(AppSettings(palette = TerminalPalette.LIGHT))).put("version", 1).apply { remove("terminalTheme") }
        assertEquals(AppSettings(palette = TerminalPalette.LIGHT), SettingsStore.decode(old.toString()))
        val name = "colors-${UUID.randomUUID()}"
        try { SettingsStore(context, name).save(settings); assertEquals(settings, SettingsStore(context, name).read()) }
        finally { context.deleteSharedPreferences(name) }
    }

    @Test fun oscQueriesAndSelectionPixelsMatchSavedColorsWithoutPreviewSideEffects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val previous = TerminalColors.COLOR_SCHEME.mDefaultColors.copyOf()
        val output = ByteArrayOutputStream()
        lateinit var terminal: TerminalController
        val theme = TerminalTheme.preset(TerminalPalette.DARK).withColor(1, TerminalTheme.parse("#010203")).withColor(16, TerminalTheme.parse("#123456"))
            .withColor(17, TerminalTheme.parse("#182838")).withColor(18, TerminalTheme.parse("#aabbcc"))
            .withColor(19, TerminalTheme.parse("#ffeedd")).withColor(20, TerminalTheme.parse("#445566"))
        instrumentation.runOnMainSync {
                TerminalColors.COLOR_SCHEME.updateWith(theme.properties())
                terminal = TerminalController(app) {}
                terminal.session = TerminalSession(object : TerminalTransport {
                    override fun start(size: TerminalSize, listener: TerminalTransport.Listener) = listener.onReady()
                    override fun write(bytes: ByteArray, offset: Int, count: Int) { output.write(bytes, offset, count) }
                    override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
                    override fun close() = Unit
                }, 100, terminal)
                terminal.session.updateSize(20, 8, 8, 16)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            try {
                val emu = terminal.session.emulator
                fun append(text: String) { val b = text.toByteArray(); emu.append(b, b.size) }
                append("\u001b]10;?\u0007\u001b]11;?\u0007\u001b]12;?\u0007")
                val replies = output.toString("UTF-8")
                assertTrue(replies.contains("rgb:1212/3434/5656"))
                assertTrue(replies.contains("rgb:1818/2828/3838"))
                assertTrue(replies.contains("rgb:aaaa/bbbb/cccc"))
                assertEquals(theme.colors[1], emu.mColors.mCurrentColors[1])
                append("\u001b[?25l\u001b[2J\u001b[H                    ")
                val renderer = TerminalRenderer(30, Typeface.MONOSPACE)
                renderer.setSelectionColors(theme.colors[19], theme.colors[20])
                val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(theme.colors[17]); renderer.render(emu, canvas, 0, 0, 0, 0, 3)
                assertEquals(theme.colors[20], bitmap.getPixel((renderer.fontWidth * 2).toInt(), renderer.fontLineSpacing / 2))
                // Explicit selection background equal to normal background must still paint over reverse video.
                append("\u001b[?5h")
                renderer.setSelectionColors(theme.colors[19], theme.colors[17])
                canvas.drawColor(theme.colors[17]); renderer.render(emu, canvas, 0, 0, 0, 0, 3)
                assertEquals(theme.colors[17], bitmap.getPixel((renderer.fontWidth * 2).toInt(), renderer.fontLineSpacing / 2))
                val liveColors = emu.mColors.mCurrentColors.copyOf()
                val preview = ThemePreviewView(app); preview.layout(0, 0, 600, 240)
                preview.update(TerminalTheme.preset(TerminalPalette.LIGHT), Typeface.MONOSPACE); preview.draw(canvas)
                assertArrayEquals(liveColors, emu.mColors.mCurrentColors)
                assertEquals(theme.colors[17], TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND])
                append("\u001b]11;#ffffff\u0007\u001b]111\u0007")
                assertEquals(theme.colors[17], emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
                bitmap.recycle()
            } finally { terminal.close(); previous.copyInto(TerminalColors.COLOR_SCHEME.mDefaultColors) }
        }
    }
}

package cc.cherr.shelldeck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.keyboard.*
import cc.cherr.shelldeck.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @Test fun appearanceAndEveryActionSurviveStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context); val original = store.read()
        val actions = listOf(KeyAction.Character("中"), KeyAction.Special(SpecialKey.TAB, setOf(ModifierKey.SHIFT)),
            KeyAction.Modifier(ModifierKey.CTRL), KeyAction.EscapeSequence("\u001b[5~"), KeyAction.Macro("echo test\n"), KeyAction.ToggleKeyboard)
        val profile = KeyboardProfile(listOf(actions.mapIndexed { i, a -> KeySlot("键$i", a, i % 3 + 1) }, listOf(KeySlot("ESC", KeyAction.Special(SpecialKey.ESC)))))
        val expected = AppSettings(ThemeMode.DARK, false, "system", 19, TerminalPalette.LIGHT, profile)
        try { store.save(expected); assertEquals(expected, SettingsStore(context).read()) }
        finally { store.save(original) }
    }
    @Test fun bundledFontLoadsOnceAndHasAsciiAndChineseGlyphs() {
        val store = FontStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val face = store.load("maple")
        assertSame(face, store.load("maple"))
        val paint = android.graphics.Paint().apply { typeface = face; textSize = 32f }
        assertTrue(paint.hasGlyph("中")); assertTrue(paint.hasGlyph("A")); assertTrue(paint.hasGlyph("\uF120"))
        assertEquals(paint.measureText("AA"), paint.measureText("中"), 0.1f)
    }
}

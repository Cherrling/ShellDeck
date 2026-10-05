package cc.cherr.shelldeck

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalThemeUiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun editPreviewRecreateSaveDiscardAndAbout() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.runOnUiThread { model.updateSettings(original.copy(terminalTheme = null)) }
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("编辑终端配色").performScrollTo().performClick()
            ui.onNodeWithTag("theme-color-1").performScrollTo().performClick()
            ui.onNodeWithText("RGB 十六进制").performTextReplacement("#123ABC")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("#123ABC").assertExists()
            ui.onNodeWithText("确定", substring = false).performClick()
            ui.runOnIdle { assertNull(model.settings.terminalTheme) }
            ui.onNodeWithText("保存配色").performClick()
            ui.runOnIdle {
                assertEquals(TerminalTheme.parse("#123ABC"), model.settings.terminalTheme!!.colors[1])
                assertEquals(original.theme, model.settings.theme); assertEquals(original.dynamicColor, model.settings.dynamicColor)
                assertEquals(TerminalTheme.parse("#123ABC"), com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[1])
            }
            ui.onNodeWithText("编辑终端配色").performScrollTo().performClick()
            ui.onNodeWithText("使用浅色基底").performClick()
            ui.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            ui.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
            ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithText("放弃", substring = false).performClick()
            ui.runOnIdle { assertEquals(TerminalTheme.parse("#123ABC"), model.settings.terminalTheme!!.colors[1]) }
            ui.onNodeWithText("关于 ShellDeck").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("提交：${BuildConfig.SOURCE_REVISION}").performScrollTo().assertIsDisplayed()
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
}

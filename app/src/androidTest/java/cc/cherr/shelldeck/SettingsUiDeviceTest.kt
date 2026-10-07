package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cherr.shelldeck.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsUiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun settingsHierarchyAndThemeDialogSurviveRecreation() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        fun back() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        fun capture(name: String) {
            ui.waitForIdle()
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            ui.runOnUiThread { model.updateSettings(original.copy(theme = ThemeMode.LIGHT, dynamicColor = false)) }
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("外观与操作").assertIsDisplayed()
            ui.onNodeWithText("字体与字号").assertIsDisplayed()
            capture("settings-overview-light")
            ui.onNodeWithText("应用外观").performClick()
            ui.onNodeWithText("夜间模式").performClick()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("跟随系统", substring = false).assertIsDisplayed()
            ui.onNodeWithText("取消", substring = false).performClick()
            ui.runOnIdle { assertEquals(ThemeMode.LIGHT, model.settings.theme) }
            ui.onNodeWithText("夜间模式").performClick()
            ui.onNodeWithText("深色", substring = false).performClick()
            capture("settings-appearance-dark")
            ui.runOnIdle { assertEquals(original.terminalTheme, model.settings.terminalTheme); assertEquals(original.palette, model.settings.palette) }
            back()
            capture("settings-overview-dark")
            ui.onNodeWithText("字体与字号").performClick()
            capture("settings-font-dark")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("导入字体").assertIsDisplayed()
            back()
            ui.onNodeWithText("快捷键", substring = false).performClick()
            capture("settings-keyboard-dark")
            back()
            ui.onNodeWithText("连接与后台").performScrollTo().performClick()
            ui.onNodeWithText("关闭后台保持").performScrollTo().performClick()
            ui.runOnIdle { assertEquals(BackgroundMode.OFF, model.settings.backgroundMode) }
            ui.onNodeWithText("保活探测", substring = false).performScrollTo().performClick()
            ui.onNodeWithText("每 120 秒").performClick()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("每 120 秒").assertIsDisplayed()
            capture("settings-connection-dark")
            back()
            ui.onNodeWithText("关于 ShellDeck").performScrollTo().performClick()
            ui.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").assertIsDisplayed()
            back()
            back()
            ui.onNodeWithTag("page-HOSTS").assertIsSelected()
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
    @Test fun keyHeightAndVisibleCountPersistAcrossRecreation() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("快捷键", substring = false).performClick()
            ui.onNodeWithTag("key-count-slider").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(9f) }
            ui.runOnIdle { assertEquals(9, model.settings.keyboardSizing.visibleKeys) }
            ui.onNodeWithTag("key-height-slider").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(32f) }
            ui.runOnIdle { assertEquals(32, model.settings.keyboardSizing.rowHeight) }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("行高：32 dp").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("每行显示：9 个").performScrollTo().assertIsDisplayed()
            ui.runOnIdle { assertEquals(9, model.settings.keyboardSizing.visibleKeys) }
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
    @Test fun editSaveRecreateAndCancelKeepExpectedSettings() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("应用外观").performClick()
            ui.onNodeWithText("夜间模式").performClick()
            ui.onNodeWithText("深色", substring = false).performClick()
            ui.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(10000) { model.fonts.size >= 2 }
            ui.onNodeWithText("字体与字号").performClick()
            ui.onNodeWithText("系统等宽字体").performScrollTo().performClick()
            ui.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNodeWithText("快捷键", substring = false).performClick()
            ui.onNodeWithText("编辑快捷键布局").performScrollTo().performClick()
            ui.onNodeWithText("ESC", substring = false).performClick()
            ui.onNodeWithText("显示名称").performTextReplacement("退出键")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("退出键", substring = false).assertExists()
            ui.onNodeWithText("确定", substring = false).performClick()
            val from = ui.onNodeWithText("退出键", substring = false).fetchSemanticsNode().boundsInRoot.center
            val next = ui.onNodeWithText("-", substring = false).fetchSemanticsNode().boundsInRoot
            ui.onRoot().performTouchInput {
                down(from); advanceEventTime(650); moveTo(androidx.compose.ui.geometry.Offset(next.right - 2, next.center.y), delayMillis = 100); up()
            }
            ui.onNodeWithText("保存布局").performClick()
            ui.runOnIdle {
                assertEquals(ThemeMode.DARK, model.settings.theme)
                assertEquals("system", model.settings.fontId)
                assertEquals("退出键", model.settings.keyboard.rows[0][2].label)
            }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("编辑快捷键布局").performScrollTo().performClick()
            ui.onNodeWithText("恢复默认布局（保存后生效）").performClick()
            ui.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNodeWithText("放弃", substring = false).performClick()
            ui.runOnIdle { assertEquals("退出键", model.settings.keyboard.rows[0][2].label) }
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
}

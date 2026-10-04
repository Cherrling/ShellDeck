package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cherr.shelldeck.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsUiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun editSaveRecreateAndCancelKeepExpectedSettings() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.onNodeWithText("设置").performClick()
            ui.onNodeWithText("深色", substring = false).performClick()
            ui.waitUntil(10000) { model.fonts.size >= 2 }
            ui.onNodeWithText("系统等宽字体").performScrollTo().performClick()
            ui.onNodeWithText("编辑快捷键布局").performScrollTo().performClick()
            ui.onNodeWithText("ESC", substring = false).performClick()
            ui.onNodeWithText("显示名称").performTextReplacement("退出键")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("退出键", substring = false).assertExists()
            ui.onNodeWithText("右移", substring = false).performScrollTo().performClick()
            ui.onNodeWithText("保存布局").performClick()
            ui.runOnIdle {
                assertEquals(ThemeMode.DARK, model.settings.theme)
                assertEquals("system", model.settings.fontId)
                assertEquals("退出键", model.settings.keyboard.rows[0][2].label)
            }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("编辑快捷键布局").performScrollTo().performClick()
            ui.onNodeWithText("恢复默认布局（保存后生效）").performClick()
            ui.onNodeWithText("返回", substring = false).performClick()
            ui.onNodeWithText("放弃", substring = false).performClick()
            ui.runOnIdle { assertEquals("退出键", model.settings.keyboard.rows[0][2].label) }
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
}

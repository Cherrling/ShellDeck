package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import cc.cherr.shelldeck.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MainNavigationDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun keysAreInSettingsAndNavigationDoesNotDiscardKeyboardDraft() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val original = model.settings
        try {
            ui.onNodeWithTag("page-HOSTS").assertIsSelected()
            ui.onNodeWithText("导入 SSH Key", substring = false).assertDoesNotExist()
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("SSH 身份与密钥").performClick()
            ui.onNodeWithText("导入 SSH Key", substring = false).assertIsDisplayed()
            ui.onNodeWithTag("page-SETTINGS").assertIsSelected()
            ui.onNodeWithText("返回", substring = false).performClick()
            ui.onNodeWithText("深色", substring = false).performClick()
            ui.runOnIdle { assertFalse(WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars) }
            ui.activityRule.scenario.recreate()
            ui.runOnIdle { assertEquals(ThemeMode.DARK, model.settings.theme); assertFalse(WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars) }
            ui.onNodeWithText("编辑快捷键布局").performScrollTo().performClick()
            ui.onNodeWithTag("main-navigation").assertDoesNotExist()
            ui.onNodeWithText("返回", substring = false).performClick()
            ui.onNodeWithTag("main-navigation").assertIsDisplayed()
            ui.onNodeWithTag("page-SESSIONS").performClick()
            ui.onNodeWithText("添加服务器", substring = false).assertDoesNotExist()
        } finally { ui.runOnUiThread { model.updateSettings(original) } }
    }
}

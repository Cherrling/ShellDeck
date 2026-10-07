package cc.cherr.shelldeck

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MoshUiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun hostEditorStoresProtocolAndDuplicatePreservesIt() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java]; model.sessionManager.home() }
        fun saved() = ui.waitUntil(5000) { !model.busy }
        saved()
        try {
            ui.onNodeWithTag("page-HOSTS").performClick()
            ui.onNodeWithContentDescription("添加服务器").performClick()
            ui.onNodeWithText("名称").performTextInput("Mosh UI fixture")
            ui.onNodeWithText("主机名 / IP").performTextInput("example.com")
            ui.onNodeWithText("Mosh", substring = false).performScrollTo().performClick()
            ui.onNodeWithText("Mosh UDP 端口（可留空）").performScrollTo().performTextInput("60008")
            ui.onNode(isDialog()).saveScreenshot("mosh-host-editor")
            ui.onNodeWithText("保存", substring = false).performClick(); saved()
            val host = model.hosts.single { it.label == "Mosh UI fixture" }
            assertEquals("mosh", host.protocol); assertEquals(60008, host.moshPort)
            ui.runOnUiThread { model.duplicateHost(host.id) }; saved()
            assertTrue(model.hosts.filter { it.label.startsWith("Mosh UI fixture") }.all { it.protocol == "mosh" && it.moshPort == 60008 })
        } finally {
            saved()
            for (host in model.hosts.filter { it.label.startsWith("Mosh UI fixture") }) {
                ui.runOnUiThread { model.deleteHost(host.id) }; saved()
            }
        }
    }
    @Test fun installationHelpCopiesOnlyOnTapAndSshRequiresExplicitChoice() {
        var sshRequested = false
        ui.runOnUiThread { ui.activity.setContent { MaterialTheme { cc.cherr.shelldeck.mosh.MoshInstallHelp { sshRequested = true } } } }
        ui.onNodeWithText("远端未安装 Mosh").assertIsDisplayed()
        ui.onNode(isDialog()).saveScreenshot("mosh-install-help")
        assertFalse(sshRequested)
        ui.onNodeWithText("复制 Debian / Ubuntu 命令").performScrollTo().performClick()
        // Android 13+ clipboard preview briefly takes window focus; dismiss it before reading.
        ui.onNodeWithText("远端未安装 Mosh").performTouchInput { click() }
        ui.runOnIdle {
            assertEquals("sudo apt install mosh", ui.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            assertFalse(sshRequested)
        }
        ui.onNodeWithText("使用 SSH 连接").performClick()
        ui.runOnIdle { assertTrue(sshRequested) }
    }
}

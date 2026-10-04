package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionNavigationDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun backHidesKeyboardThenShowsHomeWithoutDisconnectingAndCloseIsExplicit() {
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sshUser"))
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val pem = File(ui.activity.filesDir, "test-ssh-key").readText()
        var identityId: String? = null
        var hostId: String? = null
        try {
            ui.runOnUiThread { model.importIdentity("Navigation test identity", pem, null, "") {} }
            ui.waitUntil(10000) { !model.busy && model.identities.any { it.label == "Navigation test identity" } }
            identityId = model.identities.first { it.label == "Navigation test identity" }.id
            ui.runOnUiThread { model.saveHost(null, "Navigation test host", "127.0.0.1", args.getString("sshPort")!!, args.getString("sshUser")!!, identityId) }
            ui.waitUntil(10000) { !model.busy && model.hosts.any { it.label == "Navigation test host" } }
            val host = model.hosts.first { it.label == "Navigation test host" }; hostId = host.id
            ui.runOnUiThread { model.connect(host, "") }
            ui.waitUntil(10000) { model.sessionManager.selected?.challenge != null }
            ui.onNodeWithText("仅信任本次").performClick()
            ui.waitUntil(10000) { model.sessionManager.selected?.terminal?.session?.isReady == true }
            val connection = model.sessionManager.selected!!
            ui.onNodeWithText("键盘", substring = false).performClick()
            ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            ui.runOnIdle { assertSame(connection, model.sessionManager.selected) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5000) { model.sessionManager.selected == null }
            ui.onNodeWithText("活动会话").assertIsDisplayed()
            ui.activityRule.scenario.recreate()
            ui.runOnIdle { assertTrue(connection.terminal.session.isReady) }
            ui.onNodeWithText("打开", substring = false).performClick()
            ui.onNodeWithText("设置", substring = false).performClick()
            ui.onNodeWithText("返回", substring = false).performClick()
            ui.runOnIdle { assertSame(connection, model.sessionManager.selected); assertTrue(connection.terminal.session.isReady) }
            ui.onNodeWithText("关闭", substring = false).performClick()
            ui.onNodeWithText("确认", substring = false).performClick()
            ui.runOnIdle { assertTrue(model.sessionManager.sessions.isEmpty()); assertTrue(connection.ended) }
        } finally {
            ui.runOnUiThread { model.sessionManager.closeAll(); hostId?.let(model::deleteHost) }
            ui.waitUntil(5000) { !model.busy }
            ui.runOnUiThread { identityId?.let(model::deleteIdentity) }
            ui.waitUntil(5000) { !model.busy }
        }
    }
}

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
        val originalSettings = model.settings
        var identityId: String? = null
        var hostId: String? = null
        try {
            ui.runOnUiThread { model.importIdentity("Navigation test identity", pem, null, "") {} }
            ui.waitUntil(10000) { !model.busy && model.identities.any { it.label == "Navigation test identity" } }
            identityId = model.identities.first { it.label == "Navigation test identity" }.id
            ui.runOnUiThread { model.saveHost(null, "Navigation test host", "127.0.0.1", args.getString("sshPort")!!, args.getString("sshUser")!!, identityId, "export SHELLDECK_START_COUNT=\$(( \${SHELLDECK_START_COUNT:-0} + 1 ))") }
            ui.waitUntil(10000) { !model.busy && model.hosts.any { it.label == "Navigation test host" } }
            val host = model.hosts.first { it.label == "Navigation test host" }; hostId = host.id
            ui.onNodeWithContentDescription("Navigation test host 的更多操作").performClick()
            ui.onNodeWithText("编辑服务器").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.runOnIdle { assertTrue(model.sessionManager.sessions.isEmpty()) }
            ui.onNodeWithTag("host-${host.id}").performClick()
            ui.waitUntil(10000) { model.sessionManager.selected?.challenge != null }
            ui.onNodeWithText("仅信任本次").performClick()
            ui.waitUntil(10000) { model.sessionManager.selected?.terminal?.session?.isReady == true }
            val connection = model.sessionManager.selected!!
            ui.onNodeWithTag("main-navigation").assertDoesNotExist()
            ui.runOnIdle { model.updateSettings(model.settings.copy(theme = cc.cherr.shelldeck.settings.ThemeMode.LIGHT, palette = cc.cherr.shelldeck.settings.TerminalPalette.DARK)) }
            ui.runOnIdle { assertFalse(androidx.core.view.WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars) }
            ui.runOnIdle { model.updateSettings(model.settings.copy(palette = cc.cherr.shelldeck.settings.TerminalPalette.LIGHT)) }
            ui.runOnIdle { assertTrue(androidx.core.view.WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars) }
            ui.runOnIdle { model.updateSettings(model.settings.copy(palette = cc.cherr.shelldeck.settings.TerminalPalette.DARK)) }
            ui.onNodeWithText("键盘", substring = false).performClick()
            ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            ui.runOnIdle { model.updateSettings(model.settings.copy(fontSize = 14)) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            ui.runOnIdle {
                assertEquals(15, model.settings.fontSize)
                assertEquals(15, cc.cherr.shelldeck.settings.SettingsStore(ui.activity).read().fontSize)
                // Repeated key-down from a held button does not cause repeated PTY resizes.
                ui.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 2))
                ui.activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
                assertEquals(15, model.settings.fontSize)
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            ui.runOnIdle { assertEquals(14, model.settings.fontSize); model.updateSettings(model.settings.copy(fontSize = 32)) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            ui.runOnIdle { assertEquals(32, model.settings.fontSize); model.updateSettings(model.settings.copy(fontSize = 8)) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            ui.runOnIdle { assertEquals(8, model.settings.fontSize); model.updateSettings(model.settings.copy(fontSize = 14)) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(10000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            ui.runOnIdle { assertSame(connection, model.sessionManager.selected) }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5000) { model.sessionManager.selected == null }
            ui.onNodeWithText("活动会话").assertIsDisplayed()
            ui.onNodeWithTag("page-SESSIONS").assertIsSelected()
            // Resizing and Activity recreation must not replay the host's startup command.
            ui.activityRule.scenario.recreate()
            ui.runOnUiThread { connection.terminal.session.write("printf 'START_COUNT:%s\\n' \$SHELLDECK_START_COUNT\n") }
            ui.waitUntil(5000) { connection.terminal.session.emulator.screen.transcriptText.contains("START_COUNT:1") }
            ui.runOnUiThread { connection.terminal.session.write("printf '\\033]2;Codex project test\\007'; sleep 30\n") }
            ui.waitUntil(5000) { connection.terminal.title == "Codex project test" }
            ui.waitUntil(3000) { ui.onAllNodesWithText("Codex project test", substring = false).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Codex project test", substring = false).assertIsDisplayed()
            ui.onNodeWithText("Navigation test host · 会话 ${connection.number}").assertIsDisplayed()
            ui.runOnIdle { assertTrue(androidx.core.view.WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars) }
            ui.runOnIdle { assertNull(ui.activity.onTerminalFontSizeChange) }
            ui.activityRule.scenario.recreate()
            ui.runOnIdle { assertTrue(connection.terminal.session.isReady) }
            ui.onNodeWithText("设置", substring = false).performClick()
            ui.runOnIdle { assertNull(ui.activity.onTerminalFontSizeChange) }
            ui.onNodeWithText("字体与字号").performClick()
            ui.onNodeWithText("字号：14 sp").performScrollTo().assertIsDisplayed()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("page-SESSIONS").performClick()
            ui.onNodeWithTag("session-${connection.id}").performClick()
            ui.onNodeWithText("Navigation test host", substring = false).assertDoesNotExist()
            ui.onNodeWithContentDescription("终端菜单").assertDoesNotExist()
            ui.onNodeWithContentDescription("切换会话").assertDoesNotExist()
            ui.runOnIdle { assertSame(connection, model.sessionManager.selected); assertTrue(connection.terminal.session.isReady) }
            ui.runOnUiThread { model.sessionManager.home() }
            ui.onNodeWithTag("close-session-${connection.id}").performClick()
            ui.runOnIdle { assertNull(model.sessionManager.selected); assertFalse(connection.ended) }
            ui.onNodeWithText("取消", substring = false).performClick()
            ui.runOnIdle { assertTrue(connection.terminal.session.isReady) }
            // Disconnected terminals stay readable; opening another connection requires the Hosts page.
            ui.onNodeWithTag("session-${connection.id}").performClick()
            ui.runOnUiThread { connection.terminal.session.finishIfRunning() }
            ui.waitUntil(5000) { connection.ended }
            ui.onNodeWithContentDescription("重新连接").assertDoesNotExist()
            ui.runOnIdle {
                assertSame(connection, model.sessionManager.selected)
                assertEquals(1, model.sessionManager.sessions.size)
                assertFalse(connection.terminal.session.isReady)
                assertEquals("Codex project test", connection.terminal.title)
                model.sessionManager.home()
            }
            ui.onNodeWithTag("session-${connection.id}").assertExists()
            ui.onNodeWithTag("page-HOSTS").performClick()
            ui.onNodeWithTag("host-${host.id}").performClick()
            ui.waitUntil(10000) { model.sessionManager.selected?.challenge != null }
            val fresh = model.sessionManager.selected!!
            ui.onNodeWithText("仅信任本次").performClick()
            ui.waitUntil(10000) { fresh.terminal.session.isReady }
            ui.runOnIdle {
                assertNotEquals(connection.id, fresh.id)
                assertTrue(fresh.number > connection.number)
                assertEquals("Codex project test", connection.terminal.title)
                model.sessionManager.close(fresh.id)
                model.sessionManager.home()
            }
            ui.onNodeWithTag("page-SESSIONS").performClick()
            ui.onNodeWithTag("close-session-${connection.id}").performClick()
            ui.onNodeWithText("确认", substring = false).performClick()
            ui.runOnIdle { assertTrue(model.sessionManager.sessions.isEmpty()); assertTrue(connection.ended) }
        } finally {
            ui.runOnUiThread { model.updateSettings(originalSettings); model.sessionManager.closeAll(); hostId?.let(model::deleteHost) }
            ui.waitUntil(5000) { !model.busy }
            ui.runOnUiThread { identityId?.let(model::deleteIdentity) }
            ui.waitUntil(5000) { !model.busy }
        }
    }
}

package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HostBrowserDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun searchFavoriteCopyAndEditKeepHistoryAcrossRecreation() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val initial = model.settings
        val prefix = "Browser fixture"
        fun waitSaved() = ui.waitUntil(5000) { !model.busy }
        try {
            waitSaved()
            ui.runOnUiThread { model.saveHost(null, "$prefix A", "127.0.0.1", "1", "deploy", null, "tmux new-session -A -s codex") }; waitSaved()
            val first = model.hosts.first { it.label == "$prefix A" }
            ui.runOnUiThread { model.saveHost(null, "$prefix B", "127.0.0.1", "2", "deploy", null, "tmux new-session -A -s codex") }; waitSaved()
            ui.onNodeWithTag("host-search").performTextInput("fixture A")
            ui.onNodeWithTag("host-${first.id}").assertIsDisplayed()
            ui.onNodeWithText("$prefix B", substring = false).assertDoesNotExist()
            ui.onNodeWithTag("favorite-${first.id}").performClick(); waitSaved()
            ui.runOnIdle { assertTrue(model.hosts.first { it.id == first.id }.favorite) }
            ui.onNodeWithTag("host-filter-FAVORITES").performClick()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("host-filter-FAVORITES").assertIsSelected()
            ui.onNodeWithTag("host-search").assertTextContains("fixture A")
            // A connection attempt counts as recent use, even if the server is unavailable.
            ui.runOnUiThread {
                model.updateSettings(initial.copy(backgroundMode = cc.cherr.shelldeck.settings.BackgroundMode.OFF))
                model.connect(model.hosts.first { it.id == first.id }, "")
                model.sessionManager.home()
            }
            ui.waitUntil(5000) { model.hosts.first { it.id == first.id }.lastUsedAt > 0 }
            val used = model.hosts.first { it.id == first.id }.lastUsedAt
            ui.runOnUiThread { model.saveHost(first.id, "$prefix Renamed", "127.0.0.1", "1", "deploy", null, "tmux new-session -A -s codex") }; waitSaved()
            ui.runOnIdle {
                val changed = model.hosts.first { it.id == first.id }
                assertTrue(changed.favorite); assertEquals(used, changed.lastUsedAt)
            }
            ui.onNodeWithTag("host-search").performTextReplacement("fixture")
            ui.onNodeWithTag("host-filter-RECENT").performClick()
            ui.onNodeWithTag("host-${first.id}").assertIsDisplayed()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNodeWithContentDescription("$prefix Renamed 的更多操作").performScrollTo().performClick()
            ui.onNodeWithText("复制服务器", substring = false).performClick(); waitSaved()
            ui.runOnIdle {
                val copy = model.hosts.single { it.label == "$prefix Renamed（副本）" }
                assertNotEquals(first.id, copy.id); assertEquals(first.hostname, copy.hostname)
                assertEquals("tmux new-session -A -s codex", copy.startupCommand)
                assertFalse(copy.favorite); assertEquals(0L, copy.lastUsedAt)
            }
        } finally {
            ui.runOnUiThread { model.sessionManager.closeAll(); model.updateSettings(initial) }
            waitSaved()
            for (host in model.hosts.filter { it.label.startsWith(prefix) }) {
                ui.runOnUiThread { model.deleteHost(host.id) }; waitSaved()
            }
        }
    }
}

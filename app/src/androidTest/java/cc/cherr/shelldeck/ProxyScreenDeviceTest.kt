package cc.cherr.shelldeck

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.proxy.ProxyCredentials
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProxyScreenDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun savesEncryptedCredentialsChecksFailureAndKeepsHostReferences() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        fun saved() = ui.waitUntil(5000) { !model.busy }
        val runtime = (ui.activity.application as ShellDeckApplication).runtime
        try {
            ui.onNodeWithText("设置", useUnmergedTree = false).performClick()
            ui.onNodeWithText("连接与后台").performScrollTo().performClick()
            ui.onNodeWithText("SOCKS 代理").performScrollTo().performClick()
            ui.onNodeWithContentDescription("添加代理").performClick()
            ui.onNodeWithText("名称").performTextInput("Proxy fixture")
            ui.onNodeWithText("代理地址").performTextInput("127.0.0.1")
            ui.onNodeWithText("端口").performTextReplacement("1")
            ui.onNodeWithText("保存").performClick(); saved()
            ui.waitUntil(5000) { model.proxies.any { it.label == "Proxy fixture" } }
            val proxy = model.proxies.single { it.label == "Proxy fixture" }
            ui.onNodeWithText("检测代理").performScrollTo().performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("无法连接代理").fetchSemanticsNodes().isNotEmpty() }
            ui.onRoot().saveScreenshot("proxy-settings")
            ui.runOnUiThread { model.saveProxy(proxy.id, proxy.label, proxy.hostname, "1", true, true, "fixture-user", "fixture-pass", true) {} }; saved()
            val stored = runtime.dao.proxy(proxy.id)!!
            assertTrue(stored.authenticated)
            val bytes = runtime.vault.decrypt("proxy:${stored.id}", stored.encryptedCredentials)
            try { ProxyCredentials.decode(bytes).use { assertEquals("fixture-user", String(it.username)); assertEquals("fixture-pass", String(it.password)) } }
            finally { bytes.fill(0) }
            assertFalse(String(stored.encryptedCredentials).contains("fixture-pass"))
            ui.runOnUiThread { model.saveHost(null, "Proxy fixture host", "only-on-proxy.invalid", "22", "dev", null, proxyId = proxy.id) }; saved()
            val host = model.hosts.single { it.label == "Proxy fixture host" }
            ui.runOnUiThread { model.duplicateHost(host.id) }; saved()
            assertTrue(model.hosts.filter { it.label.startsWith("Proxy fixture host") }.all { it.proxyId == proxy.id })
            ui.runOnUiThread { model.deleteProxy(proxy.id) }; saved()
            assertNotNull(runtime.dao.proxy(proxy.id))
            ui.runOnUiThread { model.clearError() }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("SOCKS 代理").assertIsDisplayed()
            // Exiting the screen must cancel checks and unregister network callbacks without errors.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally {
            saved()
            for (host in model.hosts.filter { it.label.startsWith("Proxy fixture host") }) { ui.runOnUiThread { model.deleteHost(host.id) }; saved() }
            for (proxy in model.proxies.filter { it.label == "Proxy fixture" }) { ui.runOnUiThread { model.deleteProxy(proxy.id) }; saved() }
            ui.runOnUiThread { model.clearError() }
        }
    }
}

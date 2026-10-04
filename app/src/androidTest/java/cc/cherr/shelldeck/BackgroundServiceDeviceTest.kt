package cc.cherr.shelldeck

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.settings.BackgroundMode
import cc.cherr.shelldeck.settings.SettingsStore
import cc.cherr.shelldeck.ssh.TrustDecision
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundServiceDeviceTest {
    @Test fun serviceOwnsRealConnectionsAfterActivityFinishesAndDismissalNeverReposts() {
        assumeTrue("Android 14+ permits swiping foreground notifications", Build.VERSION.SDK_INT >= 34)
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sshUser"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ShellDeckApplication
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(description: String, predicate: () -> Boolean) {
            val end = System.nanoTime() + 15_000_000_000L
            var ok = false
            while (!ok && System.nanoTime() < end) { main { ok = predicate() }; if (!ok) Thread.sleep(25) }
            assertTrue(description, ok)
        }
        lateinit var runtime: ConnectionRuntime
        main { runtime = app.runtime }
        val original = SettingsStore(app).read()
        val identityId = UUID.randomUUID().toString()
        val key = File(app.filesDir, "test-ssh-key").readBytes()
        try { runtime.dao.insertIdentity(IdentityRecord().apply {
            id = identityId; label = "Background test key"; encryptedKey = runtime.vault.encrypt(id, key)
        }) } finally { key.fill(0) }
        val host = HostRecord().apply {
            id = UUID.randomUUID().toString(); label = "Background test host"; hostname = "127.0.0.1"
            port = args.getString("sshPort")!!.toInt(); username = args.getString("sshUser")!!; this.identityId = identityId
        }
        val notifications = app.getSystemService(NotificationManager::class.java)
        fun notification() = notifications.activeNotifications.firstOrNull { it.id == ConnectionService.NOTIFICATION_ID }
        var scenario: ActivityScenario<MainActivity>? = null
        lateinit var model: ShellDeckModel
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[ShellDeckModel::class.java] }
            // The device-test harness revokes POST_NOTIFICATIONS before instrumentation starts.
            assertFalse("test starts without notification permission", androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled())
            main { model.updateSettings(original.copy(backgroundMode = BackgroundMode.NORMAL)); model.connect(host, "") }
            waitFor("foreground service works without notification permission") { runtime.service != null && runtime.sessions.selected?.challenge != null }
            main { runtime.sessions.selected!!.trust(TrustDecision.ONCE) }
            waitFor("SSH works without notification permission") { runtime.sessions.selected!!.terminal.session.isReady }
            assertNull(notification())
            main { runtime.sessions.closeAll() }
            waitFor("service without notification permission stops") { runtime.service == null }
            instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            main { model.updateSettings(original.copy(backgroundMode = BackgroundMode.NORMAL)); model.connect(host, "") }
            waitFor("foreground service and exactly one notification") { runtime.service != null && notification() != null }
            waitFor("host key challenge") { runtime.sessions.selected?.challenge != null }
            lateinit var first: SessionConnection
            main { first = runtime.sessions.selected!!; first.trust(TrustDecision.ONCE) }
            waitFor("first SSH ready") { first.terminal.session.isReady }
            val service = runtime.service
            notification()!!.notification.contentIntent.send()
            waitFor("notification opens session list without disconnecting") { runtime.sessions.selected == null && !first.ended }
            // Actually swipe the foreground notification on Android 14+, exercising its deleteIntent.
            val automation = instrumentation.uiAutomation
            automation.executeShellCommand("cmd statusbar expand-notifications").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                packageNames = null
            }
            fun findTitle(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null || depth > 24) return null
                if (node.viewIdResourceName == "android:id/title" && node.text?.toString() == "ShellDeck · SSH 会话") return node
                repeat(node.childCount) { findTitle(node.getChild(it), depth + 1)?.let { return it } }
                return null
            }
            var bounds: android.graphics.Rect? = null
            val shadeDeadline = System.nanoTime() + 10_000_000_000L
            while (bounds == null && System.nanoTime() < shadeDeadline) {
                val node = automation.windows.firstNotNullOfOrNull { findTitle(it.root) }
                if (node != null) bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                else Thread.sleep(100)
            }
            assertNotNull("notification visible in system shade", bounds)
            val rect = bounds!!
            automation.executeShellCommand("input swipe ${rect.left + 4} ${rect.centerY()} ${app.resources.displayMetrics.widthPixels - 2} ${rect.centerY()} 100").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            waitFor("swiped notification removed") { notification() == null }
            automation.executeShellCommand("input keyevent KEYCODE_BACK").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            main { runtime.sessions.home() }
            scenario.close(); scenario = null
            main { assertSame(service, runtime.service); assertFalse(first.ended) }
            main { first.terminal.session.write("printf '\\102\\107\\137\\101\\114\\111\\126\\105\\n'\r") }
            waitFor("remote output after Activity and ViewModel destruction") {
                first.terminal.session.emulator.screen.transcriptText.contains("BG_ALIVE")
            }
            // Exercise a state update while dismissed. It must not restore the notification.
            main { runtime.changeMode(BackgroundMode.ONGOING) }
            Thread.sleep(350)
            assertNull(notification())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[ShellDeckModel::class.java] }
            main { assertSame(first, model.sessionManager.sessions.single()); model.connect(host, "") }
            waitFor("explicit new connection restores one notification") { notification() != null && notifications.activeNotifications.size == 1 }
            waitFor("second host challenge") { runtime.sessions.selected?.challenge != null }
            main { runtime.sessions.selected!!.trust(TrustDecision.ONCE) }
            waitFor("second ready") { runtime.sessions.selected!!.terminal.session.isReady }
            main { model.updateSettings(model.settings.copy(backgroundMode = BackgroundMode.ONGOING)) }
            waitFor("ongoing flag applied") { notification()!!.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 }
            // Settings changes do not reconnect either socket. Rapid toggles must settle without a loop.
            main { repeat(8) {
                model.updateSettings(model.settings.copy(backgroundMode = BackgroundMode.OFF))
                model.updateSettings(model.settings.copy(backgroundMode = BackgroundMode.NORMAL))
            } }
            waitFor("rapid toggles settle") { runtime.service != null && notifications.activeNotifications.size == 1 }
            main { assertTrue(first.terminal.session.isReady); model.updateSettings(model.settings.copy(backgroundMode = BackgroundMode.OFF)) }
            waitFor("off stops service and removes notification") { runtime.service == null && notification() == null }
            main { assertFalse(first.ended); model.updateSettings(model.settings.copy(backgroundMode = BackgroundMode.NORMAL)) }
            waitFor("on with existing connections starts service") { runtime.service != null && notification() != null }
            main { runtime.sessions.close(first.id) }
            waitFor("closing one of two retains foreground service") { runtime.service != null && runtime.sessions.activeCount == 1 }
            // Remote exit also releases the service; ended tabs must not count as live connections.
            main { runtime.sessions.selected!!.terminal.session.write("exit\r") }
            waitFor("remote exit stops last service") { runtime.sessions.activeCount == 0 && runtime.service == null && notification() == null }
            Thread.sleep(350)
            main { assertNull(runtime.service) }
            assertNull(notification())
        } finally {
            main { runtime.sessions.closeAll(); runtime.changeMode(original.backgroundMode); SettingsStore(app).save(original) }
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
            scenario?.close()
            runtime.dao.deleteIdentity(identityId)
        }
    }
}

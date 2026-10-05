package cc.cherr.shelldeck

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.room.Room
import cc.cherr.shelldeck.data.ShellDeckDatabase
import cc.cherr.shelldeck.settings.BackgroundMode
import cc.cherr.shelldeck.settings.SettingsStore
import cc.cherr.shelldeck.data.CredentialVault

class ShellDeckApplication : Application() {
    val runtime by lazy { ConnectionRuntime(this) }
}

/** Main-thread owner shared by UI and service. Database lives as long as this process. */
class ConnectionRuntime(private val application: Application) {
    internal val database = Room.databaseBuilder(application, ShellDeckDatabase::class.java, "shelldeck.db").addMigrations(ShellDeckDatabase.MIGRATION_1_2, ShellDeckDatabase.MIGRATION_2_3, ShellDeckDatabase.MIGRATION_3_4).build()
    val dao = database.records()
    val vault = CredentialVault()
    private val main = Handler(Looper.getMainLooper())
    private var uiOwners = 0
    private var starting = false
    internal var service: ConnectionService? = null
        private set
    var mode: BackgroundMode = SettingsStore(application).read().backgroundMode
        private set
    var backgroundError by mutableStateOf<String?>(null)
        private set
    val sessions = SessionManager(application, dao, vault, ::sessionsChanged)
    private val refresh = Runnable { service?.refresh() }

    fun attachUi() { uiOwners++ }
    fun detachUi() {
        check(uiOwners > 0)
        uiOwners--
        // A foreground service (including a pending start) owns the sessions after the UI leaves.
        if (uiOwners == 0 && service == null && !starting) sessions.closeAll()
    }
    fun changeMode(value: BackgroundMode) {
        mode = value
        backgroundError = null
        if (value != BackgroundMode.OFF) ensureService()
        sessionsChanged()
    }
    fun userRequestedConnection() {
        service?.restoreNotification()
        ensureService()
    }
    private fun ensureService() {
        if (mode == BackgroundMode.OFF || sessions.activeCount == 0 || service != null || starting) return
        // Called only from a visible UI action. No restart from timers, deletion callbacks or onDestroy.
        starting = true
        try {
            ContextCompat.startForegroundService(application, Intent(application, ConnectionService::class.java))
        } catch (_: RuntimeException) {
            starting = false
            backgroundError = "无法启用后台保持，请回到 App 后重新开启。当前连接仍可在前台使用。"
        }
    }
    internal fun serviceAttached(value: ConnectionService) {
        service = value
        starting = false
        backgroundError = null
    }
    internal fun serviceDetached(value: ConnectionService) {
        if (service === value) {
            service = null
            if (uiOwners == 0 && !starting) sessions.closeAll()
        }
    }
    internal fun serviceFailed() {
        starting = false
        backgroundError = "后台保持启动失败。当前连接仍可在前台使用。"
        if (uiOwners == 0) sessions.closeAll()
    }
    private fun sessionsChanged() {
        // Coalesce closeAll and adjacent state transitions; terminal bytes never trigger this callback.
        main.removeCallbacks(refresh)
        main.post(refresh)
    }
}

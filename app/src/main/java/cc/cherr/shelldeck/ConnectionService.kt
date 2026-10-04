package cc.cherr.shelldeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cc.cherr.shelldeck.settings.BackgroundMode

/** One silent notification for all SSH sessions. Never restarts itself or reposts on dismissal. */
class ConnectionService : Service() {
    private val runtime get() = (application as ShellDeckApplication).runtime
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var foreground = false
    private var dismissed = false
    private var lastState: Pair<Int, BackgroundMode>? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "SSH 后台连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "维持用户开启的 SSH 会话，点击通知返回会话列表"
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            // Always fulfill startForegroundService's contract, even if the last connection ended meanwhile.
            if (!foreground) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                foreground = true
                lastState = runtime.sessions.activeCount to runtime.mode
            }
            runtime.serviceAttached(this)
            refresh()
        } catch (_: RuntimeException) {
            runtime.serviceFailed()
            stopServiceInstance()
        }
        // A killed process has lost SSH sockets; restarting an empty service cannot restore them.
        return START_NOT_STICKY
    }

    internal fun refresh() {
        if (!foreground) return
        if (runtime.mode == BackgroundMode.OFF || runtime.sessions.activeCount == 0) {
            stopServiceInstance()
            return
        }
        val state = runtime.sessions.activeCount to runtime.mode
        if (!dismissed && state != lastState) {
            notifications.notify(NOTIFICATION_ID, notification())
            lastState = state
        }
    }

    internal fun dismissed() { dismissed = true }
    internal fun restoreNotification() {
        dismissed = false
        lastState = null
        refresh()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_SESSIONS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val delete = PendingIntent.getBroadcast(this, 0, Intent(this, ConnectionNotificationDismissed::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sessions)
            .setContentTitle("ShellDeck · SSH 会话")
            .setContentText("${runtime.sessions.activeCount} 个活动连接")
            .setContentIntent(open).setDeleteIntent(delete)
            .setOngoing(runtime.mode == BackgroundMode.ONGOING)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun stopServiceInstance() {
        // Clear ownership before stopping so a new UI connection can request a fresh start.
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runtime.serviceDetached(this)
        stopSelfResult(lastStartId)
    }
    override fun onDestroy() {
        foreground = false
        runtime.serviceDetached(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "ssh_connections"
        const val NOTIFICATION_ID = 1
        const val ACTION_SESSIONS = "cc.cherr.shelldeck.OPEN_SESSIONS"
    }
}

class ConnectionNotificationDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // No service start, no notification, no scheduled retry. Discard stale callbacks after shutdown.
        (context.applicationContext as ShellDeckApplication).runtime.service?.dismissed()
    }
}

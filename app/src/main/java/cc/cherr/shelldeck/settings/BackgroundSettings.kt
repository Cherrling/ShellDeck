package cc.cherr.shelldeck.settings

import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
fun BackgroundSettings(mode: BackgroundMode, error: String?, change: (BackgroundMode) -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    DisposableEffect(context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    SettingsGroup("后台运行") {
        BackgroundMode.entries.forEach { value ->
            SettingsRadio(backgroundLabel(value), mode == value) { change(value) }
        }
    }
    SettingsNote(if (mode == BackgroundMode.OFF) "不使用前台服务；离开 App 后连接可能被系统回收。"
        else "仅在有活动连接时运行。通知划除不会主动断开连接，也不会自动补发；再次连接时恢复通知。Android 14 及以上仍允许划掉常驻通知。")
    SettingsGroup("系统权限") {
        if (mode != BackgroundMode.OFF && !allowed && Build.VERSION.SDK_INT >= 33) {
            SettingsLink("允许连接通知", "在通知栏显示会话入口", cc.cherr.shelldeck.R.drawable.ic_connection) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SettingsLink("系统通知设置", if (allowed) "已开启" else "未开启 · 可能看不到会话入口", cc.cherr.shelldeck.R.drawable.ic_settings) {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        SettingsLink("系统电池设置", if (unrestricted) "电池优化已豁免" else "电池优化未豁免", cc.cherr.shelldeck.R.drawable.ic_connection) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            try { context.startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }
        }
    }
    SettingsNote("前台通知不能保证 Doze 或长期熄屏时网络始终可用；厂商后台限制仍可能影响连接。")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

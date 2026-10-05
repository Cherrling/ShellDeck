package cc.cherr.shelldeck.settings

import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Text("连接与后台", style = MaterialTheme.typography.titleLarge)
    BackgroundMode.entries.forEach { value ->
        val label = when (value) {
            BackgroundMode.OFF -> "关闭后台保持"
            BackgroundMode.NORMAL -> "后台保持（推荐）"
            BackgroundMode.ONGOING -> "尽量常驻通知"
        }
        Row(Modifier.fillMaxWidth().clickable { change(value) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == value, onClick = { change(value) })
            Text(label)
        }
    }
    Text(if (mode == BackgroundMode.OFF) "不使用前台服务；离开 App 后连接可能被系统回收。"
        else "仅在有活动连接时运行。通知划除不会主动断开连接，也不会自动补发；再次连接时恢复通知。Android 14 及以上仍允许划掉常驻通知。",
        style = MaterialTheme.typography.bodySmall)
    if (mode != BackgroundMode.OFF && !allowed) {
        Column {
            Text("系统通知未开启：后台服务仍可运行，但通知栏可能看不到会话入口。", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许连接通知") }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("系统通知设置") }
        }
    }
    Text(if (unrestricted) "系统电池优化：已豁免。厂商后台限制仍可能影响连接。"
        else "系统电池优化：未豁免。前台通知不能保证 Doze 或长期熄屏时网络始终可用。", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }) { Text("系统电池设置") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

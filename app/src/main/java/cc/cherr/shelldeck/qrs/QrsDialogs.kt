package cc.cherr.shelldeck.qrs

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cherr.shelldeck.R
import cc.cherr.shelldeck.backup.BackupController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.os.SystemClock

@Composable
fun QrsDialogs(controller: BackupController) {
    val sender = controller.qrsExport
    val receiver = controller.qrsReceiver
    if (sender == null && receiver == null) return
    Dialog(onDismissRequest = controller::cancel, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        val owner = LocalLifecycleOwner.current
        var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        val view = LocalView.current
        DisposableEffect(view, active) {
            val previous = view.keepScreenOn; view.keepScreenOn = active
            onDispose { view.keepScreenOn = previous }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sender != null) "二维码发送备份" else "扫描二维码恢复", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = controller::cancel) { Icon(painterResource(R.drawable.ic_close), "关闭二维码传输") }
                }
                if (sender != null) QrsSend(sender, active)
                if (receiver != null) QrsReceive(receiver, active, controller)
            }
        }
    }
}

@Composable
private fun QrsSend(sender: QrsExport, active: Boolean) {
    var paused by remember(sender) { mutableStateOf(false) }
    val fps = sender.fps
    val sliceSize = sender.sliceSize
    var bitmap by remember(sender) { mutableStateOf<Bitmap?>(null) }
    var error by remember(sender) { mutableStateOf(false) }
    LaunchedEffect(sender, active, paused, fps, sliceSize) {
        if (!active || (paused && bitmap != null)) return@LaunchedEffect
        while (true) {
            val started = SystemClock.elapsedRealtime()
            try { bitmap = withContext(Dispatchers.Default) { qrBitmap(sender.next()) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true; break }
            if (paused) break
            delay((1000L / fps - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L))
        }
    }
    TransferPanels(primary = {
        Box(Modifier.fillMaxSize().background(Color.White).testTag("qrs-display"), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), "动态备份二维码", Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
            if (error) Text("无法继续生成二维码，请关闭后重试。", color = Color.Black)
        }
    }, controls = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (sender.encrypted) "密码加密 · ${(sender.size + 1023) / 1024} KiB" else "不加密 · ${(sender.size + 1023) / 1024} KiB",
                Modifier.weight(1f), color = if (sender.encrypted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { paused = !paused }) { Text(if (paused) "继续" else "暂停") }
        }
        Text("帧率：$fps Hz")
        Slider(value = fps.toFloat(), onValueChange = { sender.configure(it.roundToInt(), sender.sliceSize) },
            valueRange = 1f..60f, modifier = Modifier.testTag("qrs-fps"))
        Text("分块大小：$sliceSize 字节")
        Slider(value = sliceSize.toFloat(), onValueChange = {
            val bytes = it.roundToInt()
            if (bytes != sender.sliceSize) { bitmap = null; sender.configure(sender.fps, bytes); error = false }
        }, valueRange = 100f..1500f, modifier = Modifier.testTag("qrs-slice"))
        Text("拖动即生效。分块改变后，接收进度自动重新开始。", style = MaterialTheme.typography.bodySmall)
        if (!sender.encrypted) Text("请仅向可信设备展示。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (sender.size > 256 * 1024) Text("备份较大，扫码可能需要几分钟。", style = MaterialTheme.typography.bodySmall)
    })
}


internal fun qrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 4))
    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return try { Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888) } finally { pixels.fill(0) }
}

@Composable
private fun QrsReceive(receiver: QrsReceiver, active: Boolean, controller: BackupController) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var failure by remember(receiver) { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Text("接收备份需要相机权限。发送备份不需要此权限。")
        Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }

    } else {
        TransferPanels(primary = {
            QrsCamera(receiver, active, Modifier.fillMaxSize(), { controller.receivedQr(receiver, it) }, { failure = it })
        }, controls = {
            Text("对准发送端的动态二维码，保持两台设备稳定。收齐后先预览，再确认恢复。", style = MaterialTheme.typography.bodySmall)
            val (count, total) = receiver.progress
            if (total > 0) {
                LinearProgressIndicator(progress = { (count.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("已还原 $count / $total 个数据块")
            } else Text("等待识别备份二维码…")
            (failure ?: receiver.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = controller::scanQr) { Text("重新开始接收") }
        })
    }
}

@Composable
private fun TransferPanels(primary: @Composable () -> Unit, controls: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxControlsHeight = maxHeight * 0.55f
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) { primary() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { primary() }
                Column(Modifier.heightIn(max = maxControlsHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
            }
        }
    }
}

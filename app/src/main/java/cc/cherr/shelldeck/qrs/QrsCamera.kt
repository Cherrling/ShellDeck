package cc.cherr.shelldeck.qrs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrsCamera(receiver: QrsReceiver, active: Boolean, modifier: Modifier, received: (ByteArray) -> Unit, failure: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onFailure by rememberUpdatedState(failure)
    val previewView = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(receiver, active, owner, previewView) {
        val deliver = received
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        if (active && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val reader = QRCodeReader()
            analysis.setAnalyzer(executor) { frame ->
                var luminance: ByteArray? = null
                try {
                    if (!disposed.get() && !delivered.get() && !failed.get()) {
                        val plane = frame.planes[0]
                        val buffer = plane.buffer.duplicate(); val base = buffer.position()
                        val width = frame.width; val height = frame.height
                        val pixels = ByteArray(width * height); luminance = pixels
                        for (y in 0 until height) {
                            if (plane.pixelStride == 1) {
                                buffer.position(base + y * plane.rowStride)
                                buffer.get(pixels, y * width, width)
                            } else for (x in 0 until width) pixels[y * width + x] = buffer[base + y * plane.rowStride + x * plane.pixelStride]
                        }
                        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                        val text = reader.decode(BinaryBitmap(HybridBinarizer(source))).text
                        receiver.accept(text)?.let { file ->
                            delivered.set(true)
                            // Even if the camera pauses after decoding, deliver to the model, which checks session identity.
                            main.post { deliver(file) }
                        }
                    }
                } catch (_: ReaderException) {
                    // Missing/blurred QR frames are expected; fountain codes tolerate loss.
                } catch (_: Exception) {
                    if (!disposed.get() && failed.compareAndSet(false, true)) main.post { if (!disposed.get()) onFailure("相机暂时无法读取画面，请重新打开扫码页面。") }
                } finally { luminance?.fill(0); reader.reset(); frame.close() }
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed.get()) {
                    try {
                        val cameraProvider = future.get(); provider = cameraProvider
                        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
                    } catch (_: Exception) { onFailure("无法打开相机，请检查权限或相机是否被其他应用占用。") }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed.set(true); analysis.clearAnalyzer(); provider?.unbind(preview, analysis); executor.shutdown()
        }
    }
}

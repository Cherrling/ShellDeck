package cc.cherr.shelldeck

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test-only screenshots, outside the source tree and never included in release assets. */
internal fun SemanticsNodeInteraction.saveScreenshot(name: String) {
    val bitmap = captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
    try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    finally { bitmap.recycle() }
}

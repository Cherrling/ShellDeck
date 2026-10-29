package cc.cherr.shelldeck.qrs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class QrsExport(file: ByteArray, val encrypted: Boolean) : AutoCloseable {
    val size = file.size
    private val encoder = QrsEncoder(QrsBackup.frame(file, encrypted))
    var fps by mutableStateOf(10); private set
    var sliceSize by mutableStateOf(QrsLimits.SLICE); private set
    fun configure(fps: Int, bytes: Int) {
        require(fps in 1..60)
        encoder.resize(bytes)
        sliceSize = bytes; this.fps = fps
    }
    fun next() = encoder.next()
    override fun close() = encoder.close()
}

/** Kept by the model across rotation, not SavedState or files. Camera work is serial. */
class QrsReceiver : AutoCloseable {
    private val decoder = QrsDecoder()
    @Volatile private var closed = false
    var progress by mutableStateOf(0 to 0); private set
    var error by mutableStateOf<String?>(null); private set
    @Synchronized fun accept(text: String): ByteArray? {
        if (closed) return null
        return try {
            val data = decoder.accept(text)
            error = null
            if (data == null) {
                val next = decoder.count to decoder.total
                if (progress != next) progress = next
                null
            } else {
                closed = true
                try { QrsBackup.unframe(data) } finally { data.fill(0); decoder.close() }
            }
        } catch (_: Exception) {
            error = "无法处理此二维码。请对准同一份 ShellDeck 备份；若仍无进度，请重新开始接收。"
            null
        }
    }
    @Synchronized override fun close() { closed = true; decoder.close() }
}

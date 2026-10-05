package cc.cherr.shelldeck.sftp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import net.schmizz.sshj.sftp.SFTPClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Session-owned: navigation/rotation leave an in-flight transfer intact; SSH close cancels it. */
class SftpController(context: Context, private val open: () -> SFTPClient) {
    private val resolver = context.applicationContext.contentResolver
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean()
    @Volatile private var disposed = false
    var path by mutableStateOf(""); private set
    var entries by mutableStateOf<List<SftpFiles.Entry>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    var transferred by mutableLongStateOf(0); private set
    var operation by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    var upload by mutableStateOf<Upload?>(null); private set
    var download by mutableStateOf<SftpFiles.Entry?>(null); private set
    data class Upload(val uri: Uri, val directory: String, val name: String)
    private class UploadedButRefreshFailed : Exception()
    private fun run(label: String, action: (SftpFiles) -> Unit) {
        if (busy || disposed) return
        busy = true; cancelling = false; cancelled.set(false); transferred = 0; operation = label; message = null
        worker.execute {
            var error: String? = null
            try {
                check(!disposed)
                open().use { channel ->
                    channel.sftpEngine.timeoutMs = 20_000
                    action(SftpFiles(channel) { cancelled.get() || disposed })
                }
            } catch (failure: Exception) {
                error = if (failure is UploadedButRefreshFailed) "上传完成，但目录刷新失败，请手动刷新。"
                else if (cancelled.get() || disposed) "已取消。下载目标可能保留不完整文件；断网时远端可能保留 .shelldeck-*.part 临时文件。"
                else when (failure) {
                    is IllegalArgumentException -> failure.message
                    is net.schmizz.sshj.sftp.SFTPException -> "SFTP 操作失败（${failure.statusCode}）。请检查权限、路径或同名文件；上传不会覆盖已有文件。"
                    else -> "文件操作失败，请检查连接、服务器 SFTP 支持及本地存储权限。"
                }
            } finally {
                val result = error
                main.post { if (!disposed) { busy = false; cancelling = false; message = result ?: "${label}完成" } }
            }
        }
    }
    private fun publishListing(files: SftpFiles, directory: String) {
        val result = files.list(directory)
        main.post { if (!disposed && !cancelled.get()) { path = result.first; entries = result.second } }
    }
    fun browse(directory: String = path.ifBlank { "." }) = run("读取目录") { publishListing(it, directory) }
    fun select(entry: SftpFiles.Entry) = run("读取文件信息") { files ->
        val resolved = files.resolve(entry)
        if (resolved.directory) publishListing(files, resolved.path)
        else main.post { if (!disposed && !cancelled.get()) download = resolved }
    }
    fun dismissMessage() { message = null }
    fun dismissUpload() { upload = null }
    fun dismissDownload() { download = null }
    fun prepareUpload(uri: Uri) {
        if (busy || disposed || path.isBlank()) return
        val directory = path
        run("读取上传文件") {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "upload.bin"
            main.post { if (!disposed && !cancelled.get()) upload = Upload(uri, directory, name) }
        }
    }
    private fun reporter(): (Long) -> Unit {
        var last = 0L
        return { bytes ->
            val now = SystemClock.uptimeMillis()
            if (now - last >= 250) {
                last = now
                main.post { if (!disposed) transferred = bytes }
            }
        }
    }
    fun startUpload(name: String) {
        val request = upload ?: return
        if (busy) return
        try { SftpFiles.child(request.directory, name) } catch (e: IllegalArgumentException) { message = e.message; return }
        upload = null
        run("上传") { files ->
            requireNotNull(resolver.openInputStream(request.uri)).use { files.upload(request.directory, name, it, reporter()) }
            try { publishListing(files, request.directory) } catch (_: Exception) { throw UploadedButRefreshFailed() }
        }
    }
    fun startDownload(uri: Uri) {
        val request = download ?: run { message = "下载请求已失效，请重新选择文件。"; return }
        if (busy) return
        download = null
        run("下载") { files ->
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { files.download(request.path, it, reporter()) }
        }
    }
    fun cancel() { if (busy) { cancelling = true; cancelled.set(true) } }
    fun close() {
        disposed = true; cancelled.set(true); upload = null; download = null
        if (busy) message = "连接已结束，传输已停止。目标可能保留不完整文件。"
        busy = false; cancelling = false; worker.shutdownNow()
    }
}

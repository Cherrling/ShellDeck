package cc.cherr.shelldeck.sftp

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import net.schmizz.sshj.sftp.SFTPClient
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Session-owned; browsing has one reusable channel, transfers own an independent channel. */
class SftpController(context: Context, private val open: () -> SFTPClient) {
    private val resolver = context.applicationContext.contentResolver
    // At most one running navigation and the newest queued request; rapid taps never form a backlog.
    private val browser = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val browserChannel = AtomicReference<SFTPClient?>()
    private val transferChannel = AtomicReference<SFTPClient?>()
    private val cancelled = AtomicBoolean()
    private val cache = DirectoryCache()
    @Volatile private var disposed = false
    private var latestBytes = 0L
    private var refreshNavigation: (() -> Unit)? = null
    var path by mutableStateOf(""); private set
    var entries by mutableStateOf<List<SftpFiles.Entry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var cached by mutableStateOf(false); private set
    var browseMessage by mutableStateOf<String?>(null); private set
    var transferring by mutableStateOf(false); private set
    val busy get() = loading || transferring
    var cancelling by mutableStateOf(false); private set
    var totalBytes by mutableStateOf<Long?>(null); private set
    var transferred by mutableLongStateOf(0); private set
    var operation by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    var upload by mutableStateOf<Upload?>(null); private set
    var download by mutableStateOf<SftpFiles.Entry?>(null); private set
    data class Upload(val uri: Uri, val directory: String, val name: String, val size: Long?)

    private fun channel(): SFTPClient {
        browserChannel.get()?.let { return it }
        check(!disposed)
        val opened = open()
        opened.sftpEngine.timeoutMs = 20_000
        browserChannel.set(opened)
        if (disposed) { browserChannel.compareAndSet(opened, null); opened.close(); error("Closed") }
        return opened
    }
    private fun navigate(directory: String, canonical: Boolean, entry: SftpFiles.Entry? = null) {
        if (disposed) return
        refreshNavigation = { navigate(directory, canonical, entry) }
        val ticket = generation.incrementAndGet()
        loading = true; browseMessage = null; download = null
        val snapshot = if (entry == null) cache[directory] else null
        cached = snapshot != null
        if (snapshot != null) { path = directory; entries = snapshot }
        browser.execute {
            if (disposed || generation.get() != ticket) return@execute
            try {
                val files = SftpFiles(channel()) { disposed || generation.get() != ticket }
                val resolved = entry?.let(files::resolve)
                if (resolved != null && !resolved.directory) {
                    main.post { if (!disposed && generation.get() == ticket) { download = resolved; loading = false } }
                } else {
                    val result = files.list(resolved?.path ?: directory, canonical && resolved == null)
                    main.post {
                        if (!disposed && generation.get() == ticket) {
                            cache.put(result.first, result.second)
                            path = result.first; entries = result.second; cached = false; loading = false
                        }
                    }
                }
            } catch (failure: Exception) {
                // Obsolete navigation still closes its directory handle, but leaves the channel reusable.
                if (failure !is java.io.InterruptedIOException || generation.get() == ticket) {
                    try { browserChannel.getAndSet(null)?.close() } catch (_: Exception) {}
                }
                main.post { if (!disposed && generation.get() == ticket) {
                    loading = false
                    browseMessage = if (cached) "刷新失败，当前显示缓存；请重试。" else "读取失败，请检查目录、权限和连接。"
                } }
            }
        }
    }
    fun browse(directory: String = path.ifBlank { "." }) = navigate(directory, false)
    fun parent() = navigate(path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }, true)
    fun select(entry: SftpFiles.Entry) {
        if (entry.directory && !entry.link) navigate(entry.path, true)
        else if (entry.link || !transferring) navigate(entry.path, false, entry)
    }
    fun cancelBrowse() {
        generation.incrementAndGet(); browser.queue.clear(); loading = false
        browseMessage = "已取消目录读取。"
        closeAsync(browserChannel.getAndSet(null))
    }
    private fun closeAsync(channel: SFTPClient?) {
        if (channel != null) Thread({ try { channel.close() } catch (_: Exception) {} }, "sftp-close").start()
    }
    private fun error(failure: Exception): String = when {
        cancelled.get() || disposed -> "已取消。下载目标可能保留不完整文件；远端可能保留 .shelldeck-*.part 临时文件。"
        failure is IllegalArgumentException -> failure.message ?: "参数无效。"
        failure is net.schmizz.sshj.sftp.SFTPException -> "SFTP 操作失败（${failure.statusCode}）。请检查权限、路径、同名文件或服务器原子替换支持；删除目录前须清空目录。"
        else -> "文件操作失败，请检查连接、服务器 SFTP 支持及本地存储权限。"
    }
    private fun run(label: String, network: Boolean = true, changedDirectory: String? = null, action: (SftpFiles?) -> Unit) {
        if (transferring || disposed) return
        transferring = true; cancelling = false; cancelled.set(false); transferred = 0; latestBytes = 0
        operation = label; message = null
        worker.execute {
            var failure: String? = null
            try {
                check(!disposed)
                if (network) open().use { channel ->
                    transferChannel.set(channel); channel.sftpEngine.timeoutMs = 20_000
                    if (cancelled.get() || disposed) throw java.io.InterruptedIOException()
                    action(SftpFiles(channel) { cancelled.get() || disposed })
                } else action(null)
            } catch (problem: Exception) { failure = error(problem) }
            finally {
                transferChannel.set(null)
                val result = failure
                main.post { if (!disposed) {
                    transferring = false; cancelling = false; transferred = latestBytes; message = result ?: "${label}完成"
                    if (changedDirectory != null) {
                        cache.clear() // Rename/delete can invalidate descendants and aliases, including symlinks.
                        if (loading) refreshNavigation?.invoke()
                        else if (path.isNotBlank()) navigate(path, false)
                    }
                } }
            }
        }
    }
    fun dismissMessage() { message = null; browseMessage = null }
    fun dismissUpload() { upload = null }
    fun dismissDownload() { download = null }
    fun prepareUpload(uri: Uri) {
        if (transferring || disposed || path.isBlank()) return
        val directory = path
        run("读取上传文件", network = false) {
            var name = "upload.bin"; var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    if (!cursor.isNull(1)) size = cursor.getLong(1).takeIf { it >= 0 }
                }
            }
            main.post { if (!disposed && !cancelled.get()) upload = Upload(uri, directory, name, size) }
        }
    }
    private fun reporter(): (Long) -> Unit {
        var last = 0L
        return { bytes ->
            latestBytes = bytes
            val now = SystemClock.uptimeMillis()
            if (now - last >= 250) { last = now; main.post { if (!disposed) transferred = bytes } }
        }
    }
    fun mkdir(name: String) {
        val directory = path
        run("新建目录", changedDirectory = directory) { it!!.mkdir(directory, name) }
    }
    fun rename(entry: SftpFiles.Entry, name: String) = run("重命名", changedDirectory = entry.path.substringBeforeLast('/').ifBlank { "/" }) { it!!.rename(entry, name) }
    fun delete(entry: SftpFiles.Entry) = run("删除", changedDirectory = entry.path.substringBeforeLast('/').ifBlank { "/" }) { it!!.delete(entry) }
    fun startUpload(name: String, overwrite: Boolean = false) {
        val request = upload ?: return
        if (transferring) return
        try { SftpFiles.child(request.directory, name) } catch (e: IllegalArgumentException) { message = e.message; return }
        upload = null; totalBytes = request.size
        run("上传", changedDirectory = request.directory) { files ->
            requireNotNull(resolver.openInputStream(request.uri)).use { files!!.upload(request.directory, name, it, overwrite, reporter()) }
        }
    }
    fun startDownload(uri: Uri) {
        val request = download ?: run { message = "下载请求已失效，请重新选择文件。"; return }
        if (transferring) return
        download = null; totalBytes = request.size
        run("下载") { files ->
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { files!!.download(request.path, it, reporter()) }
        }
    }
    fun cancel() {
        if (transferring && !cancelling) {
            cancelling = true; cancelled.set(true); closeAsync(transferChannel.getAndSet(null))
        } else if (!transferring) cancelBrowse()
    }
    fun close() {
        if (disposed) return
        disposed = true; cancelled.set(true); generation.incrementAndGet()
        upload = null; download = null; cache.clear(); browser.queue.clear()
        closeAsync(browserChannel.getAndSet(null)); closeAsync(transferChannel.getAndSet(null))
        if (transferring) message = "连接已结束，传输已停止。目标可能保留不完整文件。"
        loading = false; transferring = false; cancelling = false
        browser.shutdown(); worker.shutdown()
    }
}

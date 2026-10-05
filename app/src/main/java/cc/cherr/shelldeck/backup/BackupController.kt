package cc.cherr.shelldeck.backup

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import cc.cherr.shelldeck.ConnectionRuntime
import cc.cherr.shelldeck.settings.FontStore
import cc.cherr.shelldeck.settings.SettingsStore
import cc.cherr.shelldeck.ssh.SshKeys
import net.schmizz.sshj.SSHClient
import java.util.UUID
import java.util.concurrent.Executors

enum class TransferKind { BACKUP, RESTORE, PRIVATE_KEY }
data class TransferForm(val kind: TransferKind, val label: String = "", val identityId: String? = null, val uri: Uri? = null, val needsUnlock: Boolean = false)
class ExportRequest(val filename: String, internal val bytes: ByteArray) {
    val id: String = UUID.randomUUID().toString()
    var launched = false
}

/** Owns transient transfer state across rotation, never SavedState or plaintext temporary files. */
class BackupController(private val context: Context, private val runtime: ConnectionRuntime, private val changed: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = BackupRepository(runtime.database, runtime.vault, SettingsStore(context)) { FontStore(context).entries().map { it.id }.toSet() }
    @Volatile private var disposed = false
    var busy by mutableStateOf(false); private set
    var menu by mutableStateOf(false)
    var identityChoices by mutableStateOf<Map<String, RestoreChoice>>(emptyMap())
    var hostChoices by mutableStateOf<Map<String, RestoreChoice>>(emptyMap())
    var restoreSettings by mutableStateOf(false)
    private class PendingSave(val uri: Uri?)
    private var pendingSave: PendingSave? = null
    var form by mutableStateOf<TransferForm?>(null); private set
    var export by mutableStateOf<ExportRequest?>(null); private set
    var preview by mutableStateOf<PreparedRestore?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    fun clearMessage() { message = null }
    private fun post(discarded: () -> Unit = {}, action: () -> Unit) { main.post { if (disposed) discarded() else action() } }
    private fun operation(failure: String, action: () -> Unit) {
        if (busy || disposed) return
        busy = true
        worker.execute {
            try { action() }
            catch (_: Exception) { post { message = failure } }
            finally { post {
                busy = false
                pendingSave?.let { pendingSave = null; save(it.uri) }
            } }
        }
    }
    fun backup() { if (!busy && export == null && preview == null) form = TransferForm(TransferKind.BACKUP) }
    fun restoreFile(uri: Uri) { if (!busy && export == null && preview == null) form = TransferForm(TransferKind.RESTORE, uri = uri) }
    fun privateKey(id: String) = operation("私钥读取失败，请检查设备密钥是否可用") {
        val identity = requireNotNull(runtime.dao.identity(id))
        val bytes = runtime.vault.decrypt(id, identity.encryptedKey)
        try {
            var needsUnlock = false
            SshKeys.configure()
            SSHClient().use { ssh ->
                try { SshKeys.loadWithPassphraseRequest(ssh, bytes) { needsUnlock = true; null } }
                catch (failure: Exception) { if (!needsUnlock) throw failure }
            }
            post { form = TransferForm(TransferKind.PRIVATE_KEY, identity.label, id, needsUnlock = needsUnlock) }
        } finally { bytes.fill(0) }
    }
    fun cancel() {
        if (busy) return
        menu = false; form = null; preview?.close(); preview = null
        export?.bytes?.fill(0); export = null
    }
    fun prepare(password: String, originalPassphrase: String = "", encryptedPrivateKey: Boolean = true) {
        val request = form ?: return
        if (busy || disposed) return
        val secret = password.toCharArray(); val original = originalPassphrase.toCharArray()
        operation(if (request.kind == TransferKind.RESTORE) "无法读取备份：密码错误、文件损坏或格式不受支持。本机数据未修改。"
            else "导出准备失败，请检查私钥口令、设备密钥和数据大小（备份上限 8 MiB）") {
            try {
                when (request.kind) {
                    TransferKind.BACKUP -> offer("ShellDeck-${java.time.LocalDate.now()}.sdbak", repository.export(secret))
                    TransferKind.RESTORE -> {
                        val file = context.contentResolver.openInputStream(requireNotNull(request.uri))!!.use(::readBackupFile)
                        val prepared = try { repository.prepare(file, secret) } finally { file.fill(0) }
                        post(discarded = prepared::close) {
                            form = null; preview = prepared
                            identityChoices = emptyMap(); hostChoices = emptyMap(); restoreSettings = false
                        }
                    }
                    TransferKind.PRIVATE_KEY -> {
                        if (encryptedPrivateKey) require(secret.size >= 8)
                        val identity = requireNotNull(runtime.dao.identity(requireNotNull(request.identityId)))
                        val bytes = runtime.vault.decrypt(identity.id, identity.encryptedKey)
                        try {
                            SshKeys.configure()
                            SSHClient().use { ssh ->
                                val key = SshKeys.load(ssh, bytes, original)
                                // Plaintext is allowed only for this explicit user-selected export.
                                val output = SshKeys.exportPrivate(key.getPrivate(), if (encryptedPrivateKey) secret else charArrayOf())
                                offer(safeName(identity.label) + ".pem", output)
                            }
                        } finally { bytes.fill(0) }
                    }
                }
            } finally { secret.fill('\u0000'); original.fill('\u0000') }
        }
    }
    private fun offer(filename: String, bytes: ByteArray) {
        post(discarded = { bytes.fill(0) }) { form = null; export = ExportRequest(filename, bytes) }
    }
    fun save(uri: Uri?) {
        val request = export
        if (request == null) {
            if (uri != null) message = "导出已中断，请重新发起导出。"
            return
        }
        // A picker callback may arrive immediately after offer, before the worker's final post.
        if (busy) { pendingSave = PendingSave(uri); return }
        export = null
        if (uri == null) { request.bytes.fill(0); return }
        operation("文件保存失败，请重新选择位置后导出") {
            try {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(request.bytes) }
                post { message = "文件已保存。" }
            } finally { request.bytes.fill(0) }
        }
    }
    fun pickerUnavailable() { cancel(); message = "系统文件选择器不可用，请稍后重试。" }
    fun apply(identities: Map<String, RestoreChoice>, hosts: Map<String, RestoreChoice>, settings: Boolean) {
        val plan = preview ?: return
        if (busy || disposed) return
        if (runtime.sessions.activeCount > 0) { message = "请先关闭活动连接，再恢复备份。"; return }
        preview = null
        operation("恢复未完成。本机数据可能已变化，或多个条目选择覆盖同一记录；请重新预览。主机与身份未写入。") {
            try {
                val result = repository.restore(plan, identities, hosts, settings)
                post {
                    changed()
                    message = "已恢复 ${result.identities} 个身份、${result.hosts} 个主机。" +
                        if (result.settingsSaved) (if (settings) "设置已恢复。" else "本机设置保持不变。")
                        else "主机和身份已保存，但设置保存失败，请重新恢复设置。"
                }
            } finally { plan.close() }
        }
    }
    override fun close() {
        disposed = true; pendingSave = null
        preview?.close(); preview = null; export?.bytes?.fill(0); export = null; form = null
        worker.shutdown()
    }
    private fun safeName(label: String) = label.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("").take(80).ifBlank { "id_ssh" }
}

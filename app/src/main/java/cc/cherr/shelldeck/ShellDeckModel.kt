package cc.cherr.shelldeck

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.*
import cc.cherr.shelldeck.settings.*
import android.graphics.Typeface
import com.termux.terminal.TerminalColors
import net.schmizz.sshj.SSHClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class ShellDeckModel(application: Application) : AndroidViewModel(application) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val runtime = (application as ShellDeckApplication).runtime
    private val dao = runtime.dao
    private val vault = runtime.vault
    @Volatile private var cleared = false
    val sessionManager = runtime.sessions
    val backgroundError get() = runtime.backgroundError
    private val settingsStore = SettingsStore(application)
    private val fontStore = FontStore(application)
    var settings by mutableStateOf(settingsStore.read()); private set
    var fonts by mutableStateOf<List<FontEntry>>(emptyList()); private set
    var typeface by mutableStateOf(Typeface.MONOSPACE); private set
    var fontBusy by mutableStateOf(false); private set
    private var fontRequest = 0L
    var hosts by mutableStateOf<List<HostRecord>>(emptyList()); private set
    var proxies by mutableStateOf<List<ProxyRecord>>(emptyList()); private set
    fun proxyChecks() = cc.cherr.shelldeck.proxy.ProxyChecks(getApplication(), vault)
    var identities by mutableStateOf<List<IdentityRecord>>(emptyList()); private set
    private var working by mutableStateOf(false)
    val backup = cc.cherr.shelldeck.backup.BackupController(application, runtime) { refresh(); updateSettings(settingsStore.read()) }
    val busy get() = working || backup.busy
    var error by mutableStateOf<String?>(null); private set
    init { runtime.attachUi(); refresh(); reloadFonts(); applyPalette(settings) }
    fun updateSettings(value: AppSettings) {
        settingsStore.save(value)
        val old = settings; settings = value
        if (old.backgroundMode != value.backgroundMode) runtime.changeMode(value.backgroundMode)
        if (old.palette != value.palette || old.terminalTheme != value.terminalTheme) applyPalette(value)
        if (old.fontId != value.fontId) reloadFonts()
        sessionManager.sessions.forEach { it.terminal.appearance(typeface, value.fontSize) }
    }
    fun adjustFontSize(delta: Int) {
        val size = (settings.fontSize + delta).coerceIn(8, 32)
        if (size != settings.fontSize) updateSettings(settings.copy(fontSize = size))
    }
    private fun applyPalette(value: AppSettings) {
        val properties = java.util.Properties()
        if (value.palette == TerminalPalette.LIGHT) {
            properties.setProperty("foreground", "#202020"); properties.setProperty("background", "#ffffff")
            properties.setProperty("cursor", "#202020")
        }
        TerminalColors.COLOR_SCHEME.updateWith(value.terminalTheme?.properties() ?: properties)
        sessionManager.sessions.forEach { it.terminal.selectionTheme = value.terminalTheme; it.terminal.colorsChanged() }
    }
    private fun reloadFonts() {
        val request = ++fontRequest; val selected = settings.fontId
        fontBusy = true
        worker.execute {
            val list = fontStore.entries()
            val face = runCatching { fontStore.load(selected) }
            post {
                if (request == fontRequest) {
                    fonts = list; typeface = face.getOrDefault(Typeface.MONOSPACE); fontBusy = false
                    if (face.isFailure) error = "字体加载失败，已临时使用系统等宽字体"
                    sessionManager.sessions.forEach { it.terminal.appearance(typeface, settings.fontSize) }
                }
            }
        }
    }
    fun importFont(uri: Uri) = operation("字体导入失败，请选择有效的 TTF / OTF 文件（不超过 40 MiB）") {
        fontStore.import(uri); post { reloadFonts() }
    }
    fun renameFont(id: String, name: String) = operation("字体名称保存失败") {
        fontStore.rename(id, name); post { reloadFonts() }
    }
    fun deleteFont(id: String) = operation("字体删除失败") {
        fontStore.delete(id); post {
            if (settings.fontId == id) updateSettings(settings.copy(fontId = "system")) else reloadFonts()
        }
    }
    private fun post(action: () -> Unit) { main.post { if (!cleared) action() } }
    fun clearError() { error = null }
    private fun refresh() = worker.execute { reload() }
    private fun reload() {
        try { val h = dao.hosts(); val i = dao.identities(); val p = dao.proxies(); post { hosts = h; identities = i; proxies = p } }
        catch (_: Exception) { post { error = "无法读取本地数据" } }
    }
    private fun operation(failure: String, action: () -> Unit) {
        if (busy || cleared) return
        working = true
        worker.execute {
            try { action(); reload() }
            catch (_: Exception) { post { error = failure } }
            finally { post { working = false } }
        }
    }
    fun saveHost(id: String?, label: String, hostname: String, port: String, username: String, identityId: String?, startupCommand: String = "", jumpHostId: String? = null, proxyId: String? = null): Boolean {
        val number = port.toIntOrNull()
        val host = hostname.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.isBlank() || host.any { it.isWhitespace() || it == '/' } || number == null || number !in 1..65535 || username.isBlank()) {
            error = "请填写有效的服务器地址、端口（1–65535）和用户名"; return false
        }
        if (runCatching { startupCommandLine(startupCommand) }.isFailure) {
            error = "启动命令必须是单行、不含控制字符，且不超过 4095 字节"; return false
        }
        if (proxyId != null && proxies.none { it.id == proxyId }) { error = "代理配置不存在"; return false }
        val candidate = HostRecord().apply { this.id = id ?: "new-host"; this.jumpHostId = jumpHostId }
        try {
            val merged = (hosts.filter { it.id != candidate.id } + candidate).associateBy { it.id }
            merged.values.forEach { cc.cherr.shelldeck.ssh.jumpRoute(it, merged::get) }
        }
        catch (failure: IllegalArgumentException) { error = failure.message; return false }
        operation("服务器保存失败") {
            require(proxyId == null || dao.proxy(proxyId) != null)
            val record = if (id == null) HostRecord() else requireNotNull(dao.host(id))
            dao.saveHost(record.apply {
                this.id = id ?: UUID.randomUUID().toString(); this.label = label.trim().ifBlank { host }
                this.hostname = host; this.port = number; this.username = username.trim(); this.identityId = identityId
                this.startupCommand = startupCommand; this.jumpHostId = jumpHostId; this.proxyId = proxyId
            })
        }
        return true
    }
    fun saveProxy(id: String?, label: String, hostname: String, port: String, remoteDns: Boolean,
        authenticated: Boolean, username: String, password: String, replaceCredentials: Boolean, saved: () -> Unit): Boolean {
        val record = ProxyRecord().apply {
            this.id = id ?: UUID.randomUUID().toString(); this.label = label.trim()
            this.hostname = hostname.trim().removeSurrounding("[", "]"); this.port = port.toIntOrNull() ?: 0
            this.remoteDns = remoteDns; this.authenticated = authenticated
        }
        if (runCatching { cc.cherr.shelldeck.proxy.validateProxy(record) }.isFailure) {
            error = "请填写有效的名称、代理地址和端口（1–65535）"; return false
        }
        val credentials = if (authenticated && replaceCredentials) {
            val u = username.toByteArray(); val p = password.toByteArray()
            if (u.size !in 1..255 || p.size !in 1..255) {
                u.fill(0); p.fill(0); error = "代理用户名和密码必须各为 1–255 个 UTF-8 字节"; return false
            }
            cc.cherr.shelldeck.proxy.ProxyCredentials(u, p)
        } else null
        if (busy || cleared) { credentials?.close(); return false }
        operation("代理保存失败，请检查设备加密密钥是否可用") {
            try {
                record.encryptedCredentials = when {
                    !authenticated -> byteArrayOf()
                    credentials != null -> { val bytes = credentials.encode()
                        try { vault.encrypt("proxy:${record.id}", bytes) } finally { bytes.fill(0) } }
                    else -> requireNotNull(dao.proxy(record.id)).also { require(it.authenticated) }.encryptedCredentials
                }
                dao.saveProxy(record); post(saved)
            } finally { credentials?.close() }
        }
        return true
    }
    fun deleteProxy(id: String) = operation("代理仍被服务器引用，请先修改相关服务器") { check(dao.deleteProxy(id) == 1) }
    fun toggleFavorite(id: String) = operation("收藏更新失败") { dao.toggleFavorite(id) }
    fun duplicateHost(id: String) = operation("复制服务器失败") {
        val original = requireNotNull(dao.host(id))
        dao.saveHost(HostRecord().apply {
            this.id = UUID.randomUUID().toString(); label = "${original.label}（副本）"
            hostname = original.hostname; port = original.port; username = original.username; identityId = original.identityId; startupCommand = original.startupCommand; jumpHostId = original.jumpHostId; proxyId = original.proxyId
        })
    }
    private fun markUsed(host: HostRecord) {
        val timestamp = System.currentTimeMillis()
        worker.execute {
            try { dao.markUsed(host.id, timestamp); reload() }
            catch (_: Exception) { post { error = "最近连接记录保存失败，当前连接不受影响" } }
        }
    }
    fun deleteHost(id: String) = operation("删除服务器失败：请先移除其他主机对该跳板机的引用") { require(dao.hosts().none { it.jumpHostId == id }); dao.deleteHost(id) }
    fun deleteIdentity(id: String) {
        if (hosts.any { it.identityId == id } || sessionManager.sessions.any { !it.ended && it.host.identityId == id }) {
            error = "此身份仍被服务器或活动会话引用，请先修改服务器并关闭相关连接"; return
        }
        operation("删除身份失败，请检查是否仍有服务器引用") { dao.deleteIdentity(id) }
    }
    fun importIdentity(label: String, pasted: String, uri: Uri?, passphrase: String, imported: () -> Unit) {
        if (busy || cleared) return
        val password = passphrase.toCharArray()
        operation("私钥导入失败，请检查文件是否为完整的 OpenSSH / PEM 私钥，以及口令是否正确") {
            var bytes: ByteArray? = null
            try {
                bytes = if (uri != null) getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 256 * 1024)
                        output.write(buffer, 0, count)
                    }
                    buffer.fill(0); output.toByteArray()
                } else pasted.trim().plus("\n").toByteArray()
                SshKeys.configure()
                SSHClient().use { client ->
                    val keys = SshKeys.load(client, bytes, password)
                    val id = UUID.randomUUID().toString()
                    dao.insertIdentity(IdentityRecord().apply {
                        this.id = id; this.label = label.trim().ifBlank { "SSH Key" }
                        algorithm = SshKeys.algorithm(keys.getPublic()); fingerprint = SshKeys.fingerprint(keys.getPublic())
                        publicKey = SshKeys.publicKey(keys.getPublic())
                        encryptedKey = vault.encrypt(id, bytes)
                    })
                }
                post(imported)
            } finally { bytes?.fill(0); password.fill('\u0000') }
        }
    }
    data class PublicKeyDetails(val id: String, val label: String, val algorithm: String, val fingerprint: String, val key: String)
    var publicKeyDetails by mutableStateOf<PublicKeyDetails?>(null); private set
    var publicKeyUnlock by mutableStateOf<String?>(null); private set
    fun dismissPublicKey() { if (!busy) { publicKeyDetails = null; publicKeyUnlock = null } }

    fun generateIdentity(label: String, type: SshKeys.GenerationType, passphrase: String, generated: () -> Unit) {
        if (busy || cleared) return
        val password = passphrase.toCharArray()
        operation("密钥生成失败，请重试") {
            var bytes: ByteArray? = null
            try {
                bytes = SshKeys.generate(type, password)
                SSHClient().use { client ->
                    val key = SshKeys.load(client, bytes, password).getPublic()
                    val identity = IdentityRecord().apply {
                        id = UUID.randomUUID().toString(); this.label = label.trim().ifBlank { type.label }
                        algorithm = SshKeys.algorithm(key); fingerprint = SshKeys.fingerprint(key)
                        publicKey = SshKeys.publicKey(key); encryptedKey = vault.encrypt(id, bytes)
                    }
                    dao.insertIdentity(identity)
                    post { generated(); publicKeyDetails = details(identity) }
                }
            } finally { bytes?.fill(0); password.fill('\u0000') }
        }
    }
    private fun details(identity: IdentityRecord) = PublicKeyDetails(identity.id, identity.label,
        identity.algorithm, identity.fingerprint, requireNotNull(identity.publicKey))

    fun showPublicKey(id: String, passphrase: String? = null) {
        if (busy || cleared) return
        val password = passphrase?.toCharArray()
        operation("公钥读取失败，请检查私钥口令；若设备密钥已失效，需要重新导入私钥") {
            try {
                val identity = requireNotNull(dao.identity(id))
                if (identity.publicKey == null) {
                    val bytes = vault.decrypt(id, identity.encryptedKey)
                    var needsPassword = false
                    try {
                        SshKeys.configure()
                        SSHClient().use { client ->
                            val key = SshKeys.loadWithPassphraseRequest(client, bytes) {
                                needsPassword = password == null
                                password?.copyOf()
                            }.getPublic()
                            identity.publicKey = SshKeys.publicKey(key)
                            dao.savePublicKey(id, requireNotNull(identity.publicKey))
                        }
                    } catch (failure: Exception) {
                        if (needsPassword) { post { publicKeyUnlock = id }; return@operation }
                        throw failure
                    } finally { bytes.fill(0) }
                }
                post { publicKeyUnlock = null; publicKeyDetails = details(identity) }
            } finally { password?.fill('\u0000') }
        }
    }
    fun exportPublicKey(id: String, uri: Uri) = operation("公钥保存失败，请重新选择保存位置") {
        val key = requireNotNull(dao.identity(id)?.publicKey)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write((key + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    fun connect(host: HostRecord, secret: String) {
        val before = sessionManager.sessions.size
        val connection = sessionManager.connect(host, secret)
        if (sessionManager.sessions.size > before) markUsed(connection.host)
        runtime.userRequestedConnection()
        sessionManager.selected?.terminal?.let { it.selectionTheme = settings.terminalTheme; it.appearance(typeface, settings.fontSize) }
    }
    override fun onCleared() {
        cleared = true; backup.close(); runtime.detachUi()
        worker.shutdown()
    }
}

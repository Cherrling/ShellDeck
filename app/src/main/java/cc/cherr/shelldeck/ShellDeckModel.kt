package cc.cherr.shelldeck

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.room.Room
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.TerminalSession
import net.schmizz.sshj.SSHClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellDeckModel(application: Application) : AndroidViewModel(application) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val database = Room.databaseBuilder(application, ShellDeckDatabase::class.java, "shelldeck.db").build()
    private val dao = database.records()
    private val vault = CredentialVault()
    @Volatile private var cleared = false
    private var generation = 0L
    @Volatile private var pendingTrust: CompletableFuture<TrustDecision>? = null
    private var loginSecret: CharArray? = null
    var hosts by mutableStateOf<List<HostRecord>>(emptyList()); private set
    var identities by mutableStateOf<List<IdentityRecord>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var terminal by mutableStateOf<TerminalController?>(null); private set
    var activeHost by mutableStateOf<HostRecord?>(null); private set
    var connectionStatus by mutableStateOf(""); private set
    var challenge by mutableStateOf<HostChallenge?>(null); private set
    init { refresh() }
    private fun post(action: () -> Unit) { main.post { if (!cleared) action() } }
    fun clearError() { error = null }
    private fun refresh() = worker.execute { reload() }
    private fun reload() {
        try { val h = dao.hosts(); val i = dao.identities(); post { hosts = h; identities = i } }
        catch (_: Exception) { post { error = "无法读取本地数据" } }
    }
    private fun operation(failure: String, action: () -> Unit) {
        if (busy) return
        busy = true
        worker.execute {
            try { action(); reload() }
            catch (_: Exception) { post { error = failure } }
            finally { post { busy = false } }
        }
    }
    fun saveHost(id: String?, label: String, hostname: String, port: String, username: String, identityId: String?): Boolean {
        val number = port.toIntOrNull()
        val host = hostname.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.isBlank() || host.any { it.isWhitespace() || it == '/' } || number == null || number !in 1..65535 || username.isBlank()) {
            error = "请填写有效的服务器地址、端口（1–65535）和用户名"; return false
        }
        operation("服务器保存失败") {
            dao.saveHost(HostRecord().apply {
                this.id = id ?: UUID.randomUUID().toString(); this.label = label.trim().ifBlank { host }
                this.hostname = host; this.port = number; this.username = username.trim(); this.identityId = identityId
            })
        }
        return true
    }
    fun deleteHost(id: String) = operation("删除服务器失败") { dao.deleteHost(id) }
    fun deleteIdentity(id: String) {
        if (hosts.any { it.identityId == id }) { error = "此身份仍被服务器引用，请先修改或删除相关服务器"; return }
        operation("删除身份失败，请检查是否仍有服务器引用") { dao.deleteIdentity(id) }
    }
    fun importIdentity(label: String, pasted: String, uri: Uri?, passphrase: String, imported: () -> Unit) {
        val password = passphrase.toCharArray()
        operation("私钥导入失败，请检查格式或口令；目前优先支持 OpenSSH Ed25519 / RSA") {
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
                        encryptedKey = vault.encrypt(id, bytes)
                    })
                }
                post(imported)
            } finally { bytes?.fill(0); password.fill('\u0000') }
        }
    }
    fun connect(host: HostRecord, secret: String) {
        disconnect()
        val attempt = ++generation
        val password = secret.toCharArray().also { loginSecret = it }
        activeHost = host; connectionStatus = "准备连接…"
        val verifier = HostTrust(host.hostname, host.port,
            read = { dao.knownHost(host.hostname, host.port)?.let { HostPin(it.algorithm, it.fingerprint) } },
            save = { pin -> dao.saveKnownHost(KnownHostRecord().apply {
                hostname = host.hostname; port = host.port; algorithm = pin.algorithm; fingerprint = pin.fingerprint
            }) },
            ask = { request ->
                val future = CompletableFuture<TrustDecision>()
                pendingTrust = future
                post {
                    if (generation == attempt && terminal != null) challenge = request
                    else future.complete(TrustDecision.CANCEL)
                }
                try { future.get(90, TimeUnit.SECONDS) }
                catch (_: Exception) { TrustDecision.CANCEL }
                finally { if (pendingTrust === future) pendingTrust = null; post { if (generation == attempt) challenge = null } }
            })
        val transport = SshTransport(host.hostname, host.port, host.username, verifier,
            authenticate = { client ->
                try {
                    val identityId = host.identityId
                    if (identityId == null) client.authPassword(host.username, password)
                    else {
                        val identity = requireNotNull(dao.identity(identityId))
                        val bytes = vault.decrypt(identity.id, identity.encryptedKey)
                        try { client.authPublickey(host.username, SshKeys.load(client, bytes, password)) }
                        finally { bytes.fill(0) }
                    }
                } finally { password.fill('\u0000') }
            },
            status = { status -> post { if (generation == attempt) connectionStatus = status } })
        val controller = TerminalController(getApplication()) { password.fill('\u0000') }
        controller.session = TerminalSession(transport, 5000, controller)
        terminal = controller
    }
    fun trust(decision: TrustDecision) { pendingTrust?.complete(decision); challenge = null }
    fun disconnect() {
        generation++
        pendingTrust?.complete(TrustDecision.CANCEL); pendingTrust = null; challenge = null
        terminal?.close(); terminal = null; activeHost = null
        loginSecret?.fill('\u0000'); loginSecret = null
    }
    override fun onCleared() {
        cleared = true; disconnect()
        worker.execute { database.close() }; worker.shutdown()
    }
}

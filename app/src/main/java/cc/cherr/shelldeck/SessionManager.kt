package cc.cherr.shelldeck

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.TerminalSession
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Process-owned sessions; the foreground service can retain them without an Activity. Main thread only. */
class SessionManager(private val application: Application, private val dao: StoreDao, private val vault: CredentialVault, private val changed: () -> Unit = {}) {
    // Append in connection creation order; switching sessions never reorders this list.
    val sessions = mutableStateListOf<SessionConnection>()
    private var nextSessionNumber = 1L
    var selectedId by mutableStateOf<String?>(null); private set
    val selected get() = sessions.firstOrNull { it.id == selectedId }
    val activeCount get() = sessions.count { !it.ended }
    fun connect(host: HostRecord, secret: String): SessionConnection {
        // Repeated taps while authentication is pending select that attempt instead of opening another.
        sessions.firstOrNull { it.host.id == host.id && !it.ended && !it.terminal.session.isReady }?.let {
            select(it.id); return it
        }
        selected?.terminal?.leave()
        val connection = SessionConnection(application, host, dao, vault, secret, nextSessionNumber++, changed)
        sessions.add(connection); selectedId = connection.id; changed()
        return connection
    }
    fun select(id: String) { require(sessions.any { it.id == id }); selected?.terminal?.leave(); selectedId = id }
    fun home() { selected?.terminal?.leave(); selectedId = null }
    fun close(id: String) {
        val connection = sessions.firstOrNull { it.id == id } ?: return
        if (selectedId == id) home()
        connection.close(); sessions.remove(connection); changed()
    }
    fun closeAll() { sessions.toList().forEach { close(it.id) } }
}

class SessionConnection(application: Application, val host: HostRecord, dao: StoreDao, vault: CredentialVault, secret: String, val number: Long, private val changed: () -> Unit = {}) {
    val id: String = UUID.randomUUID().toString()
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    var ended by mutableStateOf(false); private set
    var state by mutableStateOf(ConnectionState.PREPARING); private set
    val status get() = state.message
    val connected get() = state == ConnectionState.CONNECTED && !ended
    var challenge by mutableStateOf<HostChallenge?>(null); private set
    var requestingPassword by mutableStateOf(false); private set
    var passphraseIdentity by mutableStateOf<String?>(null); private set
    private var pendingTrust: CompletableFuture<TrustDecision>? = null
    private var pendingPassphrase: CompletableFuture<CharArray?>? = null
    private val password = secret.toCharArray()
    val terminal = TerminalController(application) { finish() }
    private lateinit var transport: SshTransport
    val tunnels by lazy { TunnelController { local, host, port -> transport.openTunnel(local, host, port) } }
    val files by lazy { cc.cherr.shelldeck.sftp.SftpController(application) { transport.openSftp() } }
    init {
        fun hop(endpoint: HostRecord): SshHop {
        val verifier = HostTrust(endpoint.hostname, endpoint.port,
            read = { dao.knownHost(endpoint.hostname, endpoint.port)?.let { HostPin(it.algorithm, it.fingerprint) } },
            save = { pin -> dao.saveKnownHost(KnownHostRecord().apply {
                hostname = endpoint.hostname; port = endpoint.port; algorithm = pin.algorithm; fingerprint = pin.fingerprint
            }) },
            ask = { request ->
                val future = CompletableFuture<TrustDecision>()
                main.post {
                    if (disposed || ended || future.isDone) future.complete(TrustDecision.CANCEL)
                    else { pendingTrust = future; challenge = request; state = ConnectionState.VERIFYING }
                }
                try { future.get(90, TimeUnit.SECONDS) }
                catch (_: Exception) { TrustDecision.CANCEL }
                finally {
                    future.complete(TrustDecision.CANCEL)
                    main.post { if (pendingTrust === future) { pendingTrust = null; challenge = null } }
                }
            })
        return SshHop(endpoint.hostname, endpoint.port, verifier,
            authenticate = { client ->
                try {
                    val identityId = endpoint.identityId
                    if (identityId == null) {
                        val value = if (endpoint.id == host.id) password else requestSecret(endpoint.label, true)
                        try { client.authPassword(endpoint.username, value) } finally { value.fill('\u0000') }
                    }
                    else {
                        val identity = requireNotNull(dao.identity(identityId))
                        val bytes = vault.decrypt(identity.id, identity.encryptedKey)
                        try {
                            val key = SshKeys.loadWithPassphraseRequest(client, bytes) {
                                requestSecret("${endpoint.label} · ${identity.label}", false)
                            }
                            client.authPublickey(endpoint.username, key)
                        } finally { bytes.fill(0) }
                    }
                } finally { if (endpoint.id == host.id) password.fill('\u0000') }
            })
        }
        transport = SshTransport(host.hostname, host.port, host.username,
            object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(h: String, p: Int, key: java.security.PublicKey) = false
                override fun findExistingAlgorithms(h: String, p: Int): List<String> = emptyList()
            }, {},
            status = { value -> main.post { if (!disposed && !ended && !state.terminal) state = value } },
            startupCommand = host.startupCommand,
            route = { jumpRoute(host, dao::host).map(::hop) },
            keepAliveSeconds = cc.cherr.shelldeck.settings.SettingsStore(application).read().keepAliveSeconds)
        terminal.session = TerminalSession(transport, 5000, terminal)
        // Start independently of composition; a quick navigation must not leave an unstarted connection.
        terminal.session.updateSize(80, 24, 8, 16)
    }
    private fun requestSecret(label: String, isPassword: Boolean): CharArray {
        val future = CompletableFuture<CharArray?>()
        main.post {
            if (disposed || ended || future.isDone) future.complete(null)
            else { pendingPassphrase = future; requestingPassword = isPassword
                passphraseIdentity = label; state = ConnectionState.PASSPHRASE }
        }
        try { return requireNotNull(future.get(90, TimeUnit.SECONDS)) { "Authentication cancelled" } }
        catch (failure: Exception) {
            future.complete(null); future.getNow(null)?.fill('\u0000'); throw failure
        }
        finally {
            // If cancellation wins, a late UI reply is wiped by submitPassphrase.
            future.complete(null)
            main.post { if (pendingPassphrase === future) { pendingPassphrase = null; passphraseIdentity = null } }
        }
    }
    fun trust(decision: TrustDecision) {
        if (pendingTrust?.complete(decision) == true) state = if (decision == TrustDecision.CANCEL) ConnectionState.CANCELLED else ConnectionState.CONNECTING
        challenge = null
    }
    fun submitPassphrase(secret: String) {
        val chars = secret.toCharArray()
        if (pendingPassphrase?.complete(chars) != true) chars.fill('\u0000') else state = ConnectionState.AUTHENTICATING
        passphraseIdentity = null
    }
    private fun finish() {
        if (ended) return
        files.close(); tunnels.close()
        ended = true; if (!state.terminal) state = ConnectionState.ENDED; password.fill('\u0000')
        pendingTrust?.complete(TrustDecision.CANCEL); pendingTrust = null; challenge = null
        pendingPassphrase?.complete(null); pendingPassphrase = null; passphraseIdentity = null
        changed()
    }
    fun close() { disposed = true; state = ConnectionState.CLOSED; finish(); terminal.close() }
}

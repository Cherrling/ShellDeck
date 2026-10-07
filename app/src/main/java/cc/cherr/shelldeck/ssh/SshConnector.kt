package cc.cherr.shelldeck.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

data class SshHop(val hostname: String, val port: Int, val verify: HostKeyVerifier, val authenticate: (SSHClient) -> Unit)

/** Owns the authenticated SSH route; shared by terminal SSH and short-lived Mosh bootstrap. */
class SshConnector(
    private val hostname: String,
    private val port: Int,
    private val verify: HostKeyVerifier,
    private val authenticate: (SSHClient) -> Unit,
    private val status: (ConnectionState) -> Unit,
    private val route: (() -> List<SshHop>)? = null,
    private val keepAliveSeconds: Int = 60,
    private val connectFirst: ((Socket, String, Int) -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val socket = Socket()
    private val clients = java.util.concurrent.ConcurrentLinkedDeque<SSHClient>()
    fun connect(): SSHClient {
        try {
            check(!closed.get())
            SshKeys.configure()
            val hops = route?.invoke() ?: listOf(SshHop(hostname, port, verify, authenticate))
            require(hops.isNotEmpty() && hops.size <= 5)
            require(keepAliveSeconds == 0 || keepAliveSeconds in 30..600)
            var previous: SSHClient? = null
            hops.forEach { hop ->
                check(!closed.get())
                val config = net.schmizz.sshj.DefaultConfig().apply {
                    keepAliveProvider = net.schmizz.keepalive.KeepAliveProvider.KEEP_ALIVE
                }
                val current = SSHClient(config)
                clients.addFirst(current)
                check(!closed.get())
                if (previous == null) current.socketFactory = object : SocketFactory() {
                    override fun createSocket(): Socket = socket
                    override fun createSocket(h: String, p: Int): Socket = error("Unused")
                    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("Unused")
                    override fun createSocket(h: InetAddress, p: Int): Socket = error("Unused")
                    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("Unused")
                }
                current.connection.keepAlive.keepAliveInterval = keepAliveSeconds
                (current.connection.keepAlive as net.schmizz.keepalive.KeepAliveRunner).maxAliveCount = 3
                current.connectTimeout = 15_000
                current.transport.timeoutMs = 120_000
                current.addHostKeyVerifier(hop.verify)
                status(ConnectionState.CONNECTING)
                if (previous == null) {
                    connectFirst?.invoke(socket, hop.hostname, hop.port)
                    current.connect(hop.hostname, hop.port)
                }
                else current.connectVia(previous!!.newDirectConnection(hop.hostname, hop.port))
                check(!closed.get())
                current.transport.timeoutMs = 20_000
                status(ConnectionState.AUTHENTICATING)
                hop.authenticate(current)
                check(!closed.get())
                previous = current
            }
            return requireNotNull(previous)
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun close() {
        closed.set(true)
        runCatching { socket.close() }
        // Drain again on racing connect failure, even if cancellation already ran.
        while (true) {
            val owned = clients.pollFirst() ?: break
            runCatching { owned.close() }
        }
    }
}

fun connectionFailure(failure: Exception): ConnectionState = when (failure) {
    is cc.cherr.shelldeck.mosh.MoshFailure -> failure.state
    is cc.cherr.shelldeck.proxy.ProxyFailure -> when (failure.kind) {
        cc.cherr.shelldeck.proxy.ProxyFailure.Kind.AUTH -> ConnectionState.PROXY_AUTH_FAILED
        cc.cherr.shelldeck.proxy.ProxyFailure.Kind.TIMEOUT -> ConnectionState.PROXY_TIMEOUT
        cc.cherr.shelldeck.proxy.ProxyFailure.Kind.REJECTED -> ConnectionState.PROXY_REJECTED
        else -> ConnectionState.PROXY_FAILED
    }
    is net.schmizz.sshj.userauth.UserAuthException -> ConnectionState.AUTH_FAILED
    is java.net.UnknownHostException -> ConnectionState.ADDRESS_FAILED
    is java.net.ConnectException -> ConnectionState.CONNECT_FAILED
    is java.net.SocketTimeoutException -> ConnectionState.TIMEOUT
    else -> ConnectionState.FAILED
}

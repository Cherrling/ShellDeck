package cc.cherr.shelldeck.ssh

import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** A single producer feeds the emulator; writes/resizes are serialized independently of reads. */
class SshTransport(
    private val hostname: String,
    private val port: Int,
    private val username: String,
    private val verify: HostKeyVerifier,
    private val authenticate: (SSHClient) -> Unit,
    private val status: (ConnectionState) -> Unit,
    private val startupCommand: String = "",
) : TerminalTransport {
    private val closed = AtomicBoolean()
    private val outputLock = Any()
    private val pendingOutput = java.util.ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var writing = false
    private val reader = Executors.newSingleThreadExecutor()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256))
    private val socket = Socket()
    private val client = java.util.concurrent.atomic.AtomicReference<SSHClient?>()
    @Volatile private var shell: Session.Shell? = null
    private var listener: TerminalTransport.Listener? = null
    override fun start(initial: TerminalSize, listener: TerminalTransport.Listener) {
        this.listener = listener
        reader.execute {
            var result = 1
            try {
                check(!closed.get())
                SshKeys.configure()
                val ssh = SSHClient().also { client.set(it) }
                check(!closed.get())
                ssh.socketFactory = object : SocketFactory() {
                    override fun createSocket(): Socket = socket
                    override fun createSocket(h: String, p: Int): Socket = error("Unused")
                    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("Unused")
                    override fun createSocket(h: InetAddress, p: Int): Socket = error("Unused")
                    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("Unused")
                }
                ssh.connectTimeout = 15_000
                ssh.transport.timeoutMs = 120_000 // Allows time for the fingerprint dialog.
                ssh.addHostKeyVerifier(verify)
                status(ConnectionState.CONNECTING)
                ssh.connect(hostname, port)
                check(!closed.get())
                ssh.transport.timeoutMs = 20_000
                status(ConnectionState.AUTHENTICATING)
                authenticate(ssh)
                check(!closed.get())
                val channel = ssh.startSession()
                channel.allocatePTY("xterm-256color", initial.columns, initial.rows,
                    initial.windowWidthPixels(), initial.windowHeightPixels(), emptyMap())
                // Optional capability hint; servers may decline arbitrary environment variables.
                try { channel.setEnvVar("COLORTERM", "truecolor") } catch (_: java.io.IOException) { }
                val active = channel.startShell().also { shell = it }
                check(!closed.get())
                // Before publishing readiness: UI input cannot overtake this one-time command.
                val startup = startupCommandLine(startupCommand)
                try {
                    if (startup.isNotEmpty()) active.outputStream.apply { write(startup); flush() }
                } finally { startup.fill(0) }
                status(ConnectionState.CONNECTED)
                listener.onReady()
                val bytes = ByteArray(16 * 1024)
                while (!closed.get()) {
                    val count = active.inputStream.read(bytes)
                    if (count < 0) break
                    if (count > 0) listener.onBytes(bytes, count)
                }
                result = 0
                if (!closed.get()) status(ConnectionState.ENDED)
            } catch (failure: Exception) {
                if (!closed.get()) status(when (failure) {
                    is net.schmizz.sshj.userauth.UserAuthException -> ConnectionState.AUTH_FAILED
                    is java.net.UnknownHostException -> ConnectionState.ADDRESS_FAILED
                    is java.net.ConnectException -> ConnectionState.CONNECT_FAILED
                    is java.net.SocketTimeoutException -> ConnectionState.TIMEOUT
                    else -> ConnectionState.FAILED
                })
            } finally {
                try { listener.onClosed(result) } finally {
                    close()
                    // Covers cancellation racing with SSHClient construction/publication.
                    closeClient()
                }
            }
        }
    }
    private fun send(action: () -> Unit) {
        if (closed.get()) return
        try { writer.execute {
            if (!closed.get()) try { action() } catch (_: Exception) { failWrite() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed.get()) failWrite() }
    }
    private fun failWrite() {
        if (!closed.get()) status(ConnectionState.IO_FAILED)
        close() // Closing the socket wakes the reader, which emits onClosed exactly once.
    }
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        synchronized(outputLock) {
            if (closed.get()) return
            if (count > 1024 * 1024 - queuedBytes) { failWrite(); return }
            pendingOutput.addLast(bytes.copyOfRange(offset, offset + count))
            queuedBytes += count
            if (!writing) {
                writing = true
                send { drainWrites() }
            }
        }
    }
    private fun drainWrites() {
        while (!closed.get()) {
            val copy = synchronized(outputLock) {
                if (pendingOutput.isEmpty()) { writing = false; return }
                pendingOutput.removeFirst()
            }
            try { requireNotNull(shell).outputStream.apply { write(copy); flush() } }
            finally { synchronized(outputLock) { queuedBytes -= copy.size }; copy.fill(0) }
        }
    }
    override fun resize(size: TerminalSize, onApplied: Runnable) = send {
        requireNotNull(shell).changeWindowDimensions(size.columns, size.rows, size.windowWidthPixels(), size.windowHeightPixels())
        onApplied.run()
    }
    private fun closeClient() {
        // Ownership is transferred once; UI cancellation and reader completion may race.
        try { client.getAndSet(null)?.close() } catch (_: Exception) { }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // No network wait on the UI thread. Close the owned socket before SSHJ channel cleanup.
        Thread({
            try { socket.close() } catch (_: Exception) { }
            closeClient()
        }, "ssh-close").start()
        synchronized(outputLock) { pendingOutput.forEach { it.fill(0) }; pendingOutput.clear() }
        writer.shutdownNow()
        reader.shutdownNow() // Interrupt SSHJ handshake/auth waits too; socket close alone may not wake them.
    }
}

package cc.cherr.shelldeck.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback only, bounded concurrent streams; stopping closes accepted sockets as well as the listener. */
class LocalTunnel(private val ssh: SSHClient, localPort: Int, val remoteHost: String, val remotePort: Int) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val listener = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val channels = ConcurrentHashMap.newKeySet<DirectConnection>()
    private val permits = Semaphore(8)
    private val pumps = Executors.newFixedThreadPool(16)
    private val acceptor = Executors.newSingleThreadExecutor()
    val port get() = listener.localPort
    init {
        require(localPort in 0..65535 && remotePort in 1..65535)
        require(remoteHost.isNotBlank() && remoteHost.none { it.isWhitespace() || it.isISOControl() || it == '/' })
        try {
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
            acceptor.execute {
                try {
                    while (!closed.get()) {
                        val socket = listener.accept()
                        if (!permits.tryAcquire()) { socket.close(); continue }
                        sockets.add(socket)
                        if (closed.get()) { socket.close(); sockets.remove(socket); permits.release(); break }
                        pumps.execute {
                            var channel: DirectConnection? = null
                            try {
                                channel = ssh.newDirectConnection(remoteHost, remotePort)
                                val active = channel
                                channels.add(active)
                                check(!closed.get())
                                pumps.execute {
                                    try { pump(socket.getInputStream(), active.outputStream); active.outputStream.close() }
                                    catch (_: Exception) { try { socket.close() } catch (_: Exception) {} }
                                }
                                pump(active.inputStream, socket.getOutputStream())
                            } catch (_: Exception) { /* A failed destination affects this stream, not the SSH shell. */ }
                            finally {
                                try { socket.close() } catch (_: Exception) {}
                                channel?.let { channels.remove(it); try { it.close() } catch (_: Exception) {} }
                                sockets.remove(socket); permits.release()
                            }
                        }
                    }
                } catch (_: Exception) { close() }
            }
        } catch (failure: Exception) { close(); throw failure }
    }
    private fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        val bytes = ByteArray(16 * 1024)
        try {
            while (!closed.get()) {
                val count = input.read(bytes)
                if (count < 0) break
                if (count > 0) { output.write(bytes, 0, count); output.flush() }
            }
        } finally { bytes.fill(0) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener.close() } catch (_: Exception) {}
        sockets.forEach { try { it.close() } catch (_: Exception) {} }
        channels.forEach { try { it.close() } catch (_: Exception) {} }
        // Closing streams wakes pumps. Interrupting a channel while it is opening can prevent
        // SSHJ from transmitting its CLOSE packet; let that bounded open finish and clean up.
        pumps.shutdown(); acceptor.shutdownNow()
    }
}

package cc.cherr.shelldeck

import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Drop traffic without closing TCP: exercises half-open detection rather than EOF handling. */
class KeepAliveIntegrationTest {
    @Test fun silentNetworkLossEndsOnceWithoutReconnect() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        val port = System.getenv("SSH_TEST_PORT")!!.toInt()
        val user = System.getenv("SSH_TEST_USER")!!
        val root = File(System.getenv("SSH_TEST_DIR")!!)
        val dropped = AtomicBoolean()
        val accepted = AtomicReference<Socket?>()
        val upstream = AtomicReference<Socket?>()
        val ready = CountDownLatch(1); val ended = CountDownLatch(1)
        val readyCount = AtomicInteger(); val endCount = AtomicInteger()
        val proxy = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val workers = java.util.concurrent.Executors.newFixedThreadPool(3)
        fun pump(source: Socket, destination: Socket) {
            try {
                val bytes = ByteArray(8192)
                while (!source.isClosed) {
                    val count = source.getInputStream().read(bytes)
                    if (count < 0) break
                    if (!dropped.get()) { destination.getOutputStream().write(bytes, 0, count); destination.getOutputStream().flush() }
                }
            } catch (_: Exception) {} // Teardown closes both sockets.
        }
        workers.execute {
            try {
                val a = proxy.accept().also { accepted.set(it) }
                val b = Socket("127.0.0.1", port).also { upstream.set(it) }
                workers.execute { pump(a, b) }; workers.execute { pump(b, a) }
            } catch (_: Exception) {}
        }
        val transport = SshTransport("127.0.0.1", proxy.localPort, user,
            HostTrust("127.0.0.1", proxy.localPort, { null }, {}) { TrustDecision.ONCE },
            { ssh -> ssh.authPublickey(user, SshKeys.load(ssh, File(root, "ed25519").readBytes(), charArrayOf())) }, {},
            keepAliveSeconds = 30)
        try {
            transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
                override fun onReady() { readyCount.incrementAndGet(); ready.countDown() }
                override fun onBytes(bytes: ByteArray, length: Int) {}
                override fun onClosed(code: Int) { endCount.incrementAndGet(); ended.countDown() }
            })
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            dropped.set(true)
            assertTrue("Unanswered keepalive requests must end half-open SSH", ended.await(140, TimeUnit.SECONDS))
            assertEquals(1, readyCount.get()); assertEquals(1, endCount.get())
            assertThrows(IllegalStateException::class.java) { transport.openSftp() }
        } finally {
            transport.close(); proxy.close(); accepted.get()?.close(); upstream.get()?.close(); workers.shutdownNow()
        }
    }
}

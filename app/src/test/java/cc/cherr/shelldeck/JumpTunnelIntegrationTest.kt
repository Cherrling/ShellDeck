package cc.cherr.shelldeck

import cc.cherr.shelldeck.ssh.*
import cc.cherr.shelldeck.data.HostRecord
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JumpTunnelIntegrationTest {
    @Test fun routesRejectCyclesMissingHostsAndTooManyHops() {
        val gateway = HostRecord().apply { id = "gateway" }
        val target = HostRecord().apply { id = "target"; jumpHostId = gateway.id }
        val hosts = listOf(gateway, target).associateBy { it.id }
        assertEquals(listOf(gateway, target), jumpRoute(target, hosts::get))
        gateway.jumpHostId = target.id
        assertThrows(IllegalArgumentException::class.java) { jumpRoute(target, hosts::get) }
        gateway.jumpHostId = "missing"
        assertThrows(IllegalArgumentException::class.java) { jumpRoute(target, hosts::get) }
    }
    @Test fun nestedSshVerifiesBothHopsAndForwardsBytesWhileShellStaysAlive() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        val root = File(System.getenv("SSH_TEST_DIR")!!)
        val port = System.getenv("SSH_TEST_PORT")!!.toInt()
        val user = System.getenv("SSH_TEST_USER")!!
        val ready = CountDownLatch(1); val closed = CountDownLatch(1)
        val verified = java.util.concurrent.atomic.AtomicInteger()
        fun hop(host: String = "127.0.0.1") = SshHop(host, port,
            HostTrust(host, port, { null }, {}) { verified.incrementAndGet(); TrustDecision.ONCE },
            { ssh -> ssh.authPublickey(user, SshKeys.load(ssh, File(root, "ed25519").readBytes(), charArrayOf())) })
        val entry = hop()
        val transport = SshTransport(entry.hostname, port, user, entry.verify, entry.authenticate, {}, route = { listOf(hop(), hop("localhost")) })
        transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
            override fun onReady() { ready.countDown() }
            override fun onBytes(bytes: ByteArray, length: Int) {}
            override fun onClosed(code: Int) { closed.countDown() }
        })
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS)); assertEquals(2, verified.get())
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val worker = Thread { server.accept().use { socket ->
                    val bytes = socket.getInputStream().readNBytes(5)
                    socket.getOutputStream().write(bytes)
                } }.apply { start() }
                val tunnel = transport.openTunnel(0, "127.0.0.1", server.localPort)
                val local = tunnel.port
                // Inspect the owned listener, not a newly bound socket: a released port can
                // still be in TIME_WAIT or be allocated to an unrelated connection.
                val listener = LocalTunnel::class.java.getDeclaredField("listener").let { field ->
                    field.isAccessible = true
                    field.get(tunnel) as ServerSocket
                }
                try {
                    Socket("127.0.0.1", local).use { socket ->
                        socket.soTimeout = 10000
                        socket.getOutputStream().write("hello".toByteArray())
                        assertEquals("hello", String(socket.getInputStream().readNBytes(5)))
                    }
                } finally { tunnel.close() }
                worker.join(10000); assertFalse(worker.isAlive)
                assertTrue("tunnel releases its listening socket", listener.isClosed)
            }
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val accepted = CountDownLatch(1)
                val remoteRead = java.util.concurrent.atomic.AtomicInteger(-2)
                val worker = Thread {
                    try { server.accept().use { socket ->
                        socket.soTimeout = 10000; accepted.countDown(); remoteRead.set(socket.getInputStream().read())
                    } } catch (_: Exception) { remoteRead.set(-3) }
                }.apply { start() }
                val tunnel = transport.openTunnel(0, "127.0.0.1", server.localPort)
                try {
                    Socket("127.0.0.1", tunnel.port).use { browser ->
                        browser.soTimeout = 10000
                        assertTrue(accepted.await(10, TimeUnit.SECONDS))
                        tunnel.close()
                        assertEquals(-1, browser.getInputStream().read())
                    }
                } finally { tunnel.close() }
                worker.join(15000); assertFalse("remote stream released", worker.isAlive)
                assertEquals("remote endpoint receives EOF instead of timing out", -1, remoteRead.get())
            }
            transport.openSftp().use { assertTrue(it.canonicalize(".").isNotBlank()) }
        } finally { transport.close(); assertTrue(closed.await(15, TimeUnit.SECONDS)) }
    }
}

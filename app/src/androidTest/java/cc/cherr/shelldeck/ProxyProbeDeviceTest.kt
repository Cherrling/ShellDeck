package cc.cherr.shelldeck

import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.proxy.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.*

/** Opt-in external smoke check. Offline CI covers protocol deterministically in Socks5Test. */
class ProxyProbeDeviceTest {
    @Test fun cloudflareHttpsWorksDirectlyAndThroughAuthenticatedSocks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalProbe") == "true")
        val vault = CredentialVault()
        ProbeCall().use { call ->
            val result = ProxyProbe.run(null, vault, call)
            assertEquals(result.message, "检测成功", result.message); assertNotNull(result.latencyMs); assertNotNull(result.ip)
        }
        val sockets = CopyOnWriteArrayList<Socket>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newCachedThreadPool()
            val relay = pool.submit(Callable {
                repeat(2) { index ->
                    val client = server.accept(); sockets.add(client); client.soTimeout = 15000
                    val input = DataInputStream(client.getInputStream()); val out = client.getOutputStream()
                    fun read(n: Int) = ByteArray(n).also(input::readFully)
                    assertArrayEquals(byteArrayOf(5,1,2),read(3)); out.write(byteArrayOf(5,2))
                    assertEquals(1,input.readUnsignedByte())
                    assertEquals("fixture",String(read(input.readUnsignedByte())))
                    assertEquals("password",String(read(input.readUnsignedByte()))); out.write(byteArrayOf(1,0))
                    assertArrayEquals(byteArrayOf(5,1,0,3),read(4))
                    val host = String(read(input.readUnsignedByte()))
                    assertEquals(if (index == 0) ProxyProbe.CHECK_HOST else ProxyProbe.TRACE_HOST,host)
                    assertEquals(443,input.readUnsignedShort())
                    val remote = Socket(); sockets.add(remote); remote.connect(InetSocketAddress(host,443),10000); remote.soTimeout=15000
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0))
                    pool.submit { runCatching { input.copyTo(remote.getOutputStream()); remote.shutdownOutput() } }
                    remote.getInputStream().copyTo(out); client.close(); remote.close()
                }
            })
            try {
                val proxy = ProxyRecord().apply {
                    id="probe-fixture"; hostname="127.0.0.1"; port=server.localPort; authenticated=true
                    ProxyCredentials("fixture".toByteArray(),"password".toByteArray()).use { c ->
                        val bytes=c.encode(); try { encryptedCredentials=vault.encrypt("proxy:$id",bytes) } finally { bytes.fill(0) }
                    }
                }
                ProbeCall().use { call ->
                    val result=ProxyProbe.run(proxy,vault,call)
                    assertEquals(result.message,"检测成功",result.message); assertNotNull(result.latencyMs); assertNotNull(result.ip)
                }
                relay.get(30,TimeUnit.SECONDS)
            } finally { sockets.forEach { runCatching { it.close() } }; pool.shutdownNow() }
        }
    }
}

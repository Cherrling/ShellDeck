package cc.cherr.shelldeck

import cc.cherr.shelldeck.data.ProxyRecord
import cc.cherr.shelldeck.proxy.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

class Socks5Test {
    private fun exchange(serverAction: (Socket) -> Unit, clientAction: (ProxyRecord) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            val future = worker.submit(Callable { server.accept().use { it.soTimeout = 3000; serverAction(it) } })
            try { clientAction(ProxyRecord().apply { hostname = "127.0.0.1"; port = server.localPort })
                future.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun remoteDnsAndFragmentedDomainReplyPreserveStreamBoundary() {
        exchange({ socket ->
            val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            assertArrayEquals(byteArrayOf(5,1,0), input.readNBytes(3)); out.write(byteArrayOf(5,0))
            assertArrayEquals(byteArrayOf(5,1,0,3), input.readNBytes(4))
            assertEquals("only-on-proxy.invalid", String(input.readNBytes(input.readUnsignedByte())))
            assertEquals(2222, input.readUnsignedShort())
            (byteArrayOf(5,0,0,3,1,120,0,0) + "SSH-".toByteArray()).forEach { out.write(it.toInt()); out.flush() }
        }, { proxy -> Socket().use { s -> Socks5.connect(s, proxy, "only-on-proxy.invalid", 2222, null)
            assertEquals("SSH-", String(s.getInputStream().readNBytes(4))) } })
    }
    @Test fun credentialsAreConnectionLocalAndIpv6UsesBinaryAddress() {
        exchange({ socket ->
            val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            assertArrayEquals(byteArrayOf(5,1,2), input.readNBytes(3)); out.write(byteArrayOf(5,2))
            assertEquals(1, input.readUnsignedByte())
            assertEquals("user", String(input.readNBytes(input.readUnsignedByte())))
            assertEquals("pass", String(input.readNBytes(input.readUnsignedByte())))
            out.write(byteArrayOf(1,0))
            assertArrayEquals(byteArrayOf(5,1,0,4), input.readNBytes(4)); assertEquals(16, input.readNBytes(16).size)
            assertEquals(22, input.readUnsignedShort()); out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0))
        }, { proxy -> ProxyCredentials("user".toByteArray(), "pass".toByteArray()).use { creds ->
            Socket().use { Socks5.connect(it, proxy, "::1", 22, creds) }
        } })
        assertNull(Socks5.numericAddress("host.example")); assertNull(Socks5.numericAddress("999.1.1.1"))
    }
    @Test fun authenticationDowngradeAndRejectedConnectFailClosed() {
        exchange({ s -> s.getInputStream().readNBytes(3); s.getOutputStream().write(byteArrayOf(5,0)) }, { proxy ->
            Socket().use { s -> ProxyCredentials(byteArrayOf(1), byteArrayOf(2)).use { creds ->
                assertEquals(ProxyFailure.Kind.AUTH, assertThrows(ProxyFailure::class.java) { Socks5.connect(s, proxy, "x.invalid", 22, creds) }.kind)
            } }
        })
        exchange({ s ->
            val input = DataInputStream(s.getInputStream()); input.readNBytes(3); s.getOutputStream().write(byteArrayOf(5,0))
            input.readNBytes(4); input.readNBytes(input.readUnsignedByte()); input.readUnsignedShort()
            s.getOutputStream().write(byteArrayOf(5,5,0,1,0,0,0,0,0,0))
        }, { proxy -> Socket().use { s ->
            assertEquals(ProxyFailure.Kind.REJECTED, assertThrows(ProxyFailure::class.java) { Socks5.connect(s, proxy, "x.invalid", 22, null) }.kind)
        } })
    }
    @Test fun timeoutAndCancellationReleaseBlockedHandshake() {
        exchange({ s -> s.getInputStream().readNBytes(3); Thread.sleep(200) }, { proxy -> Socket().use { s ->
            assertEquals(ProxyFailure.Kind.TIMEOUT, assertThrows(ProxyFailure::class.java) { Socks5.connect(s, proxy, "x.invalid", 22, null, 80) }.kind)
        } })
        val accepted = CountDownLatch(1)
        exchange({ s -> s.getInputStream().readNBytes(3); accepted.countDown(); assertEquals(-1, s.getInputStream().read()) }, { proxy ->
            val worker = Executors.newSingleThreadExecutor(); val s = Socket()
            try {
                val task = worker.submit { assertThrows(ProxyFailure::class.java) { Socks5.connect(s, proxy, "x.invalid", 22, null) } }
                assertTrue(accepted.await(2, TimeUnit.SECONDS)); s.close(); task.get(2, TimeUnit.SECONDS)
            } finally { s.close(); worker.shutdownNow() }
        })
    }
    @Test fun localDnsAndIpv4SendNumericTargets() {
        exchange({ socket ->
            val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            input.readNBytes(3); out.write(byteArrayOf(5,0)); assertArrayEquals(byteArrayOf(5,1,0,1),input.readNBytes(4))
            assertArrayEquals(byteArrayOf(127,0,0,1),input.readNBytes(4)); input.readUnsignedShort()
            out.write(byteArrayOf(5,0,0,4) + ByteArray(18))
        }, { proxy -> proxy.remoteDns = false; Socket().use { Socks5.connect(it,proxy,"127.0.0.1",22,null) } })
    }
    @Test fun httpResponsesAreBoundedAndDoNotTreatRedirectAsSuccess() {
        fun reply(s: String) = readHttpReply(ByteArrayInputStream(s.toByteArray()))
        assertEquals(204, reply("HTTP/1.1 204 No Content\r\n\r\n").status)
        assertEquals("ip=1", String(reply("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nip=1\r\n0\r\n\r\n").body))
        assertEquals(302, reply("HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: https://other.invalid\r\n\r\n").status)
        assertThrows(Exception::class.java) { reply("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n") }
        assertThrows(Exception::class.java) { reply("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nshort") }
        assertThrows(Exception::class.java) { reply("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n") }
    }
}

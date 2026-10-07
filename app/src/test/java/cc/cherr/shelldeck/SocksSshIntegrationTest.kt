package cc.cherr.shelldeck

import cc.cherr.shelldeck.proxy.Socks5
import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SocksSshIntegrationTest {
    @Test fun unresolvedTargetUsesProxyAndHostTrustStillNamesTheSshEndpoint() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        val port = System.getenv("SSH_TEST_PORT")!!.toInt(); val user = System.getenv("SSH_TEST_USER")!!
        val key = File(System.getenv("SSH_TEST_DIR"), "ed25519").readBytes()
        try { for (trust in listOf(true, false)) TestSocksProxy(port).use { proxy ->
            val ready = CountDownLatch(1); val closed = CountDownLatch(1)
            val challenge = AtomicReference<HostChallenge>()
            val verifier = HostTrust("ssh-only.invalid", port, { null }, {}) {
                challenge.set(it); if (trust) TrustDecision.ONCE else TrustDecision.CANCEL
            }
            val transport = SshTransport("ssh-only.invalid", port, user, verifier,
                { ssh -> ssh.authPublickey(user, SshKeys.load(ssh,key,charArrayOf())) }, {},
                connectFirst = { socket, host, p -> Socks5.connect(socket,proxy.record,host,p,null) })
            try {
                transport.start(TerminalSize(80,24,8,16), object : TerminalTransport.Listener {
                    override fun onReady() { ready.countDown() }
                    override fun onBytes(bytes: ByteArray, length: Int) = Unit
                    override fun onClosed(code: Int) { closed.countDown() }
                })
                if (trust) { assertTrue(ready.await(20,TimeUnit.SECONDS)); transport.openSftp().use { assertTrue(it.canonicalize(".").isNotBlank()) } }
                else { assertTrue(closed.await(20,TimeUnit.SECONDS)); assertEquals(1L,ready.count) }
                assertEquals("ssh-only.invalid",challenge.get().hostname); assertEquals(port,challenge.get().port)
                assertEquals("ssh-only.invalid",proxy.requestedHost.get())
            } finally { transport.close(); assertTrue(closed.await(10,TimeUnit.SECONDS)) }
        } } finally { key.fill(0) }
    }
}

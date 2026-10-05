package cc.cherr.shelldeck

import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshIntegrationTest {
    private lateinit var root: File
    private var port = 0
    private lateinit var username: String
    @Before fun setup() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        root = File(requireNotNull(System.getenv("SSH_TEST_DIR"))); port = requireNotNull(System.getenv("SSH_TEST_PORT")).toInt(); username = requireNotNull(System.getenv("SSH_TEST_USER"))
        SshKeys.configure()
    }
    private fun verifier(accept: Boolean = true) = HostTrust("127.0.0.1", port, { null }, {}) { if (accept) TrustDecision.ONCE else TrustDecision.CANCEL }
    @Test fun authenticatesOpenSshAndPemVariantsAndRequestsOnlyNeededPassphrases() {
        for (name in listOf("ed25519", "ed25519-encrypted", "rsa", "rsa-encrypted", "rsa-pem", "rsa-pem-encrypted", "rsa-pkcs8", "rsa-pkcs8-encrypted", "ed25519-pkcs8", "ed25519-pkcs8-encrypted", "ecdsa-pem", "ecdsa-pkcs8")) {
            SSHClient().use { ssh ->
                ssh.addHostKeyVerifier(verifier()); ssh.connect("127.0.0.1", port)
                val password = (if (name.endsWith("encrypted")) "test-passphrase" else "").toCharArray()
                var prompts = 0
                val key = SshKeys.loadWithPassphraseRequest(ssh, File(root, name).readBytes()) { prompts++; password }
                assertEquals(name, if (name.endsWith("encrypted")) 1 else 0, prompts)
                val public = SshKeys.publicKey(key.getPublic())
                // ssh-keygen cannot read every PKCS8 variant; fixture public keys are independently
                // produced by OpenSSH/OpenSSL before conversion to the imported private-key format.
                val publicName = when {
                    name.startsWith("rsa-pem") || name.startsWith("rsa-pkcs8") -> "rsa"
                    name.startsWith("ed25519-pkcs8") -> "ed25519-pkcs8"
                    name.startsWith("ecdsa") -> "ecdsa-pem"
                    else -> name
                }
                val expected = File(root, "$publicName.pub").readText().trim().split(" ").take(2).joinToString(" ")
                assertEquals(name, expected, public)
                // Both exports preserve the key; only explicit empty-password exports may be plaintext.
                for (exportPassword in listOf("export-test-password", "")) {
                    val exported = SshKeys.exportPrivate(key.getPrivate(), exportPassword.toCharArray())
                    try {
                        assertTrue(exported.toString(Charsets.UTF_8).startsWith(if (exportPassword.isEmpty())
                            "-----BEGIN PRIVATE KEY-----" else "-----BEGIN ENCRYPTED PRIVATE KEY-----"))
                        assertEquals(public, SshKeys.publicKey(SshKeys.load(ssh, exported, exportPassword.toCharArray()).getPublic()))
                        val openssl = ProcessBuilder("openssl", "pkey", "-pubout", "-passin", "pass:$exportPassword").start()
                        openssl.outputStream.use { it.write(exported) }
                        val publicPem = openssl.inputStream.bufferedReader().readText()
                        assertEquals("OpenSSL must decode $name export", 0, openssl.waitFor())
                        assertTrue(publicPem.startsWith("-----BEGIN PUBLIC KEY-----"))
                    } finally { exported.fill(0) }
                }
                ssh.authPublickey(username, key)
                ssh.startSession().use { session ->
                    val command = session.exec("printf 'SSH_OK'")
                    assertEquals("SSH_OK", command.inputStream.bufferedReader().readText())
                }
            }
        }
    }
    @Test fun wrongPassphraseMalformedKeyAndUnauthorizedKeyAreRejected() {
        SSHClient().use { ssh ->
            assertThrows(Exception::class.java) { SshKeys.load(ssh, File(root, "ed25519-encrypted").readBytes(), "wrong".toCharArray()) }
            assertThrows(Exception::class.java) { SshKeys.load(ssh, "not a key".toByteArray(), charArrayOf()) }
            ssh.addHostKeyVerifier(verifier()); ssh.connect("127.0.0.1", port)
            assertThrows(Exception::class.java) { ssh.authPublickey(username, SshKeys.load(ssh, File(root, "unauthorized").readBytes(), charArrayOf())) }
        }
    }
    @Test fun cancellingEncryptedKeyPromptAndMalformedKeyDoNotAuthenticate() {
        SSHClient().use { ssh ->
            for (name in listOf("ed25519-encrypted", "rsa-pem-encrypted", "rsa-pkcs8-encrypted")) {
                assertThrows(Exception::class.java) { SshKeys.loadWithPassphraseRequest(ssh, File(root, name).readBytes()) { null } }
            }
            var prompts = 0
            assertThrows(Exception::class.java) { SshKeys.loadWithPassphraseRequest(ssh, "invalid".toByteArray()) { prompts++; null } }
            assertEquals(0, prompts)
        }
    }
    @Test fun refusedHostKeyPreventsAuthentication() {
        SSHClient().use { ssh ->
            ssh.addHostKeyVerifier(verifier(false))
            assertThrows(Exception::class.java) { ssh.connect("127.0.0.1", port) }
            assertFalse(ssh.isAuthenticated)
        }
    }
    @Test fun realTransportSupportsPtyUnicodeResizeAndClose() {
        val ready = CountDownLatch(1); val closed = CountDownLatch(1)
        val received = StringBuffer()
        val transport = SshTransport("127.0.0.1", port, username, verifier(), { ssh ->
            ssh.authPublickey(username, SshKeys.load(ssh, File(root, "ed25519").readBytes(), charArrayOf()))
        }, {}, startupCommand = "stty -echo; export SHELLDECK_START_COUNT=\$(( \${SHELLDECK_START_COUNT:-0} + 1 ))")
        transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
            override fun onReady() { ready.countDown() }
            override fun onBytes(bytes: ByteArray, length: Int) { received.append(String(bytes, 0, length, Charsets.UTF_8)) }
            override fun onClosed(exitCode: Int) { closed.countDown() }
        })
        fun send(text: String) { val bytes = text.toByteArray(); transport.write(bytes, 0, bytes.size) }
        fun waitFor(text: String) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!received.contains(text) && System.nanoTime() < end) Thread.sleep(20)
            assertTrue("Missing expected terminal output: $text", received.contains(text))
        }
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            send("stty -echo; printf '\\123\\123\\110\\137\\117\\113\\n'\r")
            waitFor("SSH_OK")
            send("printf '\\344\\270\\255\\346\\226\\207\\n'\r"); waitFor("中文")
            val resized = CountDownLatch(1)
            transport.resize(TerminalSize(100, 35, 8, 16), Runnable { resized.countDown() })
            assertTrue(resized.await(5, TimeUnit.SECONDS))
            send("stty size\r"); waitFor("35 100")
            send("printf 'START_COUNT:%s\\n' \$SHELLDECK_START_COUNT\r"); waitFor("START_COUNT:1")
            send("export LANG=C.UTF-8 LC_ALL=C.UTF-8\r")
            send("printf 'DELETE_OK:%s\\n' 中\u007fA\r"); waitFor("DELETE_OK:A")
        } finally { transport.close() }
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        transport.close()
    }
    @Test fun closingDuringHostVerificationNeverAuthenticatesAndClosesOnce() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean()
        val authenticated = java.util.concurrent.atomic.AtomicBoolean()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val trust = HostTrust("127.0.0.1", port, { null }, {}) {
            entered.countDown(); release.await(10, TimeUnit.SECONDS); TrustDecision.ONCE
        }
        val transport = SshTransport("127.0.0.1", port, username, trust, { authenticated.set(true) }, {})
        try {
            transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
                override fun onReady() { ready.set(true) }
                override fun onBytes(bytes: ByteArray, length: Int) = Unit
                override fun onClosed(exitCode: Int) { closes.incrementAndGet(); closed.countDown() }
            })
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            transport.close(); transport.close(); release.countDown()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertFalse(authenticated.get()); assertFalse(ready.get()); assertEquals(1, closes.get())
        } finally { release.countDown(); transport.close() }
    }

}

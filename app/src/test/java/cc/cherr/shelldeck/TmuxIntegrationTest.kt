package cc.cherr.shelldeck

import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.*
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TmuxIntegrationTest {
    @Test fun realTmuxMouseScrollEntersCopyModeAndBracketedPasteWaitsForEnter() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        val root = File(requireNotNull(System.getenv("SSH_TEST_DIR")))
        val port = requireNotNull(System.getenv("SSH_TEST_PORT")).toInt()
        val user = requireNotNull(System.getenv("SSH_TEST_USER"))
        val socket = "shelldeck-${UUID.randomUUID()}"
        val prefix = "tmux -L $socket"
        val marker = File(root, "paste-marker")
        SshKeys.configure()
        SSHClient().use { ssh ->
            ssh.addHostKeyVerifier(HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE })
            ssh.connect("127.0.0.1", port)
            ssh.authPublickey(user, SshKeys.load(ssh, File(root, "ed25519").readBytes(), charArrayOf()))
            fun command(text: String): String = ssh.startSession().use { session ->
                session.exec(text).use { cmd ->
                    val out = cmd.inputStream.bufferedReader().readText(); cmd.join(10, TimeUnit.SECONDS)
                    assertEquals(text, 0, cmd.exitStatus); out.trim()
                }
            }
            val ready = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val output = StringBuffer()
            val transport = SshTransport("127.0.0.1", port, user,
                HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE },
                { client -> client.authPublickey(user, SshKeys.load(client, File(root, "ed25519").readBytes(), charArrayOf())) }, {},
                "$prefix attach -t probe")
            fun send(text: String) { val bytes = text.toByteArray(); transport.write(bytes, 0, bytes.size) }
            fun waitFor(description: String, predicate: () -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!predicate() && System.nanoTime() < end) Thread.sleep(50)
                assertTrue(description, predicate())
            }
            try {
                command("$prefix -f /dev/null new-session -d -s probe -x 80 -y 24 '/bin/bash --noprofile --norc'")
                command("$prefix set-option -g mouse on")
                command("$prefix send-keys -t probe 'seq 1 300' Enter")
                transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
                    override fun onReady() { ready.countDown() }
                    override fun onBytes(bytes: ByteArray, length: Int) { output.append(String(bytes, 0, length)) }
                    override fun onClosed(code: Int) { closed.countDown() }
                })
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                waitFor("tmux enables mouse protocol") { output.contains("?1006h") }
                send("\u001b[<64;10;5M")
                waitFor("wheel enters real tmux copy mode") { command("$prefix display-message -p -t probe '#{pane_in_mode}'") == "1" }
                command("$prefix send-keys -t probe -X cancel")
                waitFor("leave copy mode") { command("$prefix display-message -p -t probe '#{pane_in_mode}'") == "0" }
                waitFor("shell enables bracketed paste") { output.contains("?2004h") }
                send("\u001b[200~printf first > ${marker.path}\nprintf second >> ${marker.path}\u001b[201~")
                Thread.sleep(300)
                assertFalse("bracketed newlines must not execute immediately", marker.exists())
                send("\r")
                waitFor("Enter executes pasted commands") { marker.exists() && marker.readText() == "firstsecond" }
            } finally {
                transport.close(); closed.await(10, TimeUnit.SECONDS)
                command("$prefix kill-server")
                marker.delete()
            }
        }
    }
}

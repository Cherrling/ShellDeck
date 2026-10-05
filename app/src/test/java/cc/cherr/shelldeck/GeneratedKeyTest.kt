package cc.cherr.shelldeck

import cc.cherr.shelldeck.ssh.*
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeneratedKeyTest {
    @Test fun generatedKeysRoundTripWithPublicKeyExportAndOptionalPassphrase() {
        for (type in SshKeys.GenerationType.entries) for (encrypted in listOf(false, true)) {
            val password = (if (encrypted) "测试 passphrase" else "").toCharArray()
            val bytes = SshKeys.generate(type, password)
            try {
                SSHClient().use { ssh ->
                    var prompts = 0
                    val key = SshKeys.loadWithPassphraseRequest(ssh, bytes) { prompts++; password.copyOf() }
                    assertEquals(if (encrypted) 1 else 0, prompts)
                    val public = SshKeys.publicKey(key.getPublic())
                    assertTrue(public.startsWith(if (type == SshKeys.GenerationType.ED25519) "ssh-ed25519 " else "ssh-rsa "))
                    assertEquals(2, public.split(" ").size)
                    if (encrypted) assertThrows(Exception::class.java) { SshKeys.load(ssh, bytes, "wrong".toCharArray()) }
                    // OpenSSH independently parses the export; only PUBLIC material reaches the file.
                    val file = File.createTempFile("shelldeck-public-", ".pub")
                    try {
                        file.writeText(public + "\n")
                        val process = ProcessBuilder("ssh-keygen", "-lf", file.path, "-E", "sha256").redirectErrorStream(true).start()
                        val output = process.inputStream.bufferedReader().readText()
                        assertEquals(0, process.waitFor())
                        assertTrue(output.contains(SshKeys.fingerprint(key.getPublic())))
                    } finally { file.delete() }
                    System.getenv("SSH_TEST_DIR")?.let { directory ->
                        File(directory, "authorized_keys").appendText(public + "\n")
                        val port = requireNotNull(System.getenv("SSH_TEST_PORT")).toInt()
                        ssh.addHostKeyVerifier(HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE })
                        ssh.connect("127.0.0.1", port)
                        ssh.authPublickey(requireNotNull(System.getenv("SSH_TEST_USER")), key)
                        ssh.startSession().use { session ->
                            assertEquals("GENERATED_OK", session.exec("printf GENERATED_OK").inputStream.bufferedReader().readText())
                        }
                    }
                }
            } finally { bytes.fill(0); password.fill('\u0000') }
        }
    }
    @Test fun startupCommandRejectsTerminalControlCharactersAndOversizedInput() {
        assertArrayEquals(byteArrayOf(), startupCommandLine("  "))
        assertArrayEquals("tmux new-session -A -s codex\r".toByteArray(), startupCommandLine("tmux new-session -A -s codex"))
        for (invalid in listOf("echo a\necho b", "echo\r", "\u001b[31m", "a".repeat(4096))) {
            assertThrows(IllegalArgumentException::class.java) { startupCommandLine(invalid) }
        }
    }
}

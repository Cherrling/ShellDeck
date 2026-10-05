package cc.cherr.shelldeck

import cc.cherr.shelldeck.sftp.SftpFiles
import cc.cherr.shelldeck.ssh.*
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.termux.terminal.*

class SftpIntegrationTest {
    @Test fun streamingUnicodeFilesCancellationAndNoOverwriteShareTheLiveShell() {
        assumeNotNull(System.getenv("SSH_TEST_DIR"))
        val root = File(requireNotNull(System.getenv("SSH_TEST_DIR")))
        val port = requireNotNull(System.getenv("SSH_TEST_PORT")).toInt()
        val user = requireNotNull(System.getenv("SSH_TEST_USER"))
        val directory = File(root, "files-${UUID.randomUUID()}").apply { mkdir() }
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val echoed = CountDownLatch(1)
        val output = StringBuffer()
        val transport = SshTransport("127.0.0.1", port, user,
            HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE },
            { ssh -> ssh.authPublickey(user, SshKeys.load(ssh, File(root, "ed25519").readBytes(), charArrayOf())) }, {})
        transport.start(TerminalSize(80, 24, 8, 16), object : TerminalTransport.Listener {
            override fun onReady() { ready.countDown() }
            override fun onBytes(bytes: ByteArray, length: Int) {
                output.append(String(bytes, 0, length)); if (output.contains("SFTP_SHELL_OK")) echoed.countDown()
            }
            override fun onClosed(code: Int) { closed.countDown() }
        })
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            transport.openSftp().use { channel ->
                val files = SftpFiles(channel)
                val data = ByteArray(1024 * 1024 + 73) { (it % 251).toByte() }
                val name = "中文 file 'quoted'.bin"
                val target = File(directory, name)
                var progress = 0L
                files.upload(directory.path, name, data.inputStream()) { progress = it }
                assertEquals(data.size.toLong(), progress)
                assertArrayEquals(data, target.readBytes())
                val listing = files.list(directory.path)
                assertEquals(directory.canonicalPath, listing.first)
                assertEquals(name, listing.second.single().name)
                val downloaded = ByteArrayOutputStream()
                files.download(target.path, downloaded) {}
                assertArrayEquals(data, downloaded.toByteArray())
                assertThrows(Exception::class.java) { files.upload(directory.path, name, "overwrite".byteInputStream()) {} }
                assertArrayEquals(data, target.readBytes())
                assertEquals(1, directory.listFiles()!!.size)
                var cancelled = false
                val cancelFiles = SftpFiles(channel) { cancelled }
                assertThrows(InterruptedIOException::class.java) {
                    cancelFiles.upload(directory.path, "cancelled.bin", data.inputStream()) { cancelled = true }
                }
                assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
                val link = File(directory, "link")
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                assertFalse(files.resolve(files.list(directory.path).second.first { it.link }).directory)
                assertThrows(IllegalArgumentException::class.java) { SftpFiles.child(directory.path, "../escape") }
                assertThrows(IllegalArgumentException::class.java) { SftpFiles.child(directory.path, ".") }
            }
            val command = "printf '\\123\\106\\124\\120\\137\\123\\110\\105\\114\\114\\137\\117\\113\\n'\r".toByteArray()
            transport.write(command, 0, command.size)
            assertTrue("SFTP close/cancel must preserve shell", echoed.await(10, TimeUnit.SECONDS))
        } finally {
            transport.close(); assertTrue(closed.await(10, TimeUnit.SECONDS)); directory.deleteRecursively()
        }
        assertThrows(IllegalStateException::class.java) { transport.openSftp() }
    }
}

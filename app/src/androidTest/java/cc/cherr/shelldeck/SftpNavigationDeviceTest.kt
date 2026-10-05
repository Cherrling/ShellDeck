package cc.cherr.shelldeck

import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.sftp.*
import cc.cherr.shelldeck.ssh.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SftpNavigationDeviceTest {
    @Test fun reuseCachedNavigationSupersessionAndIndependentTransferCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sftpDirectory"))
        val root = args.getString("sftpDirectory")!!
        val opened = AtomicInteger(); val stats = AtomicInteger(); val realpaths = AtomicInteger()
        val slowEntered = CountDownLatch(1); val slowRelease = CountDownLatch(1)
        val transferEntered = CountDownLatch(1); val transferRelease = CountDownLatch(1)
        val ssh = SSHClient()
        SshKeys.configure()
        val port = args.getString("sshPort")!!.toInt(); val user = args.getString("sshUser")!!
        ssh.addHostKeyVerifier(HostTrust("127.0.0.1", port, { null }, {}) { TrustDecision.ONCE })
        ssh.connect("127.0.0.1", port)
        ssh.authPublickey(user, SshKeys.load(ssh, File(context.filesDir, "test-ssh-key").readBytes(), charArrayOf()))
        lateinit var controller: SftpController
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(label: String, condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var done = false
            while (!done && System.nanoTime() < until) { main { done = condition() }; if (!done) Thread.sleep(20) }
            assertTrue(label, done)
        }
        ssh.newSFTPClient().use { it.mkdir("$root/a"); it.mkdir("$root/b"); it.mkdir("$root/slow") }
        try {
            main { controller = SftpController(context) {
                val number = opened.incrementAndGet()
                object : SFTPClient(ssh) {
                    override fun canonicalize(path: String): String { realpaths.incrementAndGet(); return super.canonicalize(path) }
                    override fun stat(path: String): FileAttributes { stats.incrementAndGet(); return super.stat(path) }
                    override fun ls(path: String, filter: RemoteResourceFilter): MutableList<RemoteResourceInfo> {
                        Thread.sleep(120) // Deterministic delayed remote operation; no wall-clock performance assertion.
                        if (path == "$root/slow") { slowEntered.countDown(); check(slowRelease.await(10, TimeUnit.SECONDS)) }
                        return super.ls(path, filter)
                    }
                    override fun mkdir(path: String) {
                        if (number > 1) { transferEntered.countDown(); check(transferRelease.await(10, TimeUnit.SECONDS)) }
                        super.mkdir(path)
                    }
                }
            } }
            main { controller.browse(root) }; waitFor("initial listing") { !controller.busy }
            assertEquals(1, opened.get()); assertEquals(1, realpaths.get())
            main { controller.select(controller.entries.single { it.name == "a" }) }
            waitFor("ordinary directory") { !controller.loading }
            assertEquals(1, opened.get()); assertEquals(0, stats.get()); assertEquals(1, realpaths.get())
            main {
                controller.parent()
                assertEquals(root, controller.path); assertTrue(controller.cached); assertTrue(controller.loading)
                assertTrue(controller.entries.any { it.name == "b" })
            }
            waitFor("cache refreshed") { !controller.loading && !controller.cached }
            main { controller.select(controller.entries.single { it.name == "slow" }) }
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS))
            main { controller.browse("$root/a"); controller.browse("$root/b") }
            slowRelease.countDown()
            waitFor("latest request wins") { !controller.loading && controller.path == "$root/b" }
            assertEquals(1, opened.get())
            main { controller.mkdir("cancelled-operation") }
            assertTrue(transferEntered.await(5, TimeUnit.SECONDS))
            main { controller.browse(root) }
            waitFor("browse during transfer") { !controller.loading && controller.path == root }
            main { assertTrue(controller.transferring); controller.cancel() }
            transferRelease.countDown()
            waitFor("cancelled transfer") { !controller.busy }
            main { controller.browse(root) }; waitFor("browser survives cancellation") { !controller.loading }
            assertEquals("one browser and one independent operation channel", 2, opened.get())
            main { controller.browse(root); controller.cancelBrowse(); controller.browse(root) }
            waitFor("browser reopens after explicit cancellation") { !controller.loading && controller.browseMessage == null }
            assertEquals(3, opened.get())
        } finally {
            slowRelease.countDown(); transferRelease.countDown()
            main { controller.close() }
            ssh.close()
        }
    }
}

package cc.cherr.shelldeck

import android.app.Application
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.ConnectionState
import cc.cherr.shelldeck.ssh.TrustDecision
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MoshDeviceTest {
    @Test fun udpOutageAndSourcePortChangeResumeTheSameRemoteProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("moshRoaming") == "true")
        withSession { manager, host, waitFor, main ->
            lateinit var session: SessionConnection
            host.startupCommand = "export SHELLDECK_MOSH_TOKEN=preserved; printf 'ROAMING_READY\\n'; exec /bin/bash --noprofile --norc"
            main { session = manager.connect(host, "") }
            waitFor("trust") { session.challenge != null }
            main { session.trust(TrustDecision.ONCE) }
            waitFor("first UDP state") { session.terminal.session.emulator.screen.transcriptText.contains("ROAMING_READY") }
            // Fixture drops every UDP packet during seconds 4..11, then changes source port.
            Thread.sleep(5500)
            main { session.terminal.session.write("printf '\\122\\105\\123\\125\\115\\105\\104:%s\\n' \"$" + "SHELLDECK_MOSH_TOKEN\"\r") }
            Thread.sleep(1000)
            main { assertFalse(session.terminal.session.emulator.screen.transcriptText.contains("RESUMED:preserved")); assertFalse(session.ended) }
            waitFor("same shell resumed after packet loss and address change") {
                session.terminal.session.emulator.screen.transcriptText.contains("RESUMED:preserved")
            }
        }
    }

    @Test fun missingServerEndsWithInstallableError() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("moshMissing") == "true")
        withSession { manager, host, waitFor, main ->
            lateinit var session: SessionConnection
            main { session = manager.connect(host, "") }
            waitFor("trust") { session.challenge != null }
            main { session.trust(TrustDecision.ONCE) }
            waitFor("missing server detected") { session.ended }
            main { assertEquals(ConnectionState.MOSH_MISSING, session.state) }
        }
    }
    @Test fun nativeMoshAuthenticatesResizesKeepsHiddenOutputAndExits() = withSession { manager, host, waitFor, main ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("moshMissing") != "true")
        lateinit var session: SessionConnection
        host.startupCommand = "printf 'MOSH_STARTUP_OK\\n'; exec /bin/bash --noprofile --norc"
        main { session = manager.connect(host, "") }
        waitFor("host key prompt") { session.challenge != null }
        main { session.trust(TrustDecision.SAVE) }
        waitFor("actual UDP output, not merely local process creation") {
            session.terminal.session.emulator.screen.transcriptText.contains("MOSH_STARTUP_OK")
        }
        main {
            assertEquals(ConnectionState.MOSH_ACTIVE, session.state)
            manager.home()
            session.terminal.session.updateSize(93, 31, 8, 16)
            session.terminal.session.write("stty size; printf '\\110\\111\\104\\104\\105\\116\\137\\115\\117\\123\\110\\n'\r")
        }
        waitFor("remote PTY resized and hidden output consumed") {
            val text = session.terminal.session.emulator.screen.transcriptText
            text.contains("31 93") && text.contains("HIDDEN_MOSH")
        }
        main { manager.select(session.id); session.terminal.session.write("printf '\\344\\270\\255\\346\\226\\207\\n'; exit\r") }
        waitFor("remote process exit") { session.ended }
        main {
            assertEquals(ConnectionState.ENDED, session.state)
            assertTrue(session.terminal.session.emulator.screen.transcriptText.contains("中文"))
        }
    }
    @Test fun closeDuringAuthenticationAndActiveSessionDoesNotLeaveClientProcesses() = withSession { manager, host, waitFor, main ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("moshMissing") != "true")
        lateinit var cancelled: SessionConnection
        main { cancelled = manager.connect(host, "") }
        waitFor("pending authentication") { cancelled.challenge != null }
        main { manager.close(cancelled.id) }
        waitFor("cancelled") { cancelled.ended }
        lateinit var active: SessionConnection
        host.startupCommand = "printf 'CLOSE_TEST_READY\\n'; exec /bin/bash --noprofile --norc"
        main { active = manager.connect(host, "") }
        waitFor("new independent trust prompt") { active.challenge != null }
        main { active.trust(TrustDecision.ONCE) }
        waitFor("UDP session") { active.terminal.session.emulator.screen.transcriptText.contains("CLOSE_TEST_READY") }
        main { manager.close(active.id) }
        Thread.sleep(3000)
        val ownUid = android.os.Process.myUid()
        val children = File("/proc").listFiles().orEmpty().filter { it.name.toIntOrNull() != null }.mapNotNull { dir ->
            runCatching {
                val status = File(dir, "status").readText()
                if (!Regex("(?m)^Uid:\\s+$ownUid\\s").containsMatchIn(status)) null
                else File(dir, "cmdline").readText()
            }.getOrNull()
        }
        assertFalse("native client was cleaned up", children.any { it.contains("/libmosh-client.so") })
    }
    private fun withSession(block: (SessionManager, HostRecord, (String, () -> Boolean) -> Unit, (() -> Unit) -> Unit) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("moshPort"), args.getString("sshUser"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, ShellDeckDatabase::class.java).build()
        val vault = CredentialVault(); val keyId = UUID.randomUUID().toString()
        val key = File(app.filesDir, "test-ssh-key").readBytes()
        try { db.records().insertIdentity(IdentityRecord().apply { id = keyId; label = "Test"; encryptedKey = vault.encrypt(id, key) }) }
        finally { key.fill(0) }
        lateinit var manager: SessionManager
        val main: (() -> Unit) -> Unit = { inst.runOnMainSync(it) }
        val waitFor: (String, () -> Boolean) -> Unit = { message, predicate ->
            val deadline = System.nanoTime() + 25_000_000_000L
            var ok = false
            while (!ok && System.nanoTime() < deadline) { main { ok = predicate() }; if (!ok) Thread.sleep(30) }
            assertTrue(message, ok)
        }
        val host = HostRecord().apply {
            id = UUID.randomUUID().toString(); label = "Mosh fixture"; hostname = "10.0.2.2"
            port = args.getString("moshPort")!!.toInt(); username = args.getString("sshUser")!!
            identityId = keyId; protocol = "mosh"
        }
        try { main { manager = SessionManager(app, db.records(), vault) }; block(manager, host, waitFor, main) }
        finally { main { manager.closeAll() }; Thread.sleep(2500); db.close() }
    }
}

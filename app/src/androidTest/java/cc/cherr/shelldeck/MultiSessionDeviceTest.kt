package cc.cherr.shelldeck

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.TrustDecision
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MultiSessionDeviceTest {
    @Test fun realSshSessionsSurviveHomeAndClosingAnotherSession() {
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sshUser"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, ShellDeckDatabase::class.java).build()
        val vault = CredentialVault(); val identityId = UUID.randomUUID().toString()
        val key = File(app.filesDir, "test-ssh-key").readBytes()
        try { db.records().insertIdentity(IdentityRecord().apply {
            id = identityId; label = "Ephemeral test key"; encryptedKey = vault.encrypt(id, key)
        }) } finally { key.fill(0) }
        lateinit var manager: SessionManager
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(description: String, predicate: () -> Boolean) {
            val end = System.nanoTime() + 20_000_000_000L
            var ok = false
            while (!ok && System.nanoTime() < end) { main { ok = predicate() }; if (!ok) Thread.sleep(20) }
            assertTrue(description, ok)
        }
        val host = HostRecord().apply { id = UUID.randomUUID().toString(); label = "Loopback"; hostname = "127.0.0.1"
            port = args.getString("sshPort")!!.toInt(); username = args.getString("sshUser")!!; this.identityId = identityId }
        try {
            main { manager = SessionManager(app, db.records(), vault); manager.connect(host, ""); manager.connect(host, "") }
            waitFor("two independently pending fingerprint prompts") { manager.sessions.size == 2 && manager.sessions.all { it.challenge != null } }
            lateinit var first: SessionConnection; lateinit var second: SessionConnection
            main { first = manager.sessions[0]; second = manager.sessions[1]; first.trust(TrustDecision.ONCE); second.trust(TrustDecision.ONCE) }
            main {
                assertEquals(1L, first.number); assertEquals(2L, second.number)
                manager.select(first.id)
                assertEquals(listOf(first.id, second.id), manager.sessions.map { it.id })
            }
            waitFor("both connections authenticated") { first.terminal.session.isReady && second.terminal.session.isReady }
            main { manager.home(); assertNull(manager.selected); first.terminal.session.write("printf '\\110\\111\\104\\104\\105\\116\\137\\117\\113\\n'\r") }
            waitFor("hidden terminal keeps parsing output") { first.terminal.session.emulator.screen.transcriptText.contains("HIDDEN_OK") }
            main { manager.select(second.id); manager.close(first.id); assertEquals(second.id, manager.selectedId); assertTrue(second.terminal.session.isReady) }
            main { manager.connect(host, "") }
            waitFor("third connection waiting for its own fingerprint") { manager.selected?.challenge != null }
            main { val third = manager.selected!!; assertEquals(3L, third.number); assertEquals(2L, second.number); manager.close(third.id); assertTrue(third.ended); assertTrue(second.terminal.session.isReady) }
            main { manager.select(second.id); assertEquals(1, manager.sessions.size) }
        } finally { main { manager.closeAll() }; db.close() }
    }
}

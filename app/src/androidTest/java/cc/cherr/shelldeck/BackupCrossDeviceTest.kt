package cc.cherr.shelldeck

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.backup.*
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.settings.*
import cc.cherr.shelldeck.ssh.*
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeNotNull
import java.io.File

/** Run normally for a disk-backed round trip, or with backupPhase=source/target on separate devices. */
class BackupCrossDeviceTest {
    @Test fun restoredIdentityCanAuthenticateToRealOpenSsh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sshUser"))
        val phase = args.getString("backupPhase", "roundtrip")!!
        val port = requireNotNull(args.getString("sshPort")) { "Use scripts/android_ssh_tests.py" }.toInt()
        val username = requireNotNull(args.getString("sshUser"))
        val name = "cross-device-restore.db"
        context.deleteDatabase(name)
        val settings = SettingsStore(context, "cross-device-restore")
        val vault = CredentialVault()
        val db = Room.databaseBuilder(context, ShellDeckDatabase::class.java, name).build()
        val password = "cross-device-backup-password".toCharArray()
        val backup = File(context.filesDir, "cross-backup.sdbak")
        val oldEnvelope = File(context.filesDir, "cross-source-envelope.bin")
        val repository = BackupRepository(db, vault, settings) { setOf("maple", "system") }
        try {
            SshKeys.configure()
            if (phase != "target") {
                val keyBytes = File(context.filesDir, "test-ssh-key").readBytes()
                try { SSHClient().use { ssh ->
                    val public = SshKeys.load(ssh, keyBytes, charArrayOf()).getPublic()
                    val identity = IdentityRecord().apply {
                        id = "cross-identity"; label = "Migrated key"; algorithm = SshKeys.algorithm(public)
                        fingerprint = SshKeys.fingerprint(public); publicKey = SshKeys.publicKey(public)
                        encryptedKey = vault.encrypt(id, keyBytes)
                    }
                    db.records().insertIdentity(identity)
                    oldEnvelope.writeBytes(identity.encryptedKey) // Ciphertext only, proves the second device cannot use the source key.
                } } finally { keyBytes.fill(0) }
                db.records().saveHost(HostRecord().apply {
                    id = "cross-host"; label = "Migrated host"; hostname = "127.0.0.1"; this.port = port
                    this.username = username; identityId = "cross-identity"; startupCommand = "echo restored"
                })
                settings.saveRestored(AppSettings(fontSize = 19, theme = ThemeMode.DARK))
                backup.writeBytes(repository.export(password))
                if (phase == "source") return
                db.records().deleteHost("cross-host"); db.records().deleteIdentity("cross-identity")
                settings.saveRestored(AppSettings())
            } else {
                assertThrows(Exception::class.java) { vault.decrypt("cross-identity", oldEnvelope.readBytes()) }
            }
            repository.prepare(backup.readBytes(), password).use { plan ->
                assertTrue(db.records().identities().isEmpty())
                repository.restore(plan, emptyMap(), emptyMap(), true)
            }
            if (phase == "target") assertThrows(Exception::class.java) { vault.decrypt("cross-identity", oldEnvelope.readBytes()) }
            val host = requireNotNull(db.records().host("cross-host"))
            val identity = requireNotNull(db.records().identity(host.identityId!!))
            assertEquals(19, settings.read().fontSize); assertEquals("echo restored", host.startupCommand)
            val bytes = vault.decrypt(identity.id, identity.encryptedKey)
            try { SSHClient().use { ssh ->
                ssh.connectTimeout = 10000; ssh.timeout = 10000
                ssh.addHostKeyVerifier(HostTrust(host.hostname, host.port, { null }, {}) { TrustDecision.ONCE })
                ssh.connect(host.hostname, host.port)
                ssh.authPublickey(host.username, SshKeys.load(ssh, bytes, charArrayOf()))
                ssh.startSession().use { session -> assertEquals("RESTORED_SSH_OK", session.exec("printf RESTORED_SSH_OK").inputStream.bufferedReader().readText()) }
            } } finally { bytes.fill(0) }
            db.close()
            val reopened = Room.databaseBuilder(context, ShellDeckDatabase::class.java, name).build()
            try { assertEquals(identity.fingerprint, reopened.records().identity(identity.id)!!.fingerprint) }
            finally { reopened.close() }
        } finally {
            password.fill('\u0000'); db.close(); context.deleteDatabase(name); context.deleteSharedPreferences("cross-device-restore")
            if (phase != "source") { backup.delete(); oldEnvelope.delete() }
        }
    }
}

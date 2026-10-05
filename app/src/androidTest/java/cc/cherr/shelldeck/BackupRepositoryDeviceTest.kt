package cc.cherr.shelldeck

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.backup.*
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.settings.*
import cc.cherr.shelldeck.ssh.SshKeys
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class BackupRepositoryDeviceTest {
    @Test fun restoreRewrapsKeysMapsCopiesAndPreservesExistingRecordsByDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ShellDeckDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, ShellDeckDatabase::class.java).build()
        val sourceVault = CredentialVault("source-$suffix"); val targetVault = CredentialVault("target-$suffix")
        val sourceSettings = SettingsStore(context, "source-$suffix"); val targetSettings = SettingsStore(context, "target-$suffix")
        val source = BackupRepository(sourceDb, sourceVault, sourceSettings) { setOf("maple", "system") }
        val target = BackupRepository(targetDb, targetVault, targetSettings) { setOf("maple", "system") }
        val bytes = SshKeys.generate(SshKeys.GenerationType.ED25519, "inner-passphrase".toCharArray())
        val password = "backup-password".toCharArray()
        try {
            val identity = IdentityRecord().apply {
                id = "identity"; label = "My key"; algorithm = "ssh-ed25519"; fingerprint = "SHA256:fixture"
                encryptedKey = sourceVault.encrypt(id, bytes)
            }
            sourceDb.records().insertIdentity(identity)
            sourceDb.records().saveHost(HostRecord().apply {
                id = "host"; label = "Source host"; hostname = "example.com"; username = "dev"; identityId = "identity"
                startupCommand = "tmux new-session -A -s codex"; favorite = true; lastUsedAt = 42
            })
            val colors = TerminalTheme.preset(TerminalPalette.LIGHT).withColor(2, TerminalTheme.parse("#226688"))
            sourceSettings.saveRestored(AppSettings(theme = ThemeMode.DARK, fontSize = 21, fontId = "missing-imported-font", terminalTheme = colors))
            targetDb.records().saveKnownHost(KnownHostRecord().apply {
                hostname = "example.com"; port = 22; algorithm = "ssh-ed25519"; fingerprint = "SHA256:local-pin"
            })
            val file = source.export(password)
            val corrupted = file.copyOf(); corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            for (attempt in listOf(corrupted, file.copyOf(file.size - 1))) assertThrows(Exception::class.java) { target.prepare(attempt, password) }
            assertThrows(Exception::class.java) { target.prepare(file, "wrong".toCharArray()) }
            assertTrue(targetDb.records().hosts().isEmpty()); assertTrue(targetDb.records().identities().isEmpty())
            assertEquals(AppSettings(), targetSettings.read())
            target.prepare(file, password).use { plan ->
                assertTrue(plan.missingFont); assertEquals("maple", plan.settings.fontId)
                assertTrue(targetDb.records().identities().isEmpty()) // Preview has no database side effects.
                val result = target.restore(plan, emptyMap(), emptyMap(), true)
                assertEquals(1, result.identities); assertEquals(1, result.hosts); assertTrue(result.settingsSaved)
            }
            val restored = targetDb.records().identity("identity")!!
            assertFalse(identity.encryptedKey.contentEquals(restored.encryptedKey))
            assertArrayEquals(bytes, targetVault.decrypt(restored.id, restored.encryptedKey))
            assertThrows(Exception::class.java) { sourceVault.decrypt(restored.id, restored.encryptedKey) }
            assertEquals(21, targetSettings.read().fontSize)
            assertEquals(colors, targetSettings.read().terminalTheme)
            val local = targetDb.records().host("host")!!.apply { label = "Local edit" }
            targetDb.records().saveHost(local)
            target.prepare(file, password).use { target.restore(it, emptyMap(), emptyMap(), false) }
            assertEquals("Local edit", targetDb.records().host("host")!!.label)
            target.prepare(file, password).use { plan ->
                target.restore(plan, mapOf("identity" to RestoreChoice.COPY), mapOf("host" to RestoreChoice.COPY), false)
            }
            val copy = targetDb.records().hosts().single { it.id != "host" }
            assertNotEquals("identity", copy.identityId)
            assertArrayEquals(bytes, targetVault.decrypt(copy.identityId!!, targetDb.records().identity(copy.identityId!!)!!.encryptedKey))
            assertEquals("tmux new-session -A -s codex", copy.startupCommand)
            target.prepare(file, password).use { plan ->
                target.restore(plan, mapOf("identity" to RestoreChoice.REPLACE), mapOf("host" to RestoreChoice.REPLACE), false)
            }
            assertEquals("Source host", targetDb.records().host("host")!!.label)
            target.prepare(file, password).use { plan ->
                targetDb.records().toggleFavorite("host")
                assertThrows(Exception::class.java) { target.restore(plan, emptyMap(), emptyMap(), true) }
            }
            assertEquals(2, targetDb.records().identities().size)
            assertEquals(2, targetDb.records().hosts().size)
            assertEquals("SHA256:local-pin", targetDb.records().knownHost("example.com", 22)!!.fingerprint)
            file.fill(0)
        } finally {
            bytes.fill(0); password.fill('\u0000'); sourceDb.close(); targetDb.close()
            context.deleteSharedPreferences("source-$suffix"); context.deleteSharedPreferences("target-$suffix")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("source-$suffix"); deleteEntry("target-$suffix") }
        }
    }
    @Test fun settingsRejectExcessiveNestingAndKeepQuotedMacroCharacters() {
        assertThrows(Exception::class.java) { SettingsStore.decode("[".repeat(1000) + "]".repeat(1000)) }
        assertThrows(Exception::class.java) { SettingsStore.decode("{\"version\":1,\"size\":999}") }
        val malformed = org.json.JSONObject(SettingsStore.encode(AppSettings())).put("keyboard", "invalid")
        assertThrows(Exception::class.java) { SettingsStore.decode(malformed.toString()) }
        val original = AppSettings(keyboard = cc.cherr.shelldeck.keyboard.KeyboardProfile.default().let { profile ->
            profile.copy(rows = profile.rows.mapIndexed { row, slots -> if (row == 0) slots.mapIndexed { index, slot ->
                if (index == 0) slot.copy(action = cc.cherr.shelldeck.keyboard.KeyAction.Macro("echo '{[]}\\\"'")) else slot
            } else slots })
        })
        assertEquals(original, SettingsStore.decode(SettingsStore.encode(original)))
    }
    @Test fun transactionRollsBackKeyWritesIfHostWriteFails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val db = Room.inMemoryDatabaseBuilder(context, ShellDeckDatabase::class.java).build()
        val vault = CredentialVault("rollback-$suffix")
        val settings = SettingsStore(context, "rollback-$suffix")
        try {
            val key = IdentityRecord().apply { id = "key"; label = "Key"; algorithm = "ssh-ed25519"; fingerprint = "SHA256:fixture" }
            val host = HostRecord().apply { id = "host"; label = "Host"; hostname = "example.com"; username = "dev"; identityId = "key" }
            val data = BackupData(listOf(BackupIdentity(key, byteArrayOf(1,2,3))), listOf(host), SettingsStore.encode(AppSettings(fontSize = 20)))
            val payload = BackupCodec.encode(data)
            val file = try { BackupCrypto.encrypt(payload, "backup-password".toCharArray()) } finally { payload.fill(0); data.close() }
            val repo = BackupRepository(db, vault, settings) { setOf("maple") }
            repo.prepare(file, "backup-password".toCharArray()).use { plan ->
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_host BEFORE INSERT ON hosts BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
                assertThrows(Exception::class.java) { repo.restore(plan, emptyMap(), emptyMap(), true) }
            }
            assertTrue(db.records().identities().isEmpty()); assertTrue(db.records().hosts().isEmpty())
            assertEquals(AppSettings(), settings.read())
        } finally {
            db.close(); context.deleteSharedPreferences("rollback-$suffix")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("rollback-$suffix") }
        }
    }
}

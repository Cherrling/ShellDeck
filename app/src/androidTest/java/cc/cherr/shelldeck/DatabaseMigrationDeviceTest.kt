package cc.cherr.shelldeck

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DatabaseMigrationDeviceTest {
    @Test fun versionOneKeepsCredentialsHostReferencesAndKnownHostPins() = verifyMigration(1)
    @Test fun versionTwoKeepsCredentialsAndHostHistory() = verifyMigration(2)
    @Test fun versionThreeKeepsSettingsAndAddsDirectRoute() = verifyMigration(3)
    private fun verifyMigration(version: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open("cc.cherr.shelldeck.data.ShellDeckDatabase/$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val vault = CredentialVault()
        val fixture = "migration fixture, not a private key".toByteArray()
        val encrypted = vault.encrypt("identity", fixture)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO identities (id, label, fingerprint, algorithm, encryptedKey) VALUES (?, ?, ?, ?, ?)", arrayOf<Any>("identity", "My key", "SHA256:fixture", "ED25519", encrypted))
                old.execSQL("INSERT INTO hosts (id, label, hostname, port, username, identityId) VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>("host", "My host", "example.com", 22, "dev", "identity"))
                old.execSQL("INSERT INTO known_hosts VALUES (?, ?, ?, ?)", arrayOf<Any>("example.com", 22, "ED25519", "SHA256:server"))
                if (version >= 2) old.execSQL("UPDATE hosts SET favorite = 1, lastUsedAt = 42")
                old.version = version
            }
            val db = Room.databaseBuilder(context, ShellDeckDatabase::class.java, name).addMigrations(ShellDeckDatabase.MIGRATION_1_2, ShellDeckDatabase.MIGRATION_2_3, ShellDeckDatabase.MIGRATION_3_4).build()
            try {
                val dao = db.records()
                val host = dao.host("host")!!
                assertEquals("identity", host.identityId); assertEquals("My host", host.label)
                assertEquals(version >= 2, host.favorite); assertEquals(if (version >= 2) 42L else 0L, host.lastUsedAt)
                assertNull(host.jumpHostId); assertEquals("", host.startupCommand); assertNull(dao.identity("identity")!!.publicKey)
                assertArrayEquals(encrypted, dao.identity("identity")!!.encryptedKey)
                assertArrayEquals(fixture, vault.decrypt("identity", dao.identity("identity")!!.encryptedKey))
                assertEquals("SHA256:server", dao.knownHost("example.com", 22)!!.fingerprint)
                dao.toggleFavorite(host.id); dao.markUsed(host.id, 123)
                assertEquals(version < 2, dao.host(host.id)!!.favorite); assertEquals(123L, dao.host(host.id)!!.lastUsedAt)
            } finally { db.close() }
            // Reopen without replaying the migration; the generated v3 schema must remain valid.
            val reopened = Room.databaseBuilder(context, ShellDeckDatabase::class.java, name).addMigrations(ShellDeckDatabase.MIGRATION_1_2, ShellDeckDatabase.MIGRATION_2_3, ShellDeckDatabase.MIGRATION_3_4).build()
            try {
                assertEquals(version < 2, reopened.records().host("host")!!.favorite)
                assertEquals(123L, reopened.records().host("host")!!.lastUsedAt)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name); fixture.fill(0) }
    }
}

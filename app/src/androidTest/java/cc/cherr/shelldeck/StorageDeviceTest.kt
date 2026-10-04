package cc.cherr.shelldeck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.SshKeys
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    @Test fun realAndroidKeystoreRoundTripAndRoomReferences() {
        // Exercise JCA after SSH's BC initialization, as in production.
        SshKeys.configure()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val privateBytes = "device-only-test-secret".toByteArray()
        val blob = CredentialVault().encrypt(id, privateBytes)
        assertFalse(blob.contentEquals(privateBytes))
        assertArrayEquals(privateBytes, CredentialVault().decrypt(id, blob))
        assertThrows(Exception::class.java) { CredentialVault().decrypt("other-id", blob) }
        val dbName = "test-${UUID.randomUUID()}.db"
        try {
            Room.databaseBuilder(context, ShellDeckDatabase::class.java, dbName).build().let { db ->
              try {
                val dao = db.records()
                dao.insertIdentity(IdentityRecord().apply { this.id = id; label = "Test"; encryptedKey = blob })
                for (hostId in listOf("a", "b")) dao.saveHost(HostRecord().apply { this.id = hostId; identityId = id })
                assertEquals(2, dao.identityUsers(id))
                assertThrows(Exception::class.java) { dao.deleteIdentity(id) }
              } finally { db.close() }
            }
            Room.databaseBuilder(context, ShellDeckDatabase::class.java, dbName).build().let { db ->
              try {
                val dao = db.records()
                assertArrayEquals(privateBytes, CredentialVault().decrypt(id, dao.identity(id).encryptedKey))
                dao.deleteHost("a"); dao.deleteHost("b"); dao.deleteIdentity(id)
                assertNull(dao.identity(id))
              } finally { db.close() }
            }
        } finally { context.deleteDatabase(dbName) }
    }
}

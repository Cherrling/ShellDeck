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
    @Test fun androidParsesAndSignsImportedEd25519AndRsaKeys() {
        SshKeys.configure()
        val ed = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val encoded = org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.encodePrivateKey(ed)
        val edPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(encoded) +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
        val rsa = java.security.KeyPairGenerator.getInstance("RSA", "BC").apply { initialize(2048) }.generateKeyPair()
        val rsaText = java.io.StringWriter()
        org.bouncycastle.openssl.jcajce.JcaPEMWriter(rsaText).use { it.writeObject(rsa) }
        for ((pem, algorithm) in listOf(edPem to "Ed25519", rsaText.toString() to "SHA256withRSA")) {
            net.schmizz.sshj.SSHClient().use { client ->
                val parsed = SshKeys.load(client, pem.toByteArray(), charArrayOf())
                val signer = net.schmizz.sshj.common.SecurityUtils.getSignature(algorithm)
                signer.initSign(parsed.getPrivate()); signer.update(byteArrayOf(1, 2, 3))
                val signature = signer.sign()
                signer.initVerify(parsed.getPublic()); signer.update(byteArrayOf(1, 2, 3))
                assertTrue(signer.verify(signature))
            }
        }
    }
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

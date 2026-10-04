package cc.cherr.shelldeck

import cc.cherr.shelldeck.data.SecretEnvelope
import cc.cherr.shelldeck.ssh.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import javax.crypto.KeyGenerator

class SshSecurityTest {
    @Test fun authenticatedEncryptionBindsIdentityAndRejectsTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "private-test-key".toByteArray()
        val a = SecretEnvelope.encrypt(key, "identity-a", plaintext)
        val b = SecretEnvelope.encrypt(key, "identity-a", plaintext)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plaintext, SecretEnvelope.decrypt(key, "identity-a", a))
        assertThrows(Exception::class.java) { SecretEnvelope.decrypt(key, "identity-b", a) }
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SecretEnvelope.decrypt(key, "identity-a", a) }
        assertThrows(Exception::class.java) { SecretEnvelope.decrypt(key, "identity-a", byteArrayOf(1)) }
    }
    @Test fun unknownOnceCancelSavedAndChangedKeysHaveExplicitPolicies() {
        SshKeys.configure()
        val first = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair().public
        val second = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair().public
        var pin: HostPin? = null
        var decision = TrustDecision.CANCEL
        var asked = 0
        val verifier = HostTrust("example.test", 2222, { pin }, { pin = it }) {
            assertEquals("example.test", it.hostname); assertEquals(2222, it.port)
            asked++; decision
        }
        assertFalse(verifier.verify("example.test", 2222, first)); assertNull(pin)
        decision = TrustDecision.ONCE
        assertTrue(verifier.verify("example.test", 2222, first)); assertNull(pin)
        decision = TrustDecision.SAVE
        assertTrue(verifier.verify("example.test", 2222, first)); assertNotNull(pin)
        val saved = pin
        assertTrue(verifier.verify("example.test", 2222, first)); assertEquals(3, asked)
        decision = TrustDecision.CANCEL
        assertFalse(verifier.verify("example.test", 2222, second)); assertEquals(saved, pin)
        decision = TrustDecision.ONCE
        assertTrue(verifier.verify("example.test", 2222, second)); assertEquals(saved, pin)
        decision = TrustDecision.SAVE
        assertTrue(verifier.verify("example.test", 2222, second)); assertNotEquals(saved, pin)
    }
}

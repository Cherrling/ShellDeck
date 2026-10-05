package cc.cherr.shelldeck

import cc.cherr.shelldeck.backup.*
import cc.cherr.shelldeck.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupCryptoTest {
    @Test fun archiveAuthenticatesPasswordHeaderAndEntirePayload() {
        val password = "备份密码 test 🔑".toCharArray()
        val original = "private material stays in memory".toByteArray()
        val first = BackupCrypto.encrypt(original, password)
        val second = BackupCrypto.encrypt(original, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(original, BackupCrypto.decrypt(first, password))
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(first, "wrong password".toCharArray()) }
        for (index in listOf(0, 8, 9, 25, 38, first.lastIndex)) {
            val corrupt = first.copyOf(); corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { BackupCrypto.decrypt(corrupt, password) }
        }
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(first.copyOf(first.size - 1), password) }
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(first + byteArrayOf(0), password) }
        assertThrows(Exception::class.java) { BackupCrypto.encrypt(original, charArrayOf()) }
        password.fill('\u0000'); original.fill(0)
    }
    @Test fun payloadPreservesReferencesAndRejectsTruncationCountsAndDanglingIds() {
        val key = IdentityRecord().apply { id = "key"; label = "Key"; algorithm = "ssh-ed25519"; fingerprint = "SHA256:fixture" }
        val host = HostRecord().apply {
            id = "host"; label = "中文主机"; hostname = "example.com"; port = 2222; username = "dev"; identityId = key.id
            startupCommand = "tmux new-session -A -s codex"; favorite = true; lastUsedAt = 123
        }
        val data = BackupData(listOf(BackupIdentity(key, byteArrayOf(1,2,3))), listOf(host), "{}")
        val bytes = BackupCodec.encode(data)
        BackupCodec.decode(bytes).use { decoded ->
            assertEquals(host.startupCommand, decoded.hosts.single().startupCommand)
            assertEquals(host.identityId, decoded.hosts.single().identityId)
            assertEquals(host.label, decoded.hosts.single().label)
            assertEquals(123L, decoded.hosts.single().lastUsedAt)
            assertTrue(decoded.hosts.single().favorite)
            assertArrayEquals(byteArrayOf(1,2,3), decoded.identities.single().privateKey)
        }
        for (length in listOf(0, 4, bytes.size / 2, bytes.size - 1)) {
            assertThrows(Exception::class.java) { BackupCodec.decode(bytes.copyOf(length)) }
        }
        assertThrows(Exception::class.java) { BackupCodec.decode(bytes + byteArrayOf(0)) }
        val malicious = bytes.copyOf(); for (i in 4..7) malicious[i] = 127
        assertThrows(Exception::class.java) { BackupCodec.decode(malicious) }
        host.identityId = "missing"
        assertThrows(Exception::class.java) { BackupCodec.encode(data) }
        host.identityId = key.id
        assertThrows(Exception::class.java) { BackupCodec.encode(BackupData(data.identities + data.identities, data.hosts, "{}")) }
        data.close(); assertArrayEquals(ByteArray(3), data.identities.single().privateKey)
        bytes.fill(0)
    }
    @Test fun legacyPayloadWithoutJumpFieldStillDecodes() {
        val bytes = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bytes)
        fun text(value: String) { val utf = value.toByteArray(); out.writeInt(utf.size); out.write(utf) }
        out.writeInt(1); text("{}"); out.writeInt(0); out.writeInt(1)
        text("old"); text("Old host"); text("example.com"); out.writeInt(22); text("dev")
        out.writeBoolean(false); text("tmux attach"); out.writeBoolean(true); out.writeLong(42)
        BackupCodec.decode(bytes.toByteArray()).use {
            assertNull(it.hosts.single().jumpHostId)
            assertEquals("tmux attach", it.hosts.single().startupCommand)
        }
    }
    @Test fun fileReadIsBoundedBeforeDecrypting() {
        assertThrows(Exception::class.java) { readBackupFile(ByteArrayInputStream(ByteArray(BackupCrypto.MAX_FILE + 1))) }
    }
}

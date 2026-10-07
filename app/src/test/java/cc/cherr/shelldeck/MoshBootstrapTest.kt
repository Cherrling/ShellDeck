package cc.cherr.shelldeck

import cc.cherr.shelldeck.mosh.*
import cc.cherr.shelldeck.data.HostRecord
import cc.cherr.shelldeck.backup.*
import cc.cherr.shelldeck.ssh.ConnectionState
import org.junit.Assert.*
import org.junit.Test

class MoshBootstrapTest {
    private val key = "A".repeat(22) // Protocol fixture, not a credential.
    @Test fun parsesOnlyOneCompleteConnectLineWithoutLeakingKey() {
        val connection = MoshBootstrap.parse("Welcome\r\nMOSH CONNECT 60001 $key\r\n")
        assertEquals(60001, connection.port); assertEquals(key, connection.key)
        assertFalse(connection.toString().contains(key))
        for (text in listOf("MOSH CONNECT 0 $key", "MOSH CONNECT 65536 $key", "prefix MOSH CONNECT 60000 $key",
            "MOSH CONNECT 60000 short", "MOSH CONNECT 60000 $key\nMOSH CONNECT 60001 $key")) {
            val failure = assertThrows(MoshFailure::class.java) { MoshBootstrap.parse(text) }
            assertEquals(ConnectionState.MOSH_START_FAILED, failure.state)
            assertFalse(failure.toString().contains(key))
        }
        assertEquals(ConnectionState.MOSH_MISSING,
            assertThrows(MoshFailure::class.java) { MoshBootstrap.parse("SHELLDECK_MOSH_MISSING\n") }.state)
    }
    @Test fun startupIsOneQuotedShellArgumentAndNoInstallIsExecuted() {
        val command = MoshBootstrap.command(60008, "printf '%s' \"hello\"; exec sh")
        assertTrue(command.contains(" -p 60008 -- /bin/sh -lc '"))
        assertTrue(command.contains("'\\''"))
        assertFalse(command.contains("apt install"))
        assertFalse(MoshBootstrap.command(0, "").contains(" -- "))
        assertThrows(IllegalArgumentException::class.java) { MoshBootstrap.command(-1, "") }
        assertThrows(IllegalArgumentException::class.java) { MoshBootstrap.command(0, "echo hi\nrm file") }
    }
    @Test fun invalidRoutesAreRejectedAndBackupPreservesProtocol() {
        val host = HostRecord().apply { id = "h"; label = "Host"; hostname = "example.com"; username = "dev"; protocol = "mosh"; moshPort = 60003 }
        validateMoshHost(host)
        BackupCodec.decode(BackupCodec.encode(BackupData(emptyList(), listOf(host), "{}"))).use {
            assertEquals("mosh", it.hosts.single().protocol); assertEquals(60003, it.hosts.single().moshPort)
        }
        host.proxyId = "proxy"; assertThrows(IllegalArgumentException::class.java) { validateMoshHost(host) }
        host.proxyId = null; host.jumpHostId = "jump"; assertThrows(IllegalArgumentException::class.java) { validateMoshHost(host) }
        host.jumpHostId = null; host.protocol = "other"; assertThrows(IllegalArgumentException::class.java) { validateMoshHost(host) }
    }
    @Test fun versionThreeBackupDefaultsToSsh() {
        val buffer = java.io.ByteArrayOutputStream(); val out = java.io.DataOutputStream(buffer)
        fun text(value: String) { val bytes = value.toByteArray(); out.writeInt(bytes.size); out.write(bytes) }
        out.writeInt(3); text("{}"); out.writeInt(0); out.writeInt(1)
        text("old"); text("Old"); text("example.com"); out.writeInt(22); text("dev")
        repeat(3) { out.writeBoolean(false) }; text(""); out.writeBoolean(false); out.writeLong(0); out.writeInt(0)
        BackupCodec.decode(buffer.toByteArray()).use {
            assertEquals("ssh", it.hosts.single().protocol); assertEquals(0, it.hosts.single().moshPort)
        }
    }
}

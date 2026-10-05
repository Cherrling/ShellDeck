package cc.cherr.shelldeck

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cherr.shelldeck.backup.BackupCrypto
import cc.cherr.shelldeck.ssh.SshKeys
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BackupTransferDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun explicitPlaintextAndEncryptedPrivateExportsAndBackupPreview() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        // Standalone controller tests output ownership without launching a system picker.
        val controller = cc.cherr.shelldeck.backup.BackupController(ui.activity.application,
            (ui.activity.application as ShellDeckApplication).runtime) {}
        val label = "Transfer fixture"
        val file = File(ui.activity.cacheDir, "transfer-fixture.pem")
        val backup = File(ui.activity.cacheDir, "transfer-fixture.sdbak")
        val bytes = SshKeys.generate(SshKeys.GenerationType.ED25519, "original-password".toCharArray())
        fun idle() = ui.waitUntil(30000) { !model.busy && !controller.busy }
        try {
            idle()
            ui.runOnUiThread { model.importIdentity(label, bytes.toString(Charsets.UTF_8), null, "original-password") {} }; idle()
            val id = model.identities.single { it.label == label }.id
            for (encrypted in listOf(true, false)) {
                ui.runOnUiThread { controller.privateKey(id) }; idle()
                assertTrue(controller.form!!.needsUnlock)
                // Prevent an external picker here; content URI flow is separately exercised end-to-end.
                ui.runOnUiThread { controller.prepare(if (encrypted) "export-password" else "", "original-password", encrypted) }
                ui.waitUntil(30000) { controller.export != null || controller.message != null }
                ui.runOnUiThread { controller.export?.launched = true }
                idle()
                assertNull(controller.message)
                val exported = controller.export!!.bytes.copyOf()
                ui.runOnUiThread { controller.save(if (encrypted) Uri.fromFile(file) else null) }; idle()
                if (encrypted) assertArrayEquals(exported, file.readBytes())
                try { SSHClient().use { ssh ->
                    var prompts = 0
                    val key = SshKeys.loadWithPassphraseRequest(ssh, exported) { prompts++; "export-password".toCharArray() }
                    assertEquals(if (encrypted) 1 else 0, prompts)
                    assertEquals(model.identities.single { it.id == id }.fingerprint, SshKeys.fingerprint(key.getPublic()))
                } } finally { exported.fill(0); file.delete() }
                ui.runOnUiThread { controller.clearMessage() }
            }
            // Empty password with protection enabled must never produce a plaintext export.
            ui.runOnUiThread { controller.privateKey(id) }; idle()
            ui.runOnUiThread { controller.prepare("", "original-password", true) }; idle()
            assertNull(controller.export); assertNotNull(controller.message)
            ui.runOnUiThread { controller.clearMessage(); controller.cancel(); controller.backup(); controller.prepare("backup-password") }
            ui.waitUntil(30000) { controller.export != null || controller.message != null }
            ui.runOnUiThread { controller.export?.launched = true }; idle()
            assertNull(controller.message)
            ui.runOnUiThread { controller.save(Uri.fromFile(backup)) }; idle()
            assertTrue(backup.isFile)
            assertThrows(Exception::class.java) { BackupCrypto.decrypt(backup.readBytes(), "wrong".toCharArray()) }
            val previewController = model.backup
            ui.runOnUiThread { controller.clearMessage(); previewController.restoreFile(Uri.fromFile(backup)); previewController.prepare("backup-password") }; idle()
            ui.onNodeWithText("恢复预览").assertIsDisplayed()
            assertEquals(id, model.backup.preview!!.identityEntries.single { it.id == id }.existingId)
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("恢复预览").assertIsDisplayed()
            ui.onNodeWithText("取消", substring = false).performClick()
            assertNull(model.backup.preview)
            assertTrue(model.identities.any { it.id == id })
        } finally {
            idle(); ui.runOnUiThread { controller.cancel(); controller.clearMessage(); model.backup.cancel(); model.backup.clearMessage() }
            for (identity in model.identities.filter { it.label == label }) {
                ui.runOnUiThread { model.deleteIdentity(identity.id) }; idle()
            }
            controller.close(); bytes.fill(0); file.delete(); backup.delete()
        }
    }
}

package cc.cherr.shelldeck

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cc.cherr.shelldeck.data.IdentityRecord
import cc.cherr.shelldeck.ssh.SshKeys
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class IdentityDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun model(): ShellDeckModel {
        lateinit var value: ShellDeckModel
        ui.runOnUiThread { value = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        ui.waitUntil(5000) { !value.busy }
        return value
    }
    @Test fun generatesKeyCopiesPublicKeyAndExportsAfterRecreation() {
        val model = model()
        val label = "Generated device fixture"
        val file = File(ui.activity.cacheDir, "generated-device.pub")
        try {
            ui.onNodeWithTag("page-SETTINGS").performClick()
            ui.onNodeWithText("SSH 身份与密钥").performClick()
            ui.onNodeWithContentDescription("添加 SSH Key").performClick()
            ui.onNodeWithText("生成新密钥").performClick()
            ui.onNodeWithText("身份名称").performTextInput(label)
            ui.onNodeWithText("生成", substring = false).performClick()
            ui.waitUntil(15000) { !model.busy && model.publicKeyDetails != null }
            val detail = requireNotNull(model.publicKeyDetails)
            assertTrue(detail.key.startsWith("ssh-ed25519 "))
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("复制公钥", substring = false).performClick()
            ui.runOnIdle {
                val clipboard = ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(detail.key, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            ui.runOnUiThread { model.exportPublicKey(detail.id, Uri.fromFile(file)) }
            ui.waitUntil(5000) { !model.busy }
            assertEquals(detail.key + "\n", file.readText())
            assertFalse(file.readText().contains("PRIVATE"))
            val runtime = (ui.activity.application as ShellDeckApplication).runtime
            val record = requireNotNull(runtime.dao.identity(detail.id))
            val bytes = runtime.vault.decrypt(record.id, record.encryptedKey)
            try { SSHClient().use { assertEquals(detail.key, SshKeys.publicKey(SshKeys.load(it, bytes, charArrayOf()).getPublic())) } }
            finally { bytes.fill(0) }
        } finally {
            ui.waitUntil(15000) { !model.busy }
            ui.runOnUiThread { model.dismissPublicKey() }
            for (identity in model.identities.filter { it.label == label }) {
                ui.runOnUiThread { model.deleteIdentity(identity.id) }; ui.waitUntil(5000) { !model.busy }
            }
            file.delete()
        }
    }
    @Test fun importedAndLegacyEncryptedKeysExportWithoutRepeatedUnlocks() {
        val model = model()
        val runtime = (ui.activity.application as ShellDeckApplication).runtime
        val prefix = "Public export fixture"
        val bytes = SshKeys.generate(SshKeys.GenerationType.RSA3072, "device-passphrase".toCharArray())
        val file = File(ui.activity.cacheDir, "imported-device.pub")
        val legacyId = UUID.randomUUID().toString()
        try {
            val public = SSHClient().use { SshKeys.load(it, bytes, "device-passphrase".toCharArray()).getPublic() }
            val expected = SshKeys.publicKey(public)
            ui.runOnUiThread { model.importIdentity(prefix, bytes.toString(Charsets.UTF_8), null, "device-passphrase") {} }
            ui.waitUntil(15000) { !model.busy }
            assertNull(model.error)
            val imported = model.identities.single { it.label == prefix }
            ui.runOnUiThread { model.showPublicKey(imported.id) }; ui.waitUntil(5000) { !model.busy }
            assertNull(model.publicKeyUnlock); assertEquals(expected, model.publicKeyDetails?.key)
            ui.runOnUiThread { model.dismissPublicKey() }
            runtime.dao.insertIdentity(IdentityRecord().apply {
                id = legacyId; label = "$prefix legacy"; algorithm = SshKeys.algorithm(public); fingerprint = SshKeys.fingerprint(public)
                encryptedKey = runtime.vault.encrypt(id, bytes)
            })
            ui.runOnUiThread { model.showPublicKey(legacyId) }; ui.waitUntil(15000) { !model.busy }
            assertEquals(legacyId, model.publicKeyUnlock); assertNull(model.publicKeyDetails)
            ui.onNodeWithText("Passphrase").performTextInput("wrong")
            ui.onNodeWithText("解锁", substring = false).performClick(); ui.waitUntil(15000) { !model.busy }
            assertNotNull(model.error); assertNull(runtime.dao.identity(legacyId)!!.publicKey)
            ui.onNodeWithText("确定", substring = false).performClick()
            ui.onNodeWithText("Passphrase").performTextInput("device-passphrase")
            ui.onNodeWithText("解锁", substring = false).performClick(); ui.waitUntil(15000) { !model.busy }
            assertNull(model.error); assertEquals(expected, model.publicKeyDetails?.key)
            ui.runOnUiThread { model.dismissPublicKey(); model.showPublicKey(legacyId) }; ui.waitUntil(5000) { !model.busy }
            assertNull(model.publicKeyUnlock); assertEquals(expected, model.publicKeyDetails?.key)
            ui.runOnUiThread { model.exportPublicKey(legacyId, Uri.fromFile(file)) }; ui.waitUntil(5000) { !model.busy }
            assertEquals(expected + "\n", file.readText())
        } finally {
            bytes.fill(0); file.delete()
            ui.waitUntil(15000) { !model.busy }
            ui.runOnUiThread { model.dismissPublicKey(); model.clearError() }
            runtime.dao.deleteIdentity(legacyId)
            for (identity in model.identities.filter { it.label == prefix }) {
                ui.runOnUiThread { model.deleteIdentity(identity.id) }; ui.waitUntil(5000) { !model.busy }
            }
        }
    }
}

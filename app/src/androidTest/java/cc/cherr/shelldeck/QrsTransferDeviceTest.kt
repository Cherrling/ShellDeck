package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import cc.cherr.shelldeck.backup.*
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.qrs.*
import cc.cherr.shelldeck.settings.SettingsStore
import cc.cherr.shelldeck.ssh.SshKeys
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class QrsTransferDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun qrImagesRestoreEncryptedAndPlainBackupsOnlyAfterConfirmation() {
        val context = ui.activity.applicationContext
        val suffix = UUID.randomUUID().toString()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, ShellDeckDatabase::class.java).build()
        val sourceVault = CredentialVault("qrs-source-$suffix")
        val sourceSettings = SettingsStore(context, "qrs-source-$suffix")
        val source = BackupRepository(sourceDb, sourceVault, sourceSettings) { setOf("maple", "system") }
        val key = SshKeys.generate(SshKeys.GenerationType.ED25519, charArrayOf())
        val password = "backup-password".toCharArray()
        try {
            sourceDb.records().insertIdentity(IdentityRecord().apply {
                id = "qr-key"; label = "QR fixture"; algorithm = "ssh-ed25519"; fingerprint = "SHA256:fixture"
                encryptedKey = sourceVault.encrypt(id, key)
            })
            sourceDb.records().saveHost(HostRecord().apply {
                id = "qr-host"; label = "QR host"; hostname = "example.invalid"; username = "test"; identityId = "qr-key"
            })
            for (encrypted in listOf(true, false)) {
                val targetDb = Room.inMemoryDatabaseBuilder(context, ShellDeckDatabase::class.java).build()
                val targetVault = CredentialVault("qrs-target-$suffix")
                val targetSettings = SettingsStore(context, "qrs-target-$suffix")
                val target = BackupRepository(targetDb, targetVault, targetSettings) { setOf("maple", "system") }
                val file = if (encrypted) source.export(password) else source.exportPlainForQr()
                try {
                    val received = QrsExport(file, encrypted).use { send ->
                        send.configure(60, if (encrypted) 1500 else 100)
                        QrsReceiver().use { receive ->
                        var result: ByteArray? = null
                        val reader = QRCodeReader()
                        var attempted = 0; var missed = 0
                        for (i in 0 until 500) {
                            val frame = send.next()
                            if (i % 3 == 1) continue // Dropped camera frames.
                            attempted++
                            val bitmap = qrBitmap(frame)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            try {
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val text = reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
                                result = receive.accept(text)
                                if (result != null) break
                                result = receive.accept(text) // Duplicate camera frames.
                                if (result != null) break
                            } catch (_: com.google.zxing.ReaderException) {
                                // Detector heuristics may miss a dense frame even without camera blur.
                                // Exercise the same loss-tolerant path as CameraX instead of a PURE_BARCODE shortcut.
                                missed++
                            } finally { pixels.fill(0); bitmap.recycle(); reader.reset() }
                        }
                        assertTrue("Too many QR detection misses: $missed / $attempted", missed <= attempted / 4 + 1)
                        requireNotNull(result) { "QR frames never completed" }
                    } }
                    try {
                        assertArrayEquals(file, received)
                        if (encrypted) assertThrows(Exception::class.java) { target.prepare(received, "wrong".toCharArray()) }
                        else assertThrows(Exception::class.java) { target.prepare(received, password) } // File entry point stays encrypted-only.
                        val plan = if (encrypted) target.prepare(received, password) else target.preparePlainFromQr(received)
                        plan.use {
                            assertTrue(targetDb.records().identities().isEmpty())
                            assertTrue(targetDb.records().hosts().isEmpty())
                            assertEquals(1, plan.identityEntries.size)
                            target.restore(plan, emptyMap(), emptyMap(), false)
                        }
                        val restored = targetDb.records().identity("qr-key")!!
                        val unwrapped = targetVault.decrypt(restored.id, restored.encryptedKey)
                        try { assertArrayEquals(key, unwrapped) } finally { unwrapped.fill(0) }
                        assertThrows(Exception::class.java) { sourceVault.decrypt(restored.id, restored.encryptedKey) }
                        assertEquals("qr-key", targetDb.records().host("qr-host")!!.identityId)
                    } finally { received.fill(0) }
                } finally { file.fill(0); targetDb.close(); context.deleteSharedPreferences("qrs-target-$suffix") }
            }
        } finally {
            key.fill(0); password.fill('\u0000'); sourceDb.close(); context.deleteSharedPreferences("qrs-source-$suffix")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("qrs-source-$suffix"); deleteEntry("qrs-target-$suffix") }
        }
    }

    @Test fun receivedEncryptedBackupRetriesPasswordAndRejectsStaleCameraDelivery() {
        val controller = BackupController(ui.activity.application, (ui.activity.application as ShellDeckApplication).runtime) {}
        fun idle() = ui.waitUntil(30000) { !controller.busy }
        try {
            ui.runOnUiThread { controller.backupQr(); controller.prepare("backup-password") }; idle()
            assertNull(controller.message)
            val file = QrsReceiver().use { receive ->
                var result: ByteArray? = null
                for (i in 0 until 1000) { result = receive.accept(controller.qrsExport!!.next()); if (result != null) break }
                requireNotNull(result)
            }
            ui.runOnUiThread { controller.cancel(); controller.scanQr(); controller.receivedQr(controller.qrsReceiver!!, file); controller.prepare("wrong") }; idle()
            assertNotNull(controller.message); assertNotNull(controller.form); assertNull(controller.preview)
            ui.runOnUiThread { controller.clearMessage(); controller.prepare("backup-password") }; idle()
            assertNull(controller.message); assertNotNull(controller.preview)
            assertArrayEquals(ByteArray(file.size), file) // Model releases incoming data after successful preview.
            ui.runOnUiThread { controller.cancel(); controller.scanQr() }
            val stale = controller.qrsReceiver!!
            ui.runOnUiThread { controller.scanQr() }
            val staleFile = byteArrayOf(1, 2, 3)
            ui.runOnUiThread { controller.receivedQr(stale, staleFile) }
            assertArrayEquals(ByteArray(3), staleFile)
            assertNotNull(controller.qrsReceiver); assertNull(controller.form)
        } finally { idle(); ui.runOnUiThread { controller.close() } }
    }

    @Test fun unencryptedSendingRequiresOptOutEveryTimeAndSurvivesRecreation() {
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        try {
            ui.runOnUiThread { model.backup.backupQr() }
            ui.onNode(isToggleable()).assertIsOn().performClick()
            ui.onNodeWithText("显示二维码").performClick()
            ui.waitUntil(30000) { !model.backup.busy && model.backup.qrsExport != null }
            ui.onNodeWithText("暂停").performClick()
            val sender = model.backup.qrsExport!!
            assertFalse(sender.encrypted)
            ui.onNodeWithTag("qrs-display").assertIsDisplayed()
            ui.onNodeWithText("参数：10 Hz · 500 字节").performClick()
            ui.onNodeWithTag("qrs-fps").performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
            ui.onNodeWithTag("qrs-slice").performSemanticsAction(SemanticsActions.SetProgress) { it(137f) }
            ui.onNodeWithText("帧率：37 Hz").assertIsDisplayed()
            ui.onNodeWithText("分块大小：137 字节").assertIsDisplayed()
            ui.onNodeWithText("应用").performClick()
            assertEquals(37, sender.fps); assertEquals(137, sender.sliceSize)
            ui.activityRule.scenario.recreate()
            assertSame(sender, model.backup.qrsExport)
            ui.onNodeWithText("参数：37 Hz · 137 字节").assertIsDisplayed()
            ui.onNodeWithContentDescription("关闭二维码传输").performClick()
            assertThrows(IllegalStateException::class.java) { sender.next() }
            ui.runOnUiThread { model.backup.backupQr() }
            ui.onNode(isToggleable()).assertIsOn()
            ui.onNodeWithText("显示二维码").assertIsNotEnabled()
            ui.onNodeWithText("取消", substring = false).performClick()
        } finally {
            ui.waitUntil(30000) { !model.backup.busy }
            ui.runOnUiThread { model.backup.cancel(); model.backup.clearMessage() }
        }
    }
}

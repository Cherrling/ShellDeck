package cc.cherr.shelldeck

import android.content.ContentValues
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SftpDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @SdkSuppress(minSdkVersion = 29)
    @Test fun sessionFileBrowserUploadsDownloadsAndSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"), args.getString("sshUser"), args.getString("sftpDirectory"))
        lateinit var model: ShellDeckModel
        ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java] }
        val resolver = ui.activity.contentResolver
        fun document(name: String) = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream") }))
        val upload = document("ShellDeck-test-${UUID.randomUUID()}.bin")
        val download = document("ShellDeck-result-${UUID.randomUUID()}.bin")
        val data = ByteArray(512 * 1024 + 29) { (it % 239).toByte() }
        resolver.openOutputStream(upload)!!.use { it.write(data) }
        var identityId: String? = null
        var hostId: String? = null
        var connection: SessionConnection? = null
        try {
            ui.runOnUiThread { model.importIdentity("SFTP fixture key", File(ui.activity.filesDir, "test-ssh-key").readText(), null, "") {} }
            ui.waitUntil(10000) { !model.busy && model.identities.any { it.label == "SFTP fixture key" } }
            identityId = model.identities.first { it.label == "SFTP fixture key" }.id
            ui.runOnUiThread { model.saveHost(null, "SFTP fixture host", "127.0.0.1", args.getString("sshPort")!!, args.getString("sshUser")!!, identityId, "") }
            ui.waitUntil(10000) { !model.busy && model.hosts.any { it.label == "SFTP fixture host" } }
            val host = model.hosts.first { it.label == "SFTP fixture host" }; hostId = host.id
            ui.runOnUiThread { model.connect(host, ""); connection = model.sessionManager.selected }
            ui.waitUntil(10000) { connection?.challenge != null }
            ui.onNodeWithText("仅信任本次").performClick()
            ui.waitUntil(10000) { connection?.connected == true }
            ui.runOnUiThread { model.sessionManager.home() }
            ui.onNodeWithTag("page-SESSIONS").performClick()
            ui.onNodeWithContentDescription("SFTP fixture host 的会话工具").performClick()
            ui.onNodeWithText("浏览文件").performClick()
            val files = connection!!.files
            fun idle() = ui.waitUntil(20000) { !files.busy }
            idle()
            ui.runOnUiThread { files.browse(args.getString("sftpDirectory")!!) }; idle()
            assertEquals(args.getString("sftpDirectory"), files.path)
            ui.runOnUiThread { files.prepareUpload(upload) }; idle()
            assertNotNull(files.upload)
            ui.onNodeWithText("远端文件名").performTextReplacement("中文文件.bin")
            ui.onNodeWithText("上传", substring = false).performClick(); idle()
            assertEquals("上传完成", files.message)
            ui.onNodeWithText("中文文件.bin").assertIsDisplayed()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("中文文件.bin").assertIsDisplayed()
            ui.onNodeWithText("中文文件.bin").performClick(); idle()
            ui.onNodeWithText("选择保存位置").performClick()
            ui.waitUntil(5000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5000) { files.download == null }
            ui.onNodeWithText("中文文件.bin").performClick(); idle()
            ui.runOnUiThread { files.startDownload(download) }; idle()
            assertEquals("下载完成", files.message)
            assertArrayEquals(data, resolver.openInputStream(download)!!.use { it.readBytes() })
            ui.runOnUiThread { files.prepareUpload(upload) }; idle()
            ui.runOnUiThread { files.startUpload("中文文件.bin") }; idle()
            assertTrue(files.message.orEmpty().contains("SFTP 操作失败"))
            ui.runOnUiThread { files.select(files.entries.single { it.name == "中文文件.bin" }) }; idle()
            ui.runOnUiThread { files.startDownload(download) }; idle()
            assertArrayEquals(data, resolver.openInputStream(download)!!.use { it.readBytes() })
            assertTrue(connection!!.terminal.session.isReady)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("page-SESSIONS").assertIsDisplayed()
            ui.runOnUiThread { model.sessionManager.select(connection!!.id) }
            assertTrue(connection!!.terminal.session.isReady)
        } finally {
            ui.runOnUiThread { connection?.let { model.sessionManager.close(it.id) } }
            ui.runOnUiThread { hostId?.let { model.deleteHost(it) } }; ui.waitUntil(5000) { !model.busy }
            ui.runOnUiThread { identityId?.let { model.deleteIdentity(it) } }; ui.waitUntil(5000) { !model.busy }
            resolver.delete(upload, null, null); resolver.delete(download, null, null)
        }
    }
}

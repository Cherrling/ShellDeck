package cc.cherr.shelldeck

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.ssh.TrustDecision
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PromptEditorDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun draftSurvivesRotationAndNavigationWithoutSendingOrCrossingSessions() {
        val args = InstrumentationRegistry.getArguments()
        assumeNotNull(args.getString("sshPort"))
        val app = ui.activity.application as ShellDeckApplication
        val runtime = app.runtime
        val id = UUID.randomUUID().toString()
        val key = File(app.filesDir, "test-ssh-key").readBytes()
        try { runtime.dao.insertIdentity(IdentityRecord().apply {
            this.id = id; label = "Prompt test"; encryptedKey = runtime.vault.encrypt(id, key)
        }) } finally { key.fill(0) }
        val host = HostRecord().apply {
            this.id = UUID.randomUUID().toString(); label = "Prompt test"; hostname = "127.0.0.1"
            port = args.getString("sshPort")!!.toInt(); username = args.getString("sshUser")!!; identityId = id
        }
        lateinit var model: ShellDeckModel
        lateinit var connection: SessionConnection
        try {
            ui.runOnUiThread { model = ViewModelProvider(ui.activity)[ShellDeckModel::class.java]; model.connect(host, ""); connection = runtime.sessions.selected!! }
            ui.waitUntil(15000) { connection.challenge != null }
            ui.runOnUiThread { connection.trust(TrustDecision.ONCE) }
            ui.waitUntil(15000) { connection.connected }
            ui.runOnUiThread { connection.terminal.promptVisible = true }
            ui.onNodeWithText("多行文本").performTextInput("中文 prompt\n第二行")
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("中文 prompt\n第二行").assertExists()
            ui.runOnIdle {
                connection.terminal.promptVisible = false
                runtime.sessions.home()
                assertFalse(connection.terminal.session.emulator.screen.transcriptText.contains("第二行"))
                runtime.sessions.select(connection.id)
                connection.terminal.promptVisible = true
            }
            ui.onNodeWithText("中文 prompt\n第二行").assertExists()
            ui.onNodeWithText("发送文本，不追加回车").assertIsEnabled()
            // Do not send a multiline command to the fixture shell: byte framing is covered separately.
            ui.runOnIdle { runtime.sessions.close(connection.id); assertEquals("", connection.terminal.promptDraft) }
        } finally {
            ui.runOnUiThread { runtime.sessions.closeAll() }
            runtime.dao.deleteIdentity(id)
        }
    }
}

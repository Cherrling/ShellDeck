package cc.cherr.shelldeck.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cc.cherr.shelldeck.TerminalController

@Composable
fun PromptEditor(terminal: TerminalController) {
    if (!terminal.promptVisible) return
    Dialog(onDismissRequest = { terminal.promptVisible = false }, properties = DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prompt 编辑器", style = MaterialTheme.typography.titleLarge)
                Text("返回保留当前会话草稿；关闭会话或进程退出后清除。发送不追加回车，但远端未启用安全粘贴时，文本中的换行仍可能执行命令。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(terminal.promptDraft,
                    { if (it.length <= PasteRequest.MAX_CHARS) terminal.promptDraft = it },
                    modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("多行文本") })
                Text("${terminal.promptDraft.length} / ${PasteRequest.MAX_CHARS}", style = MaterialTheme.typography.labelSmall)
                Button(enabled = terminal.session.isReady && terminal.promptDraft.isNotBlank(),
                    onClick = { terminal.sendPrompt() }, modifier = Modifier.fillMaxWidth()) { Text("发送文本，不追加回车") }
            }
        }
    }
}

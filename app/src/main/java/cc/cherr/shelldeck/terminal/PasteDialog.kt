package cc.cherr.shelldeck.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cc.cherr.shelldeck.TerminalController

@Composable
fun PasteDialog(terminal: TerminalController) {
    terminal.pendingPaste?.let { request ->
        AlertDialog(onDismissRequest = terminal::dismissPaste,
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("确认粘贴") }, text = { Column {
                Text("将向当前会话粘贴 ${request.text.length} 个字符。换行可能执行命令，请检查内容。")
                Text(request.preview + if (request.text.length > 1200) "\n…（预览已截断）" else "",
                    Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp))
            } }, confirmButton = { TextButton(onClick = terminal::confirmPaste) { Text("粘贴") } },
            dismissButton = { TextButton(onClick = terminal::dismissPaste) { Text("取消") } })
    }
    terminal.pasteError?.let { message -> AlertDialog(onDismissRequest = terminal::dismissPaste,
        title = { Text("无法粘贴") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = terminal::dismissPaste) { Text("确定") } }) }
}

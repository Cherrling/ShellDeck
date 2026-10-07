package cc.cherr.shelldeck.terminal

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cc.cherr.shelldeck.TerminalController

@Composable
fun LinkDialog(controller: TerminalController) {
    val target = controller.pendingLink ?: return
    val context = LocalContext.current
    var error by remember(target) { mutableStateOf<String?>(null) }
    val canOpen = TerminalLinks.canOpen(target)
    AlertDialog(onDismissRequest = controller::dismissLink, title = { Text("链接") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(target) }
            if (!canOpen) Text("此类型链接仅支持复制。远端文件路径不会作为手机本地文件打开。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Column {
            if (canOpen) TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    controller.dismissLink()
                } catch (_: ActivityNotFoundException) { error = "没有可以打开此链接的应用" }
                catch (_: SecurityException) { error = "系统不允许打开此链接" }
            }) { Text("打开链接") }
            TextButton(onClick = { controller.onCopyTextToClipboard(controller.session, target); controller.dismissLink() }) { Text("复制链接") }
            TextButton(onClick = controller::selectLinkText) { Text("选择文本") }
        }
    }, dismissButton = { TextButton(onClick = controller::dismissLink) { Text("取消") } })
}

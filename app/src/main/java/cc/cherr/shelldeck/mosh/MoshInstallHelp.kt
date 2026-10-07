package cc.cherr.shelldeck.mosh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun MoshInstallHelp(connectSsh: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    val context = LocalContext.current
    TextButton(onClick = { expanded = true }) { Text("查看 Mosh 安装方法") }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("远端未安装 Mosh") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(MoshBootstrap.INSTALL_HELP)
            listOf("Debian / Ubuntu" to "sudo apt install mosh", "Fedora" to "sudo dnf install mosh", "Alpine" to "sudo apk add mosh").forEach { (name, command) ->
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mosh 安装命令", command))
                }) { Text("复制 $name 命令") }
            }
        } }, confirmButton = { TextButton(onClick = { expanded = false; connectSsh() }) { Text("使用 SSH 连接") } },
        dismissButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}

package cc.cherr.shelldeck.sftp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.cherr.shelldeck.R
import cc.cherr.shelldeck.SessionConnection

@Composable
fun SftpScreen(connection: SessionConnection, onDismiss: () -> Unit) {
    val files = connection.files
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && connection.connected) files.prepareUpload(uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && connection.connected) files.startDownload(uri) else files.dismissDownload()
    }
    LaunchedEffect(files) { if (files.path.isBlank() && !files.busy && connection.connected) files.browse() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Text("${connection.host.label} · 文件", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
                if (!connection.connected) Text("连接已结束，请新建连接。", color = MaterialTheme.colorScheme.error)
                var directory by remember(files.path) { mutableStateOf(files.path) }
                OutlinedTextField(directory, { directory = it }, singleLine = true, label = { Text("远端目录") },
                    modifier = Modifier.fillMaxWidth(), trailingIcon = {
                        TextButton(enabled = !files.busy && connection.connected, onClick = { files.browse(directory.ifBlank { "." }) }) { Text("前往") }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !files.busy && files.path.isNotBlank() && connection.connected,
                        onClick = { files.browse(files.path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }) }) { Text("上级目录") }
                    IconButton(enabled = !files.busy && connection.connected, onClick = { files.browse() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新目录")
                    }
                    TextButton(enabled = !files.busy && files.path.isNotBlank() && connection.connected,
                        onClick = { picker.launch(arrayOf("*/*")) }) { Text("上传文件") }
                }
                if (files.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${files.operation} · ${files.transferred / 1024} KiB", Modifier.padding(vertical = 12.dp))
                        TextButton(enabled = !files.cancelling, onClick = files::cancel) { Text(if (files.cancelling) "正在取消" else "取消") }
                    }
                }
                files.message?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                Text("点击目录浏览，点击文件下载。手势返回会话列表，传输会继续。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).testTag("sftp-files")) {
                    if (files.entries.isEmpty() && !files.busy) item { Text("目录为空或尚未读取", Modifier.padding(vertical = 24.dp)) }
                    items(files.entries, key = { it.path }) { entry ->
                        ListItem(headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (entry.directory) "目录" else if (entry.link) "符号链接" else "${entry.size} 字节") },
                            leadingContent = { Icon(painterResource(if (entry.directory) R.drawable.ic_folder else R.drawable.ic_file), null) },
                            modifier = Modifier.clickable(enabled = !files.busy && connection.connected) { files.select(entry) })
                    }
                }
            }
        }
    }
    files.upload?.let { request ->
        var name by remember(request) { mutableStateOf(request.name) }
        AlertDialog(onDismissRequest = files::dismissUpload, title = { Text("上传文件") }, text = { Column {
            Text("目标：${request.directory}。已有同名文件不会被覆盖。")
            OutlinedTextField(name, { name = it }, label = { Text("远端文件名") }, singleLine = true)
            files.message?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = !files.busy && connection.connected, onClick = { files.startUpload(name) }) { Text("上传") } },
            dismissButton = { TextButton(onClick = files::dismissUpload) { Text("取消") } })
    }
    files.download?.let { entry ->
        AlertDialog(onDismissRequest = files::dismissDownload, title = { Text("下载文件") },
            text = { Text("${entry.name}\n${entry.size} 字节") },
            confirmButton = { TextButton(enabled = !files.busy && connection.connected, onClick = { saver.launch(entry.name) }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = files::dismissDownload) { Text("取消") } })
    }
}

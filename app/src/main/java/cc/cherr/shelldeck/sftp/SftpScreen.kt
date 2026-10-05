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
    var editing by remember { mutableStateOf<SftpFiles.Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SftpFiles.Entry?>(null) }
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
                        TextButton(enabled = connection.connected, onClick = { files.browse(directory.ifBlank { "." }) }) { Text("前往") }
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = files.path.isNotBlank() && connection.connected,
                        onClick = files::parent) { Text("上级目录") }
                    IconButton(enabled = connection.connected, onClick = { files.browse() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新目录")
                    }
                    TextButton(enabled = !files.busy && files.path.isNotBlank() && connection.connected,
                        onClick = { picker.launch(arrayOf("*/*")) }) { Text("上传文件") }
                }
                TextButton(enabled = !files.busy && connection.connected && files.path.isNotBlank(),
                    onClick = { creating = true }) { Text("新建目录") }
                if (files.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (files.cached) "显示缓存，正在刷新…" else "正在读取目录…")
                        TextButton(onClick = files::cancelBrowse) { Text("取消读取") }
                    }
                }
                files.browseMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (files.transferring) {
                    val total = files.totalBytes?.takeIf { it > 0 && files.operation in listOf("上传", "下载") }
                    if (total == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { (files.transferred.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
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
                        var menu by remember { mutableStateOf(false) }
                        ListItem(headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (entry.directory) "目录" else if (entry.link) "符号链接" else "${entry.size} 字节") },
                            trailingContent = { Box {
                                IconButton(enabled = !files.busy && connection.connected, onClick = { menu = true }) {
                                    Icon(painterResource(R.drawable.ic_more_vert), "${entry.name} 的文件操作")
                                }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; editing = entry })
                                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = entry })
                                }
                            } },
                            leadingContent = { Icon(painterResource(if (entry.directory) R.drawable.ic_folder else R.drawable.ic_file), null) },
                            modifier = Modifier.clickable(enabled = connection.connected && (entry.directory || entry.link || !files.busy)) { files.select(entry) })
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        var name by remember(creating, editing) { mutableStateOf(editing?.name ?: "") }
        AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (creating) "新建目录" else "重命名") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = { TextButton(enabled = connection.connected && !files.busy &&
                runCatching { SftpFiles.child(files.path, name) }.isSuccess, onClick = {
                if (creating) files.mkdir(name) else editing?.let { files.rename(it, name) }
                creating = false; editing = null
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("取消") } })
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除远端文件？") },
            text = { Text("${entry.path}\n无法撤销。目录仅在为空时删除；符号链接只删除链接本身。") },
            confirmButton = { TextButton(enabled = connection.connected && !files.busy, onClick = { files.delete(entry); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    files.upload?.let { request ->
        var overwrite by remember(request) { mutableStateOf(false) }
        var confirmOverwrite by remember(request) { mutableStateOf(false) }
        var name by remember(request) { mutableStateOf(request.name) }
        AlertDialog(onDismissRequest = files::dismissUpload, title = { Text("上传文件") }, text = { Column {
            Text("目标：${request.directory}。默认不覆盖；覆盖需服务器支持原子替换。")
            Row { Checkbox(overwrite, { overwrite = it }); Text("允许覆盖同名文件") }
            OutlinedTextField(name, { name = it }, label = { Text("远端文件名") }, singleLine = true)
            files.message?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = !files.transferring && connection.connected, onClick = { if (overwrite) confirmOverwrite = true else files.startUpload(name) }) { Text("上传") } },
            dismissButton = { TextButton(onClick = files::dismissUpload) { Text("取消") } })
            if (confirmOverwrite) AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("确认覆盖远端文件？") },
            text = { Text("${request.directory}/$name\n若目标已存在，将在上传完成后替换，旧内容无法恢复。") },
            confirmButton = { TextButton(onClick = { files.startUpload(name, true); confirmOverwrite = false }) { Text("覆盖上传") } },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("取消") } })
    }
    files.download?.let { entry ->
        AlertDialog(onDismissRequest = files::dismissDownload, title = { Text("下载文件") },
            text = { Text("${entry.name}\n${entry.size} 字节") },
            confirmButton = { TextButton(enabled = !files.transferring && connection.connected, onClick = { saver.launch(entry.name) }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = files::dismissDownload) { Text("取消") } })
    }
}

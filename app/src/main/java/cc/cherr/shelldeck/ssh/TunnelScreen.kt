package cc.cherr.shelldeck.ssh

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.cherr.shelldeck.SessionConnection

@Composable
fun TunnelScreen(connection: SessionConnection, dismiss: () -> Unit) {
    val controller = connection.tunnels
    var local by rememberSaveable { mutableStateOf("8080") }
    var host by rememberSaveable { mutableStateOf("127.0.0.1") }
    var remote by rememberSaveable { mutableStateOf("3000") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${connection.host.label} · 端口转发") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅监听手机 127.0.0.1；目标地址从最终服务器访问。关闭会话会停止全部转发。")
            OutlinedTextField(local, { local = it }, label = { Text("手机本地端口") }, singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("目标地址") }, singleLine = true)
            OutlinedTextField(remote, { remote = it }, label = { Text("目标端口") }, singleLine = true)
            Button(enabled = connection.connected && !controller.busy,
                onClick = { controller.start(local, host, remote) }) { Text("开启转发") }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            controller.tunnels.forEach { tunnel ->
                Row(Modifier.fillMaxWidth()) {
                    Text("127.0.0.1:${tunnel.port} → ${tunnel.remoteHost}:${tunnel.remotePort}", Modifier.weight(1f))
                    TextButton(onClick = { controller.stop(tunnel) }) { Text("停止") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}

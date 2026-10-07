package cc.cherr.shelldeck.proxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.cherr.shelldeck.ShellDeckModel
import cc.cherr.shelldeck.R
import cc.cherr.shelldeck.data.ProxyRecord
import cc.cherr.shelldeck.ui.ManagementHeading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProxyScreen(model: ShellDeckModel) {
    val checks = remember(model) { model.proxyChecks() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(checks, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) checks.cancelAll() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); checks.close() }
    }
    var previous by remember { mutableStateOf(model.proxies.associateBy { it.id }) }
    LaunchedEffect(model.proxies) {
        val current = model.proxies.associateBy { it.id }
        previous.forEach { (id, old) -> val new = current[id]
            if (new == null || old.hostname != new.hostname || old.port != new.port || old.remoteDns != new.remoteDns ||
                old.authenticated != new.authenticated || !old.encryptedCredentials.contentEquals(new.encryptedCredentials)) checks.invalidate(id)
        }
        previous = current
    }
    var help by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProxyRecord?>(null) }
    var editor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProxyRecord?>(null) }
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { editing = null; editor = true }, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(painterResource(R.drawable.ic_add), "添加代理")
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ManagementHeading("SOCKS 代理", "检测连通性、延迟与出口 IP") }
                IconButton(onClick = { help = true }, modifier = Modifier.padding(end = 16.dp)) { Icon(painterResource(R.drawable.ic_info), "检测说明") }
            } }
            item { Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前网络出口", style = MaterialTheme.typography.titleMedium)
                    ProbeSummary(checks.results[ProxyChecks.DIRECT], checks.pending[ProxyChecks.DIRECT] == true)
                    Text("使用当前系统网络，包括已启用的 VPN。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { checks.check(null) }) { Text("刷新出口") }
                        TextButton(onClick = { model.proxies.forEach(checks::check) }, enabled = model.proxies.isNotEmpty() && checks.pending.isEmpty()) { Text("检测全部代理") }
                        if (checks.pending.isNotEmpty()) TextButton(onClick = checks::cancelAll) { Text("停止") }
                    }
                }
            } }
            if (model.proxies.isEmpty()) item { Text("点击右下角 ＋ 添加代理，然后在服务器编辑页选择它。", Modifier.padding(24.dp)) }
            items(model.proxies, key = { it.id }) { proxy ->
                Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(proxy.label, style = MaterialTheme.typography.titleMedium)
                                Text("${proxy.hostname}:${proxy.port}", style = MaterialTheme.typography.bodyMedium)
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), "管理代理 ${proxy.label}") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; editing = proxy; editor = true })
                                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = proxy })
                                }
                            }
                        }
                        ProbeSummary(checks.results[proxy.id], checks.pending[proxy.id] == true)
                        TextButton(onClick = { checks.check(proxy) }, enabled = checks.pending[proxy.id] != true) { Text("检测代理") }
                    }
                }
            }

        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("检测说明") }, text = {
        Text("使用 Cloudflare 检测连通性和出口 IP。检测延迟包含建立连接及收到 HTTPS 响应的耗时，不是 SSH 延迟。\n\n出口 IP 是访问 Cloudflare 时的出口，分流后的 SSH 路径可能不同。仅手动检测，退出页面或进入后台会取消。")
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    if (editor) ProxyEditor(model, editing) { editor = false }
    deleting?.let { proxy -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除代理？") }, text = { Text(proxy.label) },
        confirmButton = { TextButton(enabled = !model.busy, onClick = { model.deleteProxy(proxy.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ProbeSummary(result: ProbeResult?, pending: Boolean) {
    if (pending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("等待或正在检测…"); return }
    if (result == null) { Text("未检测", color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Text(if (result.stale) "网络已变化，请重新检测" else result.message + (result.latencyMs?.let { " · $it ms" } ?: ""))
    result.ip?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text("出口 IP：$it") } }
    result.ipError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("检测于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss 'UTC+8'").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(result.checkedAt)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProxyEditor(model: ShellDeckModel, record: ProxyRecord?, dismiss: () -> Unit) {
    var label by remember { mutableStateOf(record?.label ?: "") }
    var host by remember { mutableStateOf(record?.hostname ?: "") }
    var port by remember { mutableStateOf(record?.port?.toString() ?: "1080") }
    var remote by remember { mutableStateOf(record?.remoteDns ?: true) }
    var auth by remember { mutableStateOf(record?.authenticated ?: false) }
    var replace by remember { mutableStateOf(record?.authenticated != true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = { if (!model.busy) dismiss() },
        title = { Text(if (record == null) "添加 SOCKS5 代理" else "编辑 SOCKS5 代理") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("名称") }, singleLine = true, enabled = !model.busy)
                OutlinedTextField(host, { host = it }, label = { Text("代理地址") }, singleLine = true, enabled = !model.busy)
                OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true, enabled = !model.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(remote, { remote = it }, enabled = !model.busy); Text("由代理解析目标域名") }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(auth, { auth = it }, enabled = !model.busy); Text("用户名密码认证") }
                if (auth) {
                    if (!replace) TextButton(onClick = { replace = true }, enabled = !model.busy) { Text("已保存凭据 · 点击更换") }
                    else {
                        OutlinedTextField(username, { username = it }, label = { Text("代理用户名") }, singleLine = true, enabled = !model.busy)
                        OutlinedTextField(password, { password = it }, label = { Text("代理密码") }, singleLine = true, enabled = !model.busy,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                    Text("本机加密保存；SOCKS5 认证本身不加密网络上传输的密码。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(enabled = !model.busy, onClick = {
            model.saveProxy(record?.id, label, host, port, remote, auth, username, password, replace) { username = ""; password = ""; dismiss() }
        }) { Text("保存") } }, dismissButton = { TextButton(enabled = !model.busy, onClick = dismiss) { Text("取消") } })
}

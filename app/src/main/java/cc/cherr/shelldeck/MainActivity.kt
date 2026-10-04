package cc.cherr.shelldeck

import android.os.Bundle
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.cherr.shelldeck.data.HostRecord
import cc.cherr.shelldeck.data.IdentityRecord
import cc.cherr.shelldeck.ssh.TrustDecision
import cc.cherr.shelldeck.ui.ShellDeckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShellDeckTheme { ShellDeckApp() } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun terminalKeyboardInsets(terminalActive: Boolean): WindowInsets =
    if (terminalActive) WindowInsets.imeAnimationTarget else WindowInsets.ime

@Composable
private fun ShellDeckApp(model: ShellDeckModel = viewModel()) {
    var hostEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HostRecord?>(null) }
    var importing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<HostRecord?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var deletingHost by remember { mutableStateOf<HostRecord?>(null) }
    var deletingIdentity by remember { mutableStateOf<IdentityRecord?>(null) }
    val terminal = model.terminal
    BackHandler(terminal != null) { disconnect = true }
    Scaffold { insets ->
        // A terminal resize reaches the remote TUI. Use the IME destination, not each animation frame.
        val keyboardInsets = terminalKeyboardInsets(terminal != null)
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).windowInsetsPadding(keyboardInsets)) {
            if (terminal != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(model.activeHost?.label ?: "ShellDeck", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(model.connectionStatus, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                    TextButton(onClick = { disconnect = true }) { Text("断开") }
                }
                key(terminal) {
                    AndroidView(factory = terminal::createView, modifier = Modifier.weight(1f).fillMaxWidth(),
                        onRelease = terminal::releaseView, update = {})
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = terminal::showKeyboard) { Text("键盘") }
                    TextButton(onClick = { terminal.special(KeyEvent.KEYCODE_ESCAPE) }) { Text("ESC") }
                    TextButton(onClick = { terminal.ctrl = !terminal.ctrl }) { Text(if (terminal.ctrl) "CTRL ●" else "CTRL") }
                    TextButton(onClick = { terminal.alt = !terminal.alt }) { Text(if (terminal.alt) "ALT ●" else "ALT") }
                    TextButton(onClick = { terminal.special(KeyEvent.KEYCODE_TAB) }) { Text("TAB") }
                    listOf("←" to KeyEvent.KEYCODE_DPAD_LEFT, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                        "↑" to KeyEvent.KEYCODE_DPAD_UP, "→" to KeyEvent.KEYCODE_DPAD_RIGHT).forEach { (label, code) ->
                        TextButton(onClick = { terminal.special(code) }) { Text(label) }
                    }
                }
            } else {
                Text("ShellDeck", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
                Row {
                    TextButton(enabled = !model.busy, onClick = { editing = null; hostEditor = true }) { Text("添加服务器") }
                    TextButton(enabled = !model.busy, onClick = { importing = true }) { Text("导入 SSH Key") }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.hosts.isEmpty()) item {
                        Text("先导入 SSH 私钥，再添加服务器。私钥会加密保存，口令仅用于本次操作。")
                    }
                    items(model.hosts, key = { it.id }) { host ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(host.label, style = MaterialTheme.typography.titleMedium)
                                Text("${host.username}@${host.hostname}:${host.port}")
                                Text(model.identities.firstOrNull { it.id == host.identityId }?.label ?: "密码登录", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(enabled = !model.busy, onClick = {
                                        if (host.identityId == null) login = host else model.connect(host, "")
                                    }) { Text("连接") }
                                    TextButton(enabled = !model.busy, onClick = { editing = host; hostEditor = true }) { Text("编辑") }
                                    TextButton(enabled = !model.busy, onClick = { deletingHost = host }) { Text("删除") }
                                }
                            }
                        }
                    }
                    item { Text("Identities", style = MaterialTheme.typography.titleLarge) }
                    items(model.identities, key = { it.id }) { identity ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(identity.label, style = MaterialTheme.typography.titleMedium)
                                Text(identity.algorithm, style = MaterialTheme.typography.bodySmall)
                                Text(identity.fingerprint, style = MaterialTheme.typography.labelSmall)
                                TextButton(enabled = !model.busy, onClick = { deletingIdentity = identity }) { Text("删除身份") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (hostEditor) HostEditor(editing, model.identities, onDismiss = { hostEditor = false }) { label, hostname, port, username, identity ->
        if (model.saveHost(editing?.id, label, hostname, port, username, identity)) hostEditor = false
    }
    if (importing) ImportDialog(model, onDismiss = { importing = false })
    login?.let { host ->
        var secret by remember(host.id) { mutableStateOf("") }
        AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = { secret = ""; login = null }, title = { Text("连接 ${host.label}") },
            text = { Column {
                Text("输入服务器登录密码，不会保存。")
                OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("密码") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } }, confirmButton = { TextButton(onClick = { model.connect(host, secret); secret = ""; login = null }) { Text("连接") } },
            dismissButton = { TextButton(onClick = { secret = ""; login = null }) { Text("取消") } })
    }
    model.passphraseIdentity?.let { identity ->
        var secret by remember(identity) { mutableStateOf("") }
        AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { secret = ""; model.disconnect() }, title = { Text("解锁私钥") },
            text = { Column {
                Text("$identity 使用了加密私钥。请输入口令，仅用于本次连接，不会保存。")
                OutlinedTextField(secret, { secret = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } },
            confirmButton = { TextButton(onClick = { model.submitPassphrase(secret); secret = "" }) { Text("连接") } },
            dismissButton = { TextButton(onClick = { secret = ""; model.disconnect() }) { Text("取消") } })
    }
    model.challenge?.let { challenge ->
        AlertDialog(onDismissRequest = { model.trust(TrustDecision.CANCEL) },
            title = { Text(if (challenge.previous == null) "确认服务器指纹" else "警告：服务器指纹已改变") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${challenge.hostname}:${challenge.port}")
                if (challenge.previous != null) {
                    Text("可能是服务器重装、密钥轮换或连接被冒充。请通过可信渠道核对。", color = MaterialTheme.colorScheme.error)
                    Text("已保存：${challenge.previous.algorithm}\n${challenge.previous.fingerprint}")
                }
                Text("本次：${challenge.presented.algorithm}\n${challenge.presented.fingerprint}")
                Text("请与服务器管理员提供的 SHA256 指纹核对。")
            } },
            confirmButton = { Column {
                TextButton(onClick = { model.trust(TrustDecision.SAVE) }) { Text(if (challenge.previous == null) "信任并保存" else "确认更换并保存") }
                TextButton(onClick = { model.trust(TrustDecision.ONCE) }) { Text("仅信任本次") }
            } }, dismissButton = { TextButton(onClick = { model.trust(TrustDecision.CANCEL) }) { Text("取消") } })
    }
    if (disconnect) ConfirmDialog("断开连接？", "这将关闭当前 SSH 会话。远端 tmux 会话可以在下次连接时重新附加。", { disconnect = false }) {
        model.disconnect(); disconnect = false
    }
    deletingHost?.let { host -> ConfirmDialog("删除服务器？", host.label, { deletingHost = null }) { model.deleteHost(host.id); deletingHost = null } }
    deletingIdentity?.let { identity -> ConfirmDialog("删除身份？", "${identity.label}：删除后需要重新导入私钥。", { deletingIdentity = null }) { model.deleteIdentity(identity.id); deletingIdentity = null } }
    model.error?.let { message -> AlertDialog(onDismissRequest = model::clearError, title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::clearError) { Text("确定") } }) }
}

@Composable
private fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun HostEditor(host: HostRecord?, identities: List<IdentityRecord>, onDismiss: () -> Unit,
    save: (String, String, String, String, String?) -> Unit) {
    var label by remember { mutableStateOf(host?.label ?: "") }
    var hostname by remember { mutableStateOf(host?.hostname ?: "") }
    var port by remember { mutableStateOf(host?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(host?.username ?: "root") }
    var identity by remember { mutableStateOf(if (host != null) host.identityId else identities.firstOrNull()?.id) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (host == null) "添加服务器" else "编辑服务器") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(hostname, { hostname = it }, label = { Text("主机名 / IP") }, singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true)
            Text("认证身份")
            identities.forEach { option ->
                Row { RadioButton(selected = identity == option.id, onClick = { identity = option.id }); TextButton(onClick = { identity = option.id }) { Text(option.label) } }
            }
            Row { RadioButton(selected = identity == null, onClick = { identity = null }); TextButton(onClick = { identity = null }) { Text("密码登录") } }
        } }, confirmButton = { TextButton(onClick = { save(label, hostname, port, username, identity) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ImportDialog(model: ShellDeckModel, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) { uri = it; pasted = "" } }
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = { if (!model.busy) onDismiss() }, title = { Text("导入 SSH 私钥") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("身份名称") }, singleLine = true, enabled = !model.busy)
            Text("支持 OpenSSH 与 PEM / PKCS#8 私钥（Ed25519、RSA）；私钥加密保存。")
            TextButton(enabled = !model.busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (uri == null) "选择私钥文件" else "已选择文件 · 重新选择") }
            if (uri == null) OutlinedTextField(pasted, { if (it.length <= 256 * 1024) pasted = it },
                label = { Text("或粘贴私钥") }, minLines = 3, maxLines = 5, enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            else TextButton(onClick = { uri = null }, enabled = !model.busy) { Text("改用粘贴") }
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase（可留空，不保存）") },
                singleLine = true, enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(enabled = !model.busy && (uri != null || pasted.isNotBlank()), onClick = {
            model.importIdentity(label, pasted, uri, passphrase, onDismiss); pasted = ""; passphrase = ""
        }) { Text("导入") } }, dismissButton = { TextButton(enabled = !model.busy, onClick = onDismiss) { Text("取消") } })
}

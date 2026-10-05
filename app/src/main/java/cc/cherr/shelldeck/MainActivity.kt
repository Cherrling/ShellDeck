package cc.cherr.shelldeck

import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.cherr.shelldeck.data.HostFilter
import cc.cherr.shelldeck.data.browseHosts
import cc.cherr.shelldeck.data.HostRecord
import cc.cherr.shelldeck.data.IdentityRecord
import cc.cherr.shelldeck.ssh.TrustDecision
import cc.cherr.shelldeck.ui.ShellDeckTheme
import cc.cherr.shelldeck.settings.SettingsScreen
import cc.cherr.shelldeck.keyboard.ExtraKeysBar
import androidx.compose.runtime.saveable.rememberSaveable

private enum class MainPage(val label: String, val icon: Int) {
    HOSTS("服务器", R.drawable.ic_hosts), SESSIONS("会话", R.drawable.ic_sessions),
    SETTINGS("设置", R.drawable.ic_settings)
}

class MainActivity : ComponentActivity() {
    internal var sessionsRequested by mutableIntStateOf(0)
        private set
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ConnectionService.ACTION_SESSIONS) sessionsRequested++
    }

    internal var onTerminalFontSizeChange: ((Int) -> Unit)? = null
    private val consumedVolumeKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val adjust = onTerminalFontSizeChange
            if (adjust != null) {
                consumedVolumeKeys.add(keyCode)
                // One step per physical press: holding a key must not flood the remote PTY with resizes.
                if (event.repeatCount == 0 && !event.isCanceled) adjust(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                return true
            }
            consumedVolumeKeys.remove(keyCode)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedVolumeKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.action == ConnectionService.ACTION_SESSIONS) sessionsRequested++
        enableEdgeToEdge()
        setContent {
            val model: ShellDeckModel = viewModel()
            ShellDeckTheme(model.settings.theme, model.settings.dynamicColor) { ShellDeckApp(model, this@MainActivity) }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun terminalKeyboardInsets(terminalActive: Boolean): WindowInsets =
    if (terminalActive) WindowInsets.imeAnimationTarget else WindowInsets.ime

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ShellDeckApp(model: ShellDeckModel, activity: MainActivity) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var hostQuery by rememberSaveable { mutableStateOf("") }
    var hostFilter by rememberSaveable { mutableStateOf(HostFilter.ALL) }
    val visibleHosts = remember(model.hosts, hostQuery, hostFilter) { browseHosts(model.hosts, hostQuery, hostFilter) }
    var hostEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HostRecord?>(null) }
    var importing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<HostRecord?>(null) }
    var closing by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(MainPage.HOSTS) }
    var showIdentities by rememberSaveable { mutableStateOf(false) }
    var editingKeyboard by remember { mutableStateOf(false) }
    var deletingHost by remember { mutableStateOf<HostRecord?>(null) }
    var deletingIdentity by remember { mutableStateOf<IdentityRecord?>(null) }
    val manager = model.sessionManager
    LaunchedEffect(activity.sessionsRequested) {
        if (activity.sessionsRequested > 0) {
            manager.home(); page = MainPage.SESSIONS; showIdentities = false; editingKeyboard = false
        }
    }
    val connection = manager.selected
    val terminal = connection?.terminal
    val showSettings = terminal == null && page == MainPage.SETTINGS && !showIdentities
    val background = terminal?.let { Color(it.backgroundColor) } ?: MaterialTheme.colorScheme.background
    val foreground = terminal?.let { Color(it.foregroundColor) } ?: MaterialTheme.colorScheme.onBackground
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = background.luminance() > 0.5f
            isAppearanceLightNavigationBars = background.luminance() > 0.5f
        }
    }
    val volumeChangesFont = terminal != null && closing == null &&
        connection?.challenge == null && connection?.passphraseIdentity == null && model.error == null
    DisposableEffect(activity, volumeChangesFont) {
        activity.onTerminalFontSizeChange = if (volumeChangesFont) model::adjustFontSize else null
        onDispose { activity.onTerminalFontSizeChange = null }
    }
    BackHandler(terminal != null && !showSettings) {
        if (terminal?.hideKeyboardIfVisible() != true) { manager.home(); page = MainPage.SESSIONS }
    }
    BackHandler(terminal == null && showIdentities) { showIdentities = false }
    Scaffold(containerColor = background, contentColor = foreground, floatingActionButton = {
        if (terminal == null && !editingKeyboard && !model.busy && (page == MainPage.HOSTS || showIdentities)) {
            FloatingActionButton(onClick = {
                if (showIdentities) importing = true else { editing = null; hostEditor = true }
            }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = if (showIdentities) "导入 SSH Key" else "添加服务器")
            }
        }
    }, bottomBar = {
        if (terminal == null && !editingKeyboard) NavigationBar(Modifier.testTag("main-navigation")) {
            MainPage.entries.forEach { item -> NavigationBarItem(
                selected = page == item, onClick = { page = item; showIdentities = false },
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(item.label) }, modifier = Modifier.testTag("page-${item.name}")) }
        }
    }) { insets ->
        // A terminal resize reaches the remote TUI. Use the IME destination, not each animation frame.
        val keyboardInsets = terminalKeyboardInsets(terminal != null)
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).windowInsetsPadding(keyboardInsets)) {
            if (showSettings) SettingsScreen(model, onIdentities = { showIdentities = true }, onEditorVisibilityChanged = { editingKeyboard = it }) { page = MainPage.HOSTS }
            else if (terminal != null && connection != null) {
                if (!connection.connected) {
                    Text(connection.status, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                key(terminal) {
                    AndroidView(factory = terminal::createView, modifier = Modifier.weight(1f).fillMaxWidth(),
                        onRelease = terminal::releaseView, update = {})
                }
                ExtraKeysBar(model.settings.keyboard, terminal, model.settings.keyboardSizing)
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (showIdentities) "SSH 身份与密钥" else if (page == MainPage.SESSIONS) "活动会话" else page.label,
                        Modifier.padding(16.dp), style = if (showIdentities) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
                }
                if (page == MainPage.HOSTS && !showIdentities) {
                    OutlinedTextField(hostQuery, { hostQuery = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        label = { Text("搜索名称、地址或用户名") },
                        trailingIcon = { if (hostQuery.isNotEmpty()) IconButton(onClick = { hostQuery = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "清空搜索")
                        } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("host-search"))
                    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HostFilter.entries.forEach { filter -> FilterChip(hostFilter == filter, { hostFilter = filter },
                            label = { Text(filter.label) }, modifier = Modifier.testTag("host-filter-${filter.name}")) }
                    }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp,
                    bottom = if (page == MainPage.HOSTS || showIdentities) 96.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page == MainPage.SESSIONS) {
                    if (manager.sessions.isEmpty()) item { Text("还没有会话，从服务器页面开始连接。") }
                    items(manager.sessions, key = { "session:${it.id}" }) { session ->
                        val sessionTitle = cc.cherr.shelldeck.ui.rememberSessionTitle(session.terminal)
                        OutlinedCard(onClick = { manager.select(session.id) },
                            modifier = Modifier.fillMaxWidth().testTag("session-${session.id}")) {
                            Row(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${session.host.label} · 会话 ${session.number}", style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(when {
                                        session.challenge != null -> "等待确认服务器指纹"
                                        !session.connected -> session.status
                                        else -> sessionTitle.ifBlank { session.status }
                                    }, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { closing = session.id }, modifier = Modifier.testTag("close-session-${session.id}")) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭 ${session.host.label} 的会话 ${session.number}")
                                }
                            }
                        }
                    }
                    }
                    if (page == MainPage.HOSTS) {
                    if (model.hosts.isEmpty()) item {
                        Text("添加常用服务器，点击卡片即可连接。私钥可在设置中的「SSH 身份与密钥」导入。")
                    }
                    if (model.hosts.isNotEmpty() && visibleHosts.isEmpty()) item {
                        Text(if (hostQuery.isNotBlank()) "没有匹配的服务器" else if (hostFilter == HostFilter.FAVORITES) "点击服务器旁的星标即可收藏。" else "还没有最近连接记录。")
                    }
                    items(visibleHosts, key = { it.id }) { host ->
                        var hostMenu by remember(host.id) { mutableStateOf(false) }
                        Card(onClick = { if (host.identityId == null) login = host else model.connect(host, "") },
                            enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("host-${host.id}")) {
                            Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(host.label, style = MaterialTheme.typography.titleMedium)
                                    Text("${host.username}@${host.hostname}:${host.port}")
                                    Text(model.identities.firstOrNull { it.id == host.identityId }?.label ?: "密码登录", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { model.toggleFavorite(host.id) }, enabled = !model.busy,
                                    modifier = Modifier.testTag("favorite-${host.id}")) {
                                    Icon(painterResource(if (host.favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                                        contentDescription = if (host.favorite) "取消收藏 ${host.label}" else "收藏 ${host.label}")
                                }
                                Box {
                                    IconButton(onClick = { hostMenu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "${host.label} 的更多操作") }
                                    DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                                        DropdownMenuItem(text = { Text("编辑服务器") }, enabled = !model.busy, onClick = { hostMenu = false; editing = host; hostEditor = true })
                                        DropdownMenuItem(text = { Text("复制服务器") }, enabled = !model.busy, onClick = { hostMenu = false; model.duplicateHost(host.id) })
                                        DropdownMenuItem(text = { Text("删除服务器") }, enabled = !model.busy, onClick = { hostMenu = false; deletingHost = host })
                                    }
                                }
                            }
                        }
                    }
                    }
                    if (showIdentities) {
                    if (model.identities.isEmpty()) item { Text("导入可供多个服务器共用的 SSH 私钥。私钥加密保存，口令仅用于本次操作。") }
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
    connection?.takeUnless { showSettings }?.passphraseIdentity?.let { identity ->
        var secret by remember(identity) { mutableStateOf("") }
        AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { secret = ""; manager.close(connection!!.id) }, title = { Text("解锁私钥") },
            text = { Column {
                Text("$identity 使用了加密私钥。请输入口令，仅用于本次连接，不会保存。")
                OutlinedTextField(secret, { secret = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } },
            confirmButton = { TextButton(onClick = { connection!!.submitPassphrase(secret); secret = "" }) { Text("连接") } },
            dismissButton = { TextButton(onClick = { secret = ""; manager.close(connection!!.id) }) { Text("取消") } })
    }
    connection?.takeUnless { showSettings }?.challenge?.let { challenge ->
        AlertDialog(onDismissRequest = { connection!!.trust(TrustDecision.CANCEL) },
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
                TextButton(onClick = { connection!!.trust(TrustDecision.SAVE) }) { Text(if (challenge.previous == null) "信任并保存" else "确认更换并保存") }
                TextButton(onClick = { connection!!.trust(TrustDecision.ONCE) }) { Text("仅信任本次") }
            } }, dismissButton = { TextButton(onClick = { connection!!.trust(TrustDecision.CANCEL) }) { Text("取消") } })
    }
    closing?.let { id -> ConfirmDialog("关闭连接？", "只关闭这一条 SSH 连接。远端 tmux 会话可以在下次连接时重新附加。", { closing = null }) {
        manager.close(id); closing = null
    } }
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

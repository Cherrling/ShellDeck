package cc.cherr.shelldeck.backup

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

@Composable
fun BackupDialogs(controller: BackupController) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), controller::save)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) controller.restoreFile(uri) }
    val request = controller.export
    LaunchedEffect(request?.id, controller.busy) {
        if (request != null && !request.launched && !controller.busy) {
            request.launched = true
            try { save.launch(request.filename) } catch (_: ActivityNotFoundException) { controller.pickerUnavailable() }
        }
    }
    if (controller.menu) AlertDialog(onDismissRequest = { controller.menu = false }, title = { Text("备份与恢复") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("备份主机、身份私钥、启动命令及设置。导入的字体文件需另行保存；服务器信任记录不迁移。")
            TextButton(onClick = { controller.menu = false; controller.backup() }) { Text("创建加密备份") }
            TextButton(onClick = {
                controller.menu = false
                try { open.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { controller.pickerUnavailable() }
            }) { Text("从备份恢复") }
        } }, confirmButton = { TextButton(onClick = { controller.menu = false }) { Text("关闭") } })
    controller.form?.let { form -> TransferPasswordDialog(controller, form) }
    controller.preview?.let { plan ->
        AlertDialog(onDismissRequest = controller::cancel, title = { Text("恢复预览") },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${plan.identityEntries.size} 个身份 · ${plan.hostEntries.size} 个主机 · ${plan.proxyEntries.size} 个代理")
                    Text("重复条目默认保留本机。使用备份会覆盖所列记录；导入副本会创建独立条目。", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(controller.restoreSettings, { controller.restoreSettings = it }); Text("同时恢复设置")
                    }
                    if (plan.missingFont) Text("备份使用的导入字体在本机不存在。恢复设置时将改用内置 Maple Mono，可随后重新导入字体。")
                    Text("恢复前需关闭活动连接。服务器指纹仍按本机记录验证。", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.identityEntries.isNotEmpty()) item { Text("身份", style = MaterialTheme.typography.titleMedium) }
                items(plan.identityEntries, key = { "identity:${it.id}" }) { entry ->
                    RestoreItem(entry, controller.identityChoices[entry.id] ?: RestoreChoice.KEEP) {
                        controller.identityChoices = controller.identityChoices + (entry.id to it)
                    }
                }
                if (plan.proxyEntries.isNotEmpty()) item { Text("代理", style = MaterialTheme.typography.titleMedium) }
                items(plan.proxyEntries, key = { "proxy:${it.id}" }) { entry ->
                    RestoreItem(entry, controller.proxyChoices[entry.id] ?: RestoreChoice.KEEP) {
                        controller.proxyChoices = controller.proxyChoices + (entry.id to it)
                    }
                }
                if (plan.hostEntries.isNotEmpty()) item { Text("主机", style = MaterialTheme.typography.titleMedium) }
                items(plan.hostEntries, key = { "host:${it.id}" }) { entry ->
                    RestoreItem(entry, controller.hostChoices[entry.id] ?: RestoreChoice.KEEP) {
                        controller.hostChoices = controller.hostChoices + (entry.id to it)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { controller.apply(controller.identityChoices, controller.hostChoices, controller.restoreSettings) }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = controller::cancel) { Text("取消") } })
    }
    if (controller.busy && controller.form == null) AlertDialog(onDismissRequest = {}, title = { Text("正在处理") },
        text = { Column { Text("正在加密、验证或保存，请稍候。") ; LinearProgressIndicator(Modifier.fillMaxWidth()) } }, confirmButton = {})
    else if (request != null && controller.form == null) AlertDialog(onDismissRequest = controller::cancel,
        title = { Text("选择保存位置") }, text = { Text("正在等待系统文件选择器返回。") },
        confirmButton = { TextButton(onClick = controller::cancel) { Text("取消导出") } })
    controller.message?.let { message -> AlertDialog(onDismissRequest = controller::clearMessage, title = { Text("备份与导出") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = controller::clearMessage) { Text("确定") } }) }
}

@Composable
private fun TransferPasswordDialog(controller: BackupController, form: TransferForm) {
    var password by remember(form) { mutableStateOf("") }
    var confirmation by remember(form) { mutableStateOf("") }
    var original by remember(form) { mutableStateOf("") }
    var encrypted by remember(form) { mutableStateOf(true) }
    val restore = form.kind == TransferKind.RESTORE
    val protected = form.kind != TransferKind.PRIVATE_KEY || encrypted
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = controller::cancel,
        title = { Text(when(form.kind) { TransferKind.BACKUP -> "创建加密备份"; TransferKind.RESTORE -> "打开备份"; TransferKind.PRIVATE_KEY -> "导出私钥" }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.kind == TransferKind.PRIVATE_KEY) {
                Text(form.label)
                Text("导出为标准 PKCS#8 PEM，可重新导入 ShellDeck 或支持该格式的工具。")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(encrypted, { encrypted = it }, enabled = !controller.busy); Text("使用口令保护")
                }
                if (!encrypted) Text("将导出无口令的完整私钥。任何获得文件的人都可以使用这把密钥，请妥善保存。", color = MaterialTheme.colorScheme.error)
                if (form.needsUnlock) PasswordField(original, { original = it }, "原私钥口令", !controller.busy)
            } else if (!restore) Text("备份密码独立于私钥口令。请牢记密码；恢复后，原有私钥口令仍然有效。")
            if (protected) {
                PasswordField(password, { password = it }, if (restore) "备份密码" else "导出密码（至少 8 位）", !controller.busy)
                if (!restore) PasswordField(confirmation, { confirmation = it }, "再次输入导出密码", !controller.busy)
            }
            if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(enabled = !controller.busy && (!protected ||
            (if (restore) password.isNotEmpty() else password.length >= 8 && password == confirmation)), onClick = {
            controller.prepare(password, original, encrypted)
            password = ""; confirmation = ""; original = ""
        }) { Text(if (restore) "查看预览" else "选择保存位置") } },
        dismissButton = { TextButton(enabled = !controller.busy, onClick = controller::cancel) { Text("取消") } })
}
@Composable
private fun PasswordField(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}
@Composable
private fun RestoreItem(entry: RestoreEntry, choice: RestoreChoice, choose: (RestoreChoice) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Text(entry.label, style = MaterialTheme.typography.titleSmall)
        Text(entry.detail, style = MaterialTheme.typography.bodySmall)
        if (entry.existingId == null) Text("新增", style = MaterialTheme.typography.labelSmall)
        else {
            Text("对应本机：${entry.existingLabel}", style = MaterialTheme.typography.labelSmall)
            entry.existingDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Box {
                TextButton(onClick = { menu = true }) { Text(choice.label) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    RestoreChoice.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) },
                        onClick = { choose(option); menu = false }) }
                }
            }
        }
    }
}

package cc.cherr.shelldeck.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cc.cherr.shelldeck.ShellDeckModel
import cc.cherr.shelldeck.ssh.SshKeys

@Composable
fun GenerateIdentityDialog(model: ShellDeckModel, dismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeys.GenerationType.ED25519) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!model.busy) dismiss() }, title = { Text("生成 SSH 密钥") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { label = it }, label = { Text("身份名称") }, singleLine = true, enabled = !model.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SshKeys.GenerationType.entries.forEach { option ->
                    FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.label) }, enabled = !model.busy)
                }
            }
            Text("私钥在本机生成并加密保存。生成后可复制公钥到服务器的 authorized_keys。")
            OutlinedTextField(password, { password = it }, label = { Text("私钥口令（可留空）") }, singleLine = true,
                enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (password.isNotEmpty()) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("再次输入口令") }, singleLine = true,
                enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !model.busy && (password.isEmpty() || password == confirmation), onClick = {
            model.generateIdentity(label, type, password, dismiss); password = ""; confirmation = ""
        }) { Text("生成") } },
        dismissButton = { TextButton(enabled = !model.busy, onClick = dismiss) { Text("取消") } })
}

/** Kept composed while the system document picker is open, including Activity recreation. */
@Composable
fun PublicKeyDialogs(model: ShellDeckModel) {
    val context = LocalContext.current
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    // text/plain makes Android DocumentsUI append .txt to the requested .pub filename.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportingId; exportingId = null
        if (uri != null && id != null) model.exportPublicKey(id, uri)
    }
    model.publicKeyDetails?.let { detail ->
        var copied by remember(detail.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = model::dismissPublicKey, title = { Text(detail.label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail.algorithm); Text(detail.fingerprint, style = MaterialTheme.typography.labelSmall)
                Text("公钥 · OpenSSH 格式")
                SelectionContainer { Text(detail.key, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = !model.busy, onClick = {
                    model.dismissPublicKey(); model.backup.privateKey(detail.id)
                }) { Text("导出私钥") }
                TextButton(enabled = !model.busy, onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("SSH 公钥", detail.key))
                    copied = true
                }) { Text(if (copied) "已复制公钥" else "复制公钥") }
            } }, confirmButton = { TextButton(enabled = !model.busy && exportingId == null, onClick = {
                exportingId = detail.id
                val name = detail.label.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("").take(80).ifBlank { "id_ssh" }
                try { exporter.launch("$name.pub") }
                catch (_: android.content.ActivityNotFoundException) {
                    exportingId = null
                    android.widget.Toast.makeText(context, "系统文件选择器不可用", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("导出 .pub") } },
            dismissButton = { TextButton(enabled = !model.busy, onClick = model::dismissPublicKey) { Text("关闭") } })
    }
    model.publicKeyUnlock?.let { id ->
        var password by remember(id) { mutableStateOf("") }
        AlertDialog(properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), onDismissRequest = model::dismissPublicKey,
            title = { Text("提取公钥") }, text = { Column {
                Text("这把旧密钥尚未保存公钥，需要用私钥口令提取一次。口令不会保存。")
                OutlinedTextField(password, { password = it }, label = { Text("Passphrase") }, singleLine = true,
                    enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } }, confirmButton = { TextButton(enabled = !model.busy, onClick = { model.showPublicKey(id, password); password = "" }) { Text("解锁") } },
            dismissButton = { TextButton(enabled = !model.busy, onClick = model::dismissPublicKey) { Text("取消") } })
    }
}

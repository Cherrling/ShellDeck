package cc.cherr.shelldeck.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TerminalThemeEditor(initial: TerminalTheme, face: android.graphics.Typeface, cancel: () -> Unit, save: (TerminalTheme) -> Unit) {
    var json by rememberSaveable { mutableStateOf(initial.json().toString()) }
    val draft = remember(json) { TerminalTheme.fromJson(JSONObject(json)) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var discard by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val scope = rememberCoroutineScope()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val theme = withContext(Dispatchers.IO) { requireNotNull(resolver.openInputStream(uri)).use { TerminalTheme.read(it, draft) } }
                json = theme.json().toString(); message = "已导入预览，保存后生效。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = "导入失败：${e.message?.take(140).orEmpty()}"
            } finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val contents = exporting; exporting = null
        if (uri != null && contents != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(contents.toByteArray()) } }
                message = "配色已导出。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = "导出失败，请检查保存位置。"
            } finally { busy = false }
        }
    }
    BackHandler { if (!busy) { if (draft != initial) discard = true else cancel() } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("终端配色", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(enabled = !busy, onClick = { save(draft) }) { Text("保存配色") }
        }
        Text("仅改变终端，保存后应用到现有及新建会话。", style = MaterialTheme.typography.bodySmall)
        AndroidView(factory = { ThemePreviewView(it) }, update = { it.update(draft, face) },
            modifier = Modifier.fillMaxWidth().height(168.dp).padding(vertical = 8.dp).testTag("theme-preview"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { import.launch(arrayOf("*/*")) }) { Text("导入配色") }
            TextButton(enabled = !busy, onClick = { exporting = draft.json().toString(2); export.launch("ShellDeck-theme.json") }) { Text("导出配色") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalPalette.entries.forEach { palette ->
                AssistChip(enabled = !busy, onClick = { json = TerminalTheme.preset(palette).json().toString() },
                    label = { Text(if (palette == TerminalPalette.DARK) "使用深色基底" else "使用浅色基底") })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            items(TerminalTheme.KEYS.indices.toList(), key = { it }) { index ->
                ListItem(headlineContent = { Text(TerminalTheme.LABELS[index]) },
                    supportingContent = { Text("${TerminalTheme.KEYS[index]} · ${TerminalTheme.hex(draft.colors[index])}") },
                    leadingContent = { Box(Modifier.size(32.dp).background(Color(draft.colors[index]), MaterialTheme.shapes.small)) },
                    modifier = Modifier.testTag("theme-color-$index").clickable(enabled = !busy) { selected = index })
            }
        }
    }
    if (selected >= 0) ColorEditor(TerminalTheme.LABELS[selected], draft.colors[selected], { selected = -1 }) {
        json = draft.withColor(selected, it).json().toString(); selected = -1
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的配色？") },
        confirmButton = { TextButton(onClick = cancel) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun ColorEditor(label: String, initial: Int, cancel: () -> Unit, save: (Int) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(TerminalTheme.hex(initial)) }
    val value = runCatching { TerminalTheme.parse(text) }.getOrNull()
    AlertDialog(onDismissRequest = cancel, title = { Text(label) }, text = { Column {
        Box(Modifier.fillMaxWidth().height(40.dp).background(Color(value ?: initial), MaterialTheme.shapes.small))
        OutlinedTextField(text, { if (it.length <= 7) text = it }, singleLine = true, isError = value == null,
            label = { Text("RGB 十六进制") }, supportingText = { Text("#RRGGBB，例如 #282828") })
        listOf("红", "绿", "蓝").forEachIndexed { index, name ->
            val shift = (2 - index) * 8
            val color = value ?: initial
            Text("$name：${(color ushr shift) and 255}")
            Slider(((color ushr shift) and 255).toFloat(), { channel ->
                text = TerminalTheme.hex((color and (255 shl shift).inv()) or (channel.toInt() shl shift))
            }, valueRange = 0f..255f)
        }
    } }, confirmButton = { TextButton(enabled = value != null, onClick = { save(requireNotNull(value)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}

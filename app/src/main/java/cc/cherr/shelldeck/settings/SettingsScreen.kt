package cc.cherr.shelldeck.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.cherr.shelldeck.ShellDeckModel
import cc.cherr.shelldeck.keyboard.*
import org.json.JSONArray

@Composable
fun SettingsScreen(model: ShellDeckModel, onIdentities: () -> Unit = {}, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingColors by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(editing, editingColors) {
        onEditorVisibilityChanged(editing || editingColors)
        onDispose { onEditorVisibilityChanged(false) }
    }
    var rename by remember { mutableStateOf<FontEntry?>(null) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importFont(uri) }
    if (editing) { KeyboardEditor(model.settings.keyboard, model.settings.keyboardSizing, { editing = false }) {
        model.updateSettings(model.settings.copy(keyboard = it)); editing = false
    }; return }
    if (editingColors) {
        TerminalThemeEditor(model.settings.terminalTheme ?: TerminalTheme.preset(model.settings.palette), model.typeface,
            { editingColors = false }) { model.updateSettings(model.settings.copy(terminalTheme = it)); editingColors = false }
        return
    }
    BackHandler(onBack = onBack)
    val settings = model.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        ListItem(headlineContent = { Text("SSH 身份与密钥") },
            supportingContent = { Text("生成、导入、导出密钥，供服务器共用") }, modifier = Modifier.clickable(onClick = onIdentities))
        ListItem(headlineContent = { Text("备份与恢复") }, supportingContent = { Text("加密备份、迁移和恢复主机与身份") },
            modifier = Modifier.clickable(enabled = !model.busy) { model.backup.menu = true })
        HorizontalDivider()
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Choices(ThemeMode.entries, settings.theme, { when(it) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "明亮"; ThemeMode.DARK -> "深色" } }) {
            model.updateSettings(settings.copy(theme = it))
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(settings.dynamicColor, { model.updateSettings(settings.copy(dynamicColor = it)) }); Text("动态配色")
        }
        HorizontalDivider()
        Text("终端字体", style = MaterialTheme.typography.titleLarge)
        if (model.fontBusy || model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.fonts.forEach { font ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(settings.fontId == font.id, onClick = { model.updateSettings(settings.copy(fontId = font.id)) })
                TextButton(onClick = { model.updateSettings(settings.copy(fontId = font.id)) }, modifier = Modifier.weight(1f)) { Text(font.label) }
                if (font.imported) {
                    TextButton(enabled = !model.busy, onClick = { rename = font }) { Text("改名") }
                    TextButton(enabled = !model.busy, onClick = { deleting = font }) { Text("删除") }
                }
            }
        }
        TextButton(enabled = !model.busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("导入 TTF / OTF 字体") }
        var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat()) }
        Text("字号：${size.toInt()} sp")
        Text("终端内按音量 ＋ / − 调整字号，每次 1 sp", style = MaterialTheme.typography.bodySmall)
        Slider(size, { size = it }, valueRange = 8f..32f, steps = 23,
            onValueChangeFinished = { model.updateSettings(settings.copy(fontSize = size.toInt())) })
        Text("终端配色（独立于 App 外观）")
        Choices(TerminalPalette.entries, settings.palette.takeIf { settings.terminalTheme == null }, { if (it == TerminalPalette.DARK) "深色终端" else "浅色终端" }) {
            model.updateSettings(settings.copy(palette = it, terminalTheme = null))
        }
        Text(if (settings.terminalTheme != null) "当前使用自定义配色" else "当前使用内置配色")
        TextButton(onClick = { editingColors = true }) { Text("编辑终端配色") }
        val previewTheme = settings.terminalTheme ?: TerminalTheme.preset(settings.palette)
        Surface(color = androidx.compose.ui.graphics.Color(previewTheme.colors[17]), contentColor = androidx.compose.ui.graphics.Color(previewTheme.colors[16])) {
            Text("Aa 0123 [] {} <>\n中文等宽测试  ┌─┬─┐\n图标：\uE0B0 \uF120 \uF07B", Modifier.fillMaxWidth().padding(12.dp),
                fontFamily = FontFamily(model.typeface), fontSize = size.sp)
        }
        Text("Maple Mono：SIL Open Font License 1.1", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("快捷键", style = MaterialTheme.typography.titleLarge)
        Text("两行整体左右滑动。修饰键点按用于下一次输入；按住持续生效；长按后松手锁定，再点解除。")
        TextButton(onClick = { editing = true }) { Text("编辑快捷键布局") }
        KeyboardSizeSettings(settings.keyboard, settings.keyboardSizing) {
            model.updateSettings(model.settings.copy(keyboardSizing = it))
        }
        HorizontalDivider()
        BackgroundSettings(settings.backgroundMode, model.backgroundError) {
            model.updateSettings(model.settings.copy(backgroundMode = it))
        }
        HorizontalDivider()
        Text("关于 ShellDeck", style = MaterialTheme.typography.titleLarge)
        Text("版本 ${cc.cherr.shelldeck.BuildConfig.VERSION_NAME}（${cc.cherr.shelldeck.BuildConfig.VERSION_CODE}）")
        Text("构建时间：${if (cc.cherr.shelldeck.BuildConfig.BUILD_TIME == "Local build (unrecorded)") "本地构建（未记录）" else cc.cherr.shelldeck.BuildConfig.BUILD_TIME}")
        Text("提交：${cc.cherr.shelldeck.BuildConfig.SOURCE_REVISION}", style = MaterialTheme.typography.bodySmall)
    }
    rename?.let { font ->
        var label by remember(font.id) { mutableStateOf(font.label) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("字体名称") },
            text = { OutlinedTextField(label, { if (it.length <= 80) label = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { model.renameFont(font.id, label); rename = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
    deleting?.let { font -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除字体？") },
        text = { Text("${font.label}：若正在使用，将切回系统等宽字体。") },
        confirmButton = { TextButton(onClick = { model.deleteFont(font.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T?, label: (T) -> String, choose: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { item -> FilterChip(selected == item, onClick = { choose(item) }, label = { Text(label(item)) }) }
    }
}

@Composable
private fun KeyboardEditor(initial: KeyboardProfile, sizing: KeyboardSizing, cancel: () -> Unit, save: (KeyboardProfile) -> Unit) {
    var json by rememberSaveable { mutableStateOf(SettingsStore.encodeKeyboard(initial).toString()) }
    val draft = remember(json) { SettingsStore.decodeKeyboard(JSONArray(json)) }
    var selected by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun back() { if (draft != initial) discard = true else cancel() }
    fun replace(rows: List<List<KeySlot>>) { json = SettingsStore.encodeKeyboard(KeyboardProfile(rows)).toString() }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("编辑快捷键", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { save(draft) }) { Text("保存布局") }
        }
        Text("点击修改内容，长按拖动排序或移到另一行；拖到左右边缘可自动滚动。保存后才会应用。")
        KeyboardLayoutEditor(draft, sizing,
            edit = { selected = it.row to it.index },
            add = { r ->
                val row = draft.rows[r]
                replace(draft.rows.mapIndexed { i, keys -> if (i == r) keys + KeySlot("新键", KeyAction.Character(" ")) else keys })
                selected = r to row.size
            },
            move = { from, to -> replace(draft.moveKey(from, to).rows) })
        TextButton(onClick = { json = SettingsStore.encodeKeyboard(KeyboardProfile.default()).toString() }) { Text("恢复默认布局（保存后生效）") }
    }
    selected?.let { (r, c) ->
        val slot = draft.rows[r][c]
        KeyEditor(slot, draft.rows[r].size > 1, { selected = null }, { updated ->
            replace(draft.rows.mapIndexed { i, row -> if (i == r) row.mapIndexed { j, item -> if (j == c) updated else item } else row }); selected = null
        }, {
            replace(draft.rows.mapIndexed { i, row -> if (i == r) row.filterIndexed { j, _ -> j != c } else row }); selected = null
        })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的布局？") },
        confirmButton = { TextButton(onClick = cancel) { Text("放弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun KeyEditor(initial: KeySlot, canDelete: Boolean, cancel: () -> Unit, save: (KeySlot) -> Unit, delete: () -> Unit) {
    val types = listOf("字符", "特殊键", "修饰键", "转义序列", "宏", "键盘开关")
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var width by rememberSaveable { mutableIntStateOf(initial.width) }
    var type by rememberSaveable { mutableStateOf(when(initial.action) {
        is KeyAction.Character -> "字符"; is KeyAction.Special -> "特殊键"; is KeyAction.Modifier -> "修饰键"
        is KeyAction.EscapeSequence -> "转义序列"; is KeyAction.Macro -> "宏"; else -> "键盘开关"
    }) }
    var text by rememberSaveable { mutableStateOf(when(val a = initial.action) {
        is KeyAction.Character -> a.text; is KeyAction.Macro -> a.text
        is KeyAction.EscapeSequence -> a.sequence.replace("\u001b", "\\e"); else -> ""
    }) }
    var special by rememberSaveable { mutableStateOf((initial.action as? KeyAction.Special)?.key ?: SpecialKey.ESC) }
    var modifier by rememberSaveable { mutableStateOf((initial.action as? KeyAction.Modifier)?.key ?: ModifierKey.SHIFT) }
    var combo by rememberSaveable { mutableStateOf((initial.action as? KeyAction.Special)?.modifiers ?: emptySet()) }
    val valid = label.isNotBlank() && (type !in listOf("字符", "宏", "转义序列") || text.isNotEmpty()) &&
        (type != "字符" || (text.codePointCount(0, text.length) == 1 && text.codePointAt(0) !in 0xD800..0xDFFF))
    fun editedSlot(): KeySlot {
        val action = when(type) {
            "字符" -> KeyAction.Character(text); "特殊键" -> KeyAction.Special(special, combo)
            "修饰键" -> KeyAction.Modifier(modifier); "宏" -> KeyAction.Macro(text)
            "转义序列" -> KeyAction.EscapeSequence(text.replace("\\e", "\u001b")); else -> KeyAction.ToggleKeyboard
        }
        return KeySlot(label, action, width)
    }
    AlertDialog(onDismissRequest = cancel, title = { Text("编辑按键") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(label, { if (it.length <= 24) label = it }, label = { Text("显示名称") }, singleLine = true)
            Choices(types, type, { it }) { type = it }
            when (type) {
                "特殊键" -> {
                    Choices(SpecialKey.entries, special, { it.name }) { special = it }
                    Row { ModifierKey.entries.forEach { m -> FilterChip(m in combo, onClick = { combo = if (m in combo) combo - m else combo + m }, label = { Text(m.name) }) } }
                }
                "修饰键" -> Choices(ModifierKey.entries, modifier, { it.name }) { modifier = it }
                "键盘开关" -> Text("展开或收起软键盘")
                else -> {
                    OutlinedTextField(text, { if (it.length <= 4096) text = it }, label = { Text("发送内容") }, maxLines = 5)
                    if (type == "字符") Text("填写一个字符；多个字符请使用宏。")
                    if (type == "转义序列") Text("用 \\e 表示 ESC，例如 \\e[5~。")
                    if (type == "宏") Text("按原样发送，不自动追加回车。需要执行命令时，在内容末尾输入换行。")
                }
            }
            Choices(listOf(1,2,3), width, { "${it}倍宽" }) { width = it }
            TextButton(enabled = canDelete, onClick = delete) { Text("删除按键") }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        save(editedSlot())
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}

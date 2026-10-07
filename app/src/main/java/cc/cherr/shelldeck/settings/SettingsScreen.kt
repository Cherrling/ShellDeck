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
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import cc.cherr.shelldeck.R
import cc.cherr.shelldeck.BuildConfig
import org.json.JSONArray

private enum class SettingsPage(val title: String) {
    HOME("设置"), APPEARANCE("应用外观"), FONT("字体与字号"), COLORS("终端配色"),
    KEYBOARD("快捷键"), CONNECTION("连接与后台"), ABOUT("关于 ShellDeck")
}
private enum class SettingPicker { THEME, PALETTE, KEEP_ALIVE }

@Composable
fun SettingsScreen(model: ShellDeckModel, onIdentities: () -> Unit = {}, onEditorVisibilityChanged: (Boolean) -> Unit = {}, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingColors by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf<SettingPicker?>(null) }
    val scrollStates = rememberSaveableStateHolder()
    DisposableEffect(editing, editingColors) {
        onEditorVisibilityChanged(editing || editingColors)
        onDispose { onEditorVisibilityChanged(false) }
    }
    if (editing) {
        KeyboardEditor(model.settings.keyboard, model.settings.keyboardSizing, { editing = false }) {
            model.updateSettings(model.settings.copy(keyboard = it)); editing = false
        }
        return
    }
    if (editingColors) {
        TerminalThemeEditor(model.settings.terminalTheme ?: TerminalTheme.preset(model.settings.palette), model.typeface,
            { editingColors = false }) { model.updateSettings(model.settings.copy(terminalTheme = it)); editingColors = false }
        return
    }
    BackHandler { if (page == SettingsPage.HOME) onBack() else page = SettingsPage.HOME }
    val settings = model.settings
    val fontName = model.fonts.firstOrNull { it.id == settings.fontId }?.label ?: "加载中…"
    val paletteName = if (settings.terminalTheme != null) "自定义配色" else paletteLabel(settings.palette)
    scrollStates.SaveableStateProvider(page.name) {
        SettingsLayout(page.title) {
            when (page) {
                SettingsPage.HOME -> {
                    SettingsGroup("外观与操作") {
                        SettingsLink("应用外观", themeLabel(settings.theme), R.drawable.ic_appearance) { page = SettingsPage.APPEARANCE }
                        SettingsLink("字体与字号", "$fontName · ${settings.fontSize} sp", R.drawable.ic_font) { page = SettingsPage.FONT }
                        SettingsLink("终端配色", paletteName, R.drawable.ic_palette) { page = SettingsPage.COLORS }
                        SettingsLink("快捷键", "${settings.keyboard.rows.size} 行 · 每行显示 ${settings.keyboardSizing.visibleKeys} 个", R.drawable.ic_keyboard) { page = SettingsPage.KEYBOARD }
                    }
                    SettingsGroup("连接与数据") {
                        SettingsLink("连接与后台", backgroundLabel(settings.backgroundMode), R.drawable.ic_connection) { page = SettingsPage.CONNECTION }
                        SettingsLink("SSH 身份与密钥", "生成、导入与导出", R.drawable.ic_key, onClick = onIdentities)
                        SettingsLink("备份与恢复", "加密备份与设备迁移", R.drawable.ic_backup, enabled = !model.busy) { model.backup.menu = true }
                    }
                    SettingsGroup {
                        SettingsLink("关于 ShellDeck", "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", R.drawable.ic_info) { page = SettingsPage.ABOUT }
                    }
                }
                SettingsPage.APPEARANCE -> {
                    SettingsGroup("主题") {
                        SettingsLink("夜间模式", themeLabel(settings.theme), R.drawable.ic_appearance) { selection = SettingPicker.THEME }
                        SettingsSwitch("动态配色", if (Build.VERSION.SDK_INT >= 31) "使用系统壁纸提供的颜色" else "需要 Android 12 或更高版本",
                            settings.dynamicColor, enabled = Build.VERSION.SDK_INT >= 31) {
                            model.updateSettings(model.settings.copy(dynamicColor = it))
                        }
                    }
                    SettingsNote("应用外观仅影响管理界面。终端的背景、文字和 ANSI 颜色在「终端配色」中单独设置。")
                }
                SettingsPage.FONT -> FontSettings(model)
                SettingsPage.COLORS -> {
                    SettingsGroup("颜色方案") {
                        SettingsLink("内置配色", paletteName, R.drawable.ic_palette) { selection = SettingPicker.PALETTE }
                        SettingsLink("编辑终端配色", "背景、前景、光标和 ANSI 色板", R.drawable.ic_settings) { editingColors = true }
                    }
                    SettingsGroup("预览") { TerminalFontPreview(model, settings.fontSize.toFloat()) }
                    SettingsNote("终端配色独立于应用外观，切换夜间模式不会改变终端颜色。")
                }
                SettingsPage.KEYBOARD -> {
                    SettingsGroup {
                        SettingsLink("编辑快捷键布局", "自定义按键，长按拖动排序", R.drawable.ic_keyboard) { editing = true }
                    }
                    SettingsGroup("大小与布局") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeyboardSizeSettings(settings.keyboard, settings.keyboardSizing) {
                                model.updateSettings(model.settings.copy(keyboardSizing = it))
                            }
                        }
                    }
                    SettingsNote("两行整体左右滑动。修饰键点按用于下一次输入；按住持续生效；长按后松手锁定，再点解除。")
                }
                SettingsPage.CONNECTION -> {
                    SettingsGroup("SSH 连接") {
                        SettingsLink("保活探测", keepAliveLabel(settings.keepAliveSeconds), R.drawable.ic_connection) { selection = SettingPicker.KEEP_ALIVE }
                    }
                    SettingsNote("新连接生效。连续三次探测未获响应后结束连接，不自动重连。关闭可减少空闲网络活动，但失效连接可能更晚被发现。")
                    BackgroundSettings(settings.backgroundMode, model.backgroundError) {
                        model.updateSettings(model.settings.copy(backgroundMode = it))
                    }
                }
                SettingsPage.ABOUT -> {
                    SettingsGroup {
                        ListItem(headlineContent = { Text("ShellDeck", style = MaterialTheme.typography.titleLarge) },
                            supportingContent = { Text("面向远程开发的 SSH 终端") },
                            leadingContent = { Icon(painterResource(R.drawable.ic_terminal), null, tint = MaterialTheme.colorScheme.primary) },
                            colors = settingsItemColors())
                    }
                    SettingsGroup("版本信息") {
                        SettingsValue("应用版本", "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                        SettingsValue("构建时间", if (BuildConfig.BUILD_TIME == "Local build (unrecorded)") "本地构建（未记录）" else BuildConfig.BUILD_TIME)
                        SettingsValue("源码提交", "提交：${BuildConfig.SOURCE_REVISION}")
                    }
                    SettingsGroup("字体许可") { SettingsValue("Maple Mono", "SIL Open Font License 1.1") }
                }
            }
        }
    }
    when (selection) {
        SettingPicker.THEME -> SettingsChoiceDialog("夜间模式", ThemeMode.entries, settings.theme, ::themeLabel, { selection = null }) {
            model.updateSettings(model.settings.copy(theme = it)); selection = null
        }
        SettingPicker.PALETTE -> SettingsChoiceDialog("内置配色", TerminalPalette.entries,
            settings.palette.takeIf { settings.terminalTheme == null }, ::paletteLabel, { selection = null }) {
            model.updateSettings(model.settings.copy(palette = it, terminalTheme = null)); selection = null
        }
        SettingPicker.KEEP_ALIVE -> SettingsChoiceDialog("保活探测", listOf(0, 60, 120), settings.keepAliveSeconds, ::keepAliveLabel, { selection = null }) {
            model.updateSettings(model.settings.copy(keepAliveSeconds = it)); selection = null
        }
        null -> Unit
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "明亮"; ThemeMode.DARK -> "深色" }
private fun paletteLabel(palette: TerminalPalette) = if (palette == TerminalPalette.DARK) "深色终端" else "浅色终端"
private fun keepAliveLabel(seconds: Int) = if (seconds == 0) "关闭" else "每 $seconds 秒"
internal fun backgroundLabel(mode: BackgroundMode) = when (mode) {
    BackgroundMode.OFF -> "关闭后台保持"; BackgroundMode.NORMAL -> "后台保持（推荐）"; BackgroundMode.ONGOING -> "尽量常驻通知"
}

@Composable
private fun FontSettings(model: ShellDeckModel) {
    val settings = model.settings
    var rename by remember { mutableStateOf<FontEntry?>(null) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importFont(uri) }
    if (model.fontBusy || model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    SettingsGroup("字体") {
        model.fonts.forEach { font ->
            var menu by remember(font.id) { mutableStateOf(false) }
            SettingsRadio(font.label, settings.fontId == font.id,
                trailing = if (font.imported) {{
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !model.busy) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "管理字体 ${font.label}")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = font })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = font })
                        }
                    }
                }} else null) { model.updateSettings(model.settings.copy(fontId = font.id)) }
        }
        SettingsLink("导入字体", "支持 TTF / OTF", R.drawable.ic_add, enabled = !model.busy) { picker.launch(arrayOf("*/*")) }
    }
    var size by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat()) }
    SettingsGroup("字号") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("字号：${size.toInt()} sp", style = MaterialTheme.typography.bodyLarge)
            Slider(size, { size = it }, valueRange = 8f..32f, steps = 23,
                onValueChangeFinished = { model.updateSettings(model.settings.copy(fontSize = size.toInt())) })
            Text("终端内也可按音量 ＋ / − 调整，每次 1 sp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SettingsGroup("预览") { TerminalFontPreview(model, size) }
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
private fun TerminalFontPreview(model: ShellDeckModel, size: Float) {
    val theme = model.settings.terminalTheme ?: TerminalTheme.preset(model.settings.palette)
    Surface(color = Color(theme.colors[17]), contentColor = Color(theme.colors[16])) {
        Text("Aa 0123 [] {} <>\n中文等宽测试  ┌─┬─┐\n图标：\uE0B0 \uF120 \uF07B", Modifier.fillMaxWidth().padding(16.dp),
            fontFamily = FontFamily(model.typeface), fontSize = size.sp)
    }
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
    val types = listOf("字符", "特殊键", "修饰键", "转义序列", "宏", "键盘开关", "Prompt 编辑器")
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var width by rememberSaveable { mutableIntStateOf(initial.width) }
    var type by rememberSaveable { mutableStateOf(when(initial.action) {
        is KeyAction.Character -> "字符"; is KeyAction.Special -> "特殊键"; is KeyAction.Modifier -> "修饰键"
        is KeyAction.EscapeSequence -> "转义序列"; is KeyAction.Macro -> "宏"; KeyAction.OpenPrompt -> "Prompt 编辑器"; else -> "键盘开关"
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
            "Prompt 编辑器" -> KeyAction.OpenPrompt
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
                "Prompt 编辑器" -> Text("打开当前会话的多行草稿")
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

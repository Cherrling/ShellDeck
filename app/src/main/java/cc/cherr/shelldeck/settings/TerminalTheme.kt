package cc.cherr.shelldeck.settings

import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Properties

/** Opaque sRGB only: alpha would make OSC answers disagree with displayed colors. */
data class TerminalTheme(val colors: List<Int>) {
    init { require(colors.size == 21 && colors.all { it ushr 24 == 255 }) { "需要 21 个不透明 RGB 颜色。" } }
    fun color(key: String) = colors[KEYS.indexOf(key).also { require(it >= 0) }]
    fun withColor(index: Int, value: Int) = TerminalTheme(colors.mapIndexed { i, old -> if (i == index) value else old })
    fun properties() = Properties().apply { KEYS.take(19).forEachIndexed { i, key -> setProperty(key, hex(colors[i])) } }
    fun json(): JSONObject = JSONObject().put("format", "shelldeck-terminal-theme").put("version", 1)
        .put("colors", JSONArray(colors.map(::hex)))
    companion object {
        val KEYS = (0..15).map { "color$it" } + listOf("foreground", "background", "cursor", "selection-foreground", "selection-background")
        val LABELS = listOf("黑", "红", "绿", "黄", "蓝", "品红", "青", "白").let { it + it.map { name -> "亮$name" } } +
            listOf("前景", "背景", "光标", "选区文字", "选区背景")
        const val MAX_BYTES = 32 * 1024
        fun hex(value: Int) = "#%06X".format(java.util.Locale.ROOT, value and 0xffffff)
        fun parse(value: String): Int {
            val v = value.trim()
            val hex = when {
                v.matches(Regex("#[0-9a-fA-F]{6}")) -> v.drop(1)
                v.matches(Regex("#[0-9a-fA-F]{3}")) -> v.drop(1).flatMap { listOf(it, it) }.joinToString("")
                else -> throw IllegalArgumentException("颜色请使用 #RRGGBB 或 #RGB。")
            }
            return (0xff000000L or hex.toLong(16)).toInt()
        }
        fun preset(palette: TerminalPalette): TerminalTheme {
            val defaults = TerminalColorScheme().mDefaultColors
            val fg = if (palette == TerminalPalette.LIGHT) 0xff202020.toInt() else defaults[TextStyle.COLOR_INDEX_FOREGROUND]
            val bg = if (palette == TerminalPalette.LIGHT) 0xffffffff.toInt() else defaults[TextStyle.COLOR_INDEX_BACKGROUND]
            return TerminalTheme(defaults.take(16) + listOf(fg, bg, fg, bg, fg))
        }
        fun fromJson(root: JSONObject): TerminalTheme {
            require(root.optString("format") == "shelldeck-terminal-theme" && root.get("version") == 1) { "不支持的配色文件版本。" }
            val array = root.getJSONArray("colors")
            require(array.length() == KEYS.size) { "配色需要 16 个 ANSI 色及前景、背景、光标、选区文字和背景。" }
            return TerminalTheme(List(array.length()) { i -> require(array.get(i) is String); parse(array.getString(i)) })
        }
        fun decode(text: String, base: TerminalTheme = preset(TerminalPalette.DARK)): TerminalTheme {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配色文件不能超过 32 KiB。" }
            val source = text.removePrefix("\uFEFF").trim()
            if (source.startsWith("{")) {
                // Reject deep/unrelated documents before invoking the recursive JSON parser.
                var depth = 0; var quoted = false; var escaped = false
                source.forEach { c -> if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when(c) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 4) { "配色文件结构无效。" } }
                    '}', ']' -> { depth--; require(depth >= 0) }
                } }
                require(depth == 0 && !quoted)
                return fromJson(JSONObject(source))
            }
            val properties = Properties().apply { source.reader().use { load(it) } }
            require(properties.isNotEmpty()) { "配色文件为空。" }
            var result = base
            for (key in properties.stringPropertyNames()) {
                val index = KEYS.indexOf(key)
                require(index >= 0) { "不支持的颜色字段：${key.take(40)}（支持 color0–color15）。" }
                result = result.withColor(index, parse(properties.getProperty(key)))
            }
            // Termux themes commonly omit cursor; use the imported foreground for visibility.
            if (!properties.containsKey("cursor")) result = result.withColor(18, result.colors[16])
            return result
        }
        fun read(input: InputStream, base: TerminalTheme): TerminalTheme {
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                if (n == 0) continue
                count += n
            }
            val bytes = buffer.copyOf(count)
            require(bytes.size <= MAX_BYTES) { "配色文件不能超过 32 KiB。" }
            return decode(bytes.toString(Charsets.UTF_8), base)
        }
    }
}

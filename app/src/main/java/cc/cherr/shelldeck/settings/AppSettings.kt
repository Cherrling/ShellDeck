package cc.cherr.shelldeck.settings

import android.content.Context
import androidx.core.content.edit
import cc.cherr.shelldeck.keyboard.*
import org.json.JSONArray
import org.json.JSONObject

enum class BackgroundMode { OFF, NORMAL, ONGOING }

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TerminalPalette { DARK, LIGHT }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true,
    val fontId: String = "maple", val fontSize: Int = 14, val palette: TerminalPalette = TerminalPalette.DARK,
    val keyboard: KeyboardProfile = KeyboardProfile.default(), val keyboardSizing: KeyboardSizing = KeyboardSizing(),
    val backgroundMode: BackgroundMode = BackgroundMode.NORMAL, val terminalTheme: TerminalTheme? = null, val keepAliveSeconds: Int = 60)

/** Non-secret preferences only. SSH credentials never enter this store. */
class SettingsStore(context: Context, name: String = "appearance_and_keyboard") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read(): AppSettings = try { decode(prefs.getString("settings", "{}")!!, strict = false) } catch (_: Exception) { AppSettings() }
    fun save(settings: AppSettings) { prefs.edit { putString("settings", encode(settings)) } }
    @android.annotation.SuppressLint("UseKtx") // KTX edit returns Unit; restore must inspect the disk commit result.
    fun saveRestored(settings: AppSettings): Boolean = prefs.edit().putString("settings", encode(settings)).commit()
    companion object {
        fun decode(text: String, strict: Boolean = true): AppSettings {
            if (strict) {
                require(text.length <= 1024 * 1024)
                var depth = 0; var quoted = false; var escaped = false
                text.forEach { char ->
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (char == '\\') escaped = true
                        else if (char == '"') quoted = false
                    } else when (char) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; require(depth <= 16) }
                        '}', ']' -> { depth--; require(depth >= 0) }
                    }
                }
                require(!quoted && depth == 0)
            }
            val root = JSONObject(text)
            if (strict) {
                require(root.get("version") in listOf(1, 2) && root.get("size") is Int && root.get("dynamic") is Boolean)
                listOf("theme", "font", "palette", "backgroundMode").forEach { require(root.get(it) is String) }
                require(root.getJSONArray("keyboard").length() == 2)
                val sizing = root.getJSONObject("keyboardSizing")
                require(sizing.get("height") is Int && sizing.get("visibleKeys") is Int)
            }
            return AppSettings(ThemeMode.valueOf(root.optString("theme", "SYSTEM")), root.optBoolean("dynamic", true),
                root.optString("font", "maple"), root.optInt("size", 14).let { if (strict) it else it.coerceIn(8, 32) },
                TerminalPalette.valueOf(root.optString("palette", "DARK")),
                root.optJSONArray("keyboard")?.let(::decodeKeyboard) ?: KeyboardProfile.default(),
                root.optJSONObject("keyboardSizing")?.let { KeyboardSizing(it.optInt("height", 38).let { n -> if (strict) n else n.coerceIn(28, 56) },
                    it.optInt("visibleKeys", 7).let { n -> if (strict) n else n.coerceIn(4, 12) }) } ?: KeyboardSizing(),
                if (strict) BackgroundMode.valueOf(root.optString("backgroundMode", "NORMAL"))
                else BackgroundMode.entries.firstOrNull { it.name == root.optString("backgroundMode") } ?: BackgroundMode.NORMAL,
                if (root.has("terminalTheme") && !root.isNull("terminalTheme")) TerminalTheme.fromJson(root.getJSONObject("terminalTheme")) else null,
                root.optInt("keepAliveSeconds", 60).also { require(it in listOf(0, 60, 120)) })
                .also { require(it.fontSize in 8..32 && it.fontId.length <= 128); it.keyboard.validate(); it.keyboardSizing.validate() }
        }
        fun encode(settings: AppSettings): String {
            settings.keyboard.validate(); settings.keyboardSizing.validate(); require(settings.fontSize in 8..32 && settings.keepAliveSeconds in listOf(0, 60, 120))
            return JSONObject().put("version", 2).put("theme", settings.theme.name).put("dynamic", settings.dynamicColor)
                .put("keepAliveSeconds", settings.keepAliveSeconds)
                .put("terminalTheme", settings.terminalTheme?.json() ?: JSONObject.NULL)
                .put("font", settings.fontId).put("size", settings.fontSize).put("palette", settings.palette.name)
                .put("backgroundMode", settings.backgroundMode.name).put("keyboard", encodeKeyboard(settings.keyboard))
                .put("keyboardSizing", JSONObject().put("height", settings.keyboardSizing.rowHeight).put("visibleKeys", settings.keyboardSizing.visibleKeys)).toString()
        }

        fun encodeKeyboard(profile: KeyboardProfile) = JSONArray().apply {
            profile.rows.forEach { row -> put(JSONArray().apply { row.forEach { slot ->
                val item = JSONObject().put("label", slot.label).put("width", slot.width)
                when (val a = slot.action) {
                    is KeyAction.Character -> item.put("type", "character").put("value", a.text)
                    is KeyAction.Special -> item.put("type", "special").put("value", a.key.name)
                        .put("modifiers", JSONArray(a.modifiers.map { it.name }))
                    is KeyAction.Modifier -> item.put("type", "modifier").put("value", a.key.name)
                    is KeyAction.EscapeSequence -> item.put("type", "escape").put("value", a.sequence)
                    is KeyAction.Macro -> item.put("type", "macro").put("value", a.text)
                    KeyAction.OpenPrompt -> item.put("type", "prompt")
                    KeyAction.ToggleKeyboard -> item.put("type", "keyboard")
                }
                put(item)
            } }) }
        }
        fun decodeKeyboard(rows: JSONArray) = KeyboardProfile(List(rows.length()) { r ->
            val row = rows.getJSONArray(r).also { require(it.length() in 1..32) }
            List(row.length()) { c ->
                val slot = row.getJSONObject(c)
                val value = slot.optString("value")
                val action = when (slot.getString("type")) {
                    "character" -> KeyAction.Character(value)
                    "special" -> KeyAction.Special(SpecialKey.valueOf(value), slot.optJSONArray("modifiers")?.let { mods ->
                        List(mods.length()) { ModifierKey.valueOf(mods.getString(it)) }.toSet()
                    } ?: emptySet())
                    "modifier" -> KeyAction.Modifier(ModifierKey.valueOf(value))
                    "escape" -> KeyAction.EscapeSequence(value)
                    "macro" -> KeyAction.Macro(value)
                    "prompt" -> KeyAction.OpenPrompt
                    "keyboard" -> KeyAction.ToggleKeyboard
                    else -> error("Unknown action")
                }
                KeySlot(slot.getString("label"), action, slot.optInt("width", 1))
            }
        }).also { it.validate() }
    }
}

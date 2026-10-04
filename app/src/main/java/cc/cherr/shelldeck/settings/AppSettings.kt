package cc.cherr.shelldeck.settings

import android.content.Context
import androidx.core.content.edit
import cc.cherr.shelldeck.keyboard.*
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TerminalPalette { DARK, LIGHT }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true,
    val fontId: String = "maple", val fontSize: Int = 14, val palette: TerminalPalette = TerminalPalette.DARK,
    val keyboard: KeyboardProfile = KeyboardProfile.default())

/** Only appearance/input preferences. SSH credentials never enter this store. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance_and_keyboard", Context.MODE_PRIVATE)
    fun read(): AppSettings = try {
        val root = JSONObject(prefs.getString("settings", "{}")!!)
        AppSettings(ThemeMode.valueOf(root.optString("theme", "SYSTEM")), root.optBoolean("dynamic", true),
            root.optString("font", "maple"), root.optInt("size", 14).coerceIn(8, 32),
            TerminalPalette.valueOf(root.optString("palette", "DARK")),
            root.optJSONArray("keyboard")?.let(::decodeKeyboard) ?: KeyboardProfile.default())
    } catch (_: Exception) { AppSettings() }
    fun save(settings: AppSettings) {
        settings.keyboard.validate()
        require(settings.fontSize in 8..32)
        val root = JSONObject().put("version", 1).put("theme", settings.theme.name).put("dynamic", settings.dynamicColor)
            .put("font", settings.fontId).put("size", settings.fontSize).put("palette", settings.palette.name)
            .put("keyboard", encodeKeyboard(settings.keyboard))
        prefs.edit { putString("settings", root.toString()) }
    }
    companion object {
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
                    KeyAction.ToggleKeyboard -> item.put("type", "keyboard")
                }
                put(item)
            } }) }
        }
        fun decodeKeyboard(rows: JSONArray) = KeyboardProfile(List(rows.length()) { r ->
            val row = rows.getJSONArray(r)
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
                    "keyboard" -> KeyAction.ToggleKeyboard
                    else -> error("Unknown action")
                }
                KeySlot(slot.getString("label"), action, slot.optInt("width", 1))
            }
        }).also { it.validate() }
    }
}

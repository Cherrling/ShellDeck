package cc.cherr.shelldeck.keyboard

import android.view.KeyEvent

enum class ModifierKey { CTRL, ALT, SHIFT }
enum class SpecialKey(val code: Int) {
    ESC(KeyEvent.KEYCODE_ESCAPE), TAB(KeyEvent.KEYCODE_TAB), ENTER(KeyEvent.KEYCODE_ENTER),
    BACKSPACE(KeyEvent.KEYCODE_DEL), UP(KeyEvent.KEYCODE_DPAD_UP), DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT), RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME(KeyEvent.KEYCODE_MOVE_HOME), END(KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP(KeyEvent.KEYCODE_PAGE_UP), PAGE_DOWN(KeyEvent.KEYCODE_PAGE_DOWN),
    DELETE(KeyEvent.KEYCODE_FORWARD_DEL), F1(KeyEvent.KEYCODE_F1), F2(KeyEvent.KEYCODE_F2),
    F3(KeyEvent.KEYCODE_F3), F4(KeyEvent.KEYCODE_F4), F5(KeyEvent.KEYCODE_F5), F6(KeyEvent.KEYCODE_F6),
    F7(KeyEvent.KEYCODE_F7), F8(KeyEvent.KEYCODE_F8), F9(KeyEvent.KEYCODE_F9), F10(KeyEvent.KEYCODE_F10),
    F11(KeyEvent.KEYCODE_F11), F12(KeyEvent.KEYCODE_F12)
}
sealed interface KeyAction {
    data class Character(val text: String) : KeyAction
    data class Special(val key: SpecialKey, val modifiers: Set<ModifierKey> = emptySet()) : KeyAction
    data class Modifier(val key: ModifierKey) : KeyAction
    data class EscapeSequence(val sequence: String) : KeyAction
    data class Macro(val text: String) : KeyAction
    data object ToggleKeyboard : KeyAction
}
data class KeySlot(val label: String, val action: KeyAction, val width: Int = 1)
data class KeyboardProfile(val rows: List<List<KeySlot>>) {
    fun validate() {
        require(rows.size == 2 && rows.all { it.size in 1..32 })
        rows.flatten().forEach {
            require(it.label.isNotBlank() && it.label.length <= 24 && it.width in 1..3)
            val text = when (val action = it.action) {
                is KeyAction.Character -> action.text
                is KeyAction.EscapeSequence -> action.sequence
                is KeyAction.Macro -> action.text
                else -> null
            }
            if (text != null) require(text.isNotEmpty() && text.length <= 4096)
            if (it.action is KeyAction.Character) require(it.action.text.codePointCount(0, it.action.text.length) == 1 && it.action.text.codePointAt(0) !in 0xD800..0xDFFF)
        }
    }
    companion object {
        fun default() = KeyboardProfile(listOf(
            listOf(KeySlot("ESC", KeyAction.Special(SpecialKey.ESC)),
                KeySlot("/", KeyAction.Character("/")), KeySlot("-", KeyAction.Character("-")),
                KeySlot("PGUP", KeyAction.Special(SpecialKey.PAGE_UP)), KeySlot("↑", KeyAction.Special(SpecialKey.UP)),
                KeySlot("PGDN", KeyAction.Special(SpecialKey.PAGE_DOWN)), KeySlot("SHIFT", KeyAction.Modifier(ModifierKey.SHIFT))),
            listOf(KeySlot("TAB", KeyAction.Special(SpecialKey.TAB)), KeySlot("CTRL", KeyAction.Modifier(ModifierKey.CTRL)),
                KeySlot("ALT", KeyAction.Modifier(ModifierKey.ALT)), KeySlot("←", KeyAction.Special(SpecialKey.LEFT)),
                KeySlot("↓", KeyAction.Special(SpecialKey.DOWN)), KeySlot("→", KeyAction.Special(SpecialKey.RIGHT)),
                KeySlot("键盘", KeyAction.ToggleKeyboard))))
    }
}

/** Physical toolbar dimensions, independent of the terminal's font size. */
data class KeyboardSizing(val rowHeight: Int = 38, val visibleKeys: Int = 7) {
    fun validate() { require(rowHeight in 28..56 && visibleKeys in 4..12) }
}

data class KeyPosition(val row: Int, val index: Int)

/** Destination is an insertion gap in the original row, not a swap target. */
fun KeyboardProfile.moveKey(from: KeyPosition, to: KeyPosition): KeyboardProfile {
    if (from.row !in rows.indices || to.row !in rows.indices || from.index !in rows[from.row].indices || to.index !in 0..rows[to.row].size) return this
    if (from.row != to.row && (rows[from.row].size <= 1 || rows[to.row].size >= 32)) return this
    val copy = rows.map { it.toMutableList() }
    val key = copy[from.row].removeAt(from.index)
    val insertion = to.index - if (from.row == to.row && to.index > from.index) 1 else 0
    copy[to.row].add(insertion, key)
    return KeyboardProfile(copy).also { it.validate() }
}

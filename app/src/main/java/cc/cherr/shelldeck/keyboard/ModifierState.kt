package cc.cherr.shelldeck.keyboard

import androidx.compose.runtime.mutableStateMapOf

/** Read operations never consume modifiers: TerminalView may read them repeatedly for one input. */
class ModifierState {
    enum class Latch { OFF, ONCE, LOCKED }
    private val latch = mutableStateMapOf<ModifierKey, Latch>()
    private val pressed = mutableStateMapOf<ModifierKey, Boolean>()
    private val used = mutableSetOf<ModifierKey>()
    fun mode(key: ModifierKey) = latch[key] ?: Latch.OFF
    fun active(key: ModifierKey) = pressed[key] == true || mode(key) != Latch.OFF
    fun press(key: ModifierKey) { pressed[key] = true; used.remove(key) }
    fun release(key: ModifierKey, longPress: Boolean) {
        val wasUsed = used.remove(key)
        pressed.remove(key)
        if (!wasUsed) latch[key] = when {
            mode(key) != Latch.OFF -> Latch.OFF
            longPress -> Latch.LOCKED
            else -> Latch.ONCE
        }
    }
    fun cancel(key: ModifierKey) { pressed.remove(key); used.remove(key) }
    fun consumed() {
        pressed.keys.forEach { used.add(it) }
        latch.keys.toList().forEach { if (latch[it] == Latch.ONCE) latch[it] = Latch.OFF }
    }
    fun clear() { latch.clear(); pressed.clear(); used.clear() }
}

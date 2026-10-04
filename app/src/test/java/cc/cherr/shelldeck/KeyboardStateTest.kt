package cc.cherr.shelldeck

import cc.cherr.shelldeck.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardStateTest {
    @Test fun onceIsNotConsumedByRepeatedReads() {
        val state = ModifierState(); val key = ModifierKey.SHIFT
        state.press(key); state.release(key, false)
        repeat(4) { assertTrue(state.active(key)) }
        state.consumed(); assertFalse(state.active(key))
    }
    @Test fun heldModifierSurvivesMultipleInputsAndReleasesWithoutLatching() {
        val state = ModifierState(); val key = ModifierKey.SHIFT
        state.press(key)
        repeat(3) { state.consumed(); assertTrue(state.active(key)) }
        state.release(key, true)
        assertFalse(state.active(key)); assertEquals(ModifierState.Latch.OFF, state.mode(key))
    }
    @Test fun lockedModifierSurvivesInputUntilTappedAgain() {
        val state = ModifierState(); val key = ModifierKey.CTRL
        state.press(key); state.release(key, true)
        repeat(3) { state.consumed(); assertTrue(state.active(key)) }
        assertEquals(ModifierState.Latch.LOCKED, state.mode(key))
        state.press(key); state.release(key, false); assertFalse(state.active(key))
    }
    @Test fun cancelledGestureDoesNotLatchAndLeavingClearsAllModifiers() {
        val state = ModifierState()
        state.press(ModifierKey.ALT); state.cancel(ModifierKey.ALT)
        assertFalse(state.active(ModifierKey.ALT))
        ModifierKey.entries.forEach { state.press(it); state.release(it, true) }
        state.clear(); ModifierKey.entries.forEach { assertFalse(state.active(it)) }
    }
    @Test fun modifiersAreIndependentBetweenSessions() {
        val first = ModifierState(); val second = ModifierState()
        first.press(ModifierKey.SHIFT)
        assertTrue(first.active(ModifierKey.SHIFT)); assertFalse(second.active(ModifierKey.SHIFT))
    }
    @Test fun profileRejectsInvalidRowsAndOversizedActions() {
        KeyboardProfile.default().validate()
        assertThrows(IllegalArgumentException::class.java) { KeyboardProfile(listOf(emptyList(), emptyList())).validate() }
        val slot = KeySlot("bad", KeyAction.Macro("a".repeat(4097)))
        assertThrows(IllegalArgumentException::class.java) { KeyboardProfile(listOf(listOf(slot), listOf(slot))).validate() }
    }
}

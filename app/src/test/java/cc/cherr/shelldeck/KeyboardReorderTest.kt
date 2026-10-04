package cc.cherr.shelldeck

import cc.cherr.shelldeck.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardReorderTest {
    private fun key(label: String, width: Int = 1) = KeySlot(label, KeyAction.Macro(label), width)
    private val profile = KeyboardProfile(listOf(listOf(key("A"), key("B", 2), key("C")), listOf(key("X"), key("Y"))))
    @Test fun reorderingInsertsRatherThanSwapsAndSupportsEnd() {
        assertEquals(listOf("B", "C", "A"), profile.moveKey(KeyPosition(0, 0), KeyPosition(0, 3)).rows[0].map { it.label })
        assertEquals(listOf("C", "A", "B"), profile.moveKey(KeyPosition(0, 2), KeyPosition(0, 0)).rows[0].map { it.label })
        assertEquals(profile, profile.moveKey(KeyPosition(0, 1), KeyPosition(0, 2)))
    }
    @Test fun crossRowPreservesActionWidthAndDuplicateLabels() {
        val duplicate = profile.copy(rows = listOf(listOf(key("A"), key("A", 2), key("C")), profile.rows[1]))
        val moved = duplicate.moveKey(KeyPosition(0, 1), KeyPosition(1, 1))
        assertEquals(duplicate.rows[0][1], moved.rows[1][1])
        assertEquals(listOf("X", "A", "Y"), moved.rows[1].map { it.label })
        assertEquals(5, moved.rows.sumOf { it.size })
    }
    @Test fun refusesEmptyRowsFullDestinationsAndInvalidPositions() {
        val last = profile.copy(rows = listOf(listOf(key("A")), profile.rows[1]))
        assertEquals(last, last.moveKey(KeyPosition(0, 0), KeyPosition(1, 0)))
        val full = profile.copy(rows = listOf(profile.rows[0], List(32) { key("$it") }))
        assertEquals(full, full.moveKey(KeyPosition(0, 0), KeyPosition(1, 1)))
        assertEquals(profile, profile.moveKey(KeyPosition(0, 9), KeyPosition(1, 0)))
    }
    @Test fun sizingHasCompactDefaultAndValidatedLimits() {
        assertEquals(38, KeyboardSizing().rowHeight)
        KeyboardSizing(28, 32, 10).validate()
        KeyboardSizing(56, 80, 18).validate()
        assertThrows(IllegalArgumentException::class.java) { KeyboardSizing(10).validate() }
    }
}

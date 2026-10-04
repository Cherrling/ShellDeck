package cc.cherr.shelldeck

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.cherr.shelldeck.keyboard.*
import cc.cherr.shelldeck.ui.ShellDeckTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardDragDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun key(text: String, width: Int = 1) = KeySlot(text, KeyAction.Macro(text), width)
    @Test fun dragAcrossRowsPreservesWidthAndDroppingOutsideCancels() {
        val profile = mutableStateOf(KeyboardProfile(listOf(listOf(key("A"), key("B", 2), key("C")), listOf(key("X"), key("Y")))))
        var edited: KeyPosition? = null
        ui.runOnUiThread { ui.activity.setContent { ShellDeckTheme { Column(Modifier.fillMaxSize()) {
            KeyboardLayoutEditor(profile.value, KeyboardSizing(), { edited = it }, {}, { from, to -> profile.value = profile.value.moveKey(from, to) })
        } } } }
        val from = ui.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.center
        val y = ui.onNodeWithText("Y").fetchSemanticsNode().boundsInRoot
        ui.onRoot().performTouchInput { down(from); advanceEventTime(650); moveTo(Offset(y.left + 2, y.center.y), delayMillis = 150); up() }
        ui.runOnIdle {
            assertEquals(listOf("A", "C"), profile.value.rows[0].map { it.label })
            assertEquals(listOf("X", "B", "Y"), profile.value.rows[1].map { it.label })
            assertEquals(2, profile.value.rows[1][1].width)
            assertNull(edited)
        }
        val before = profile.value
        val b = ui.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.center
        val area = ui.onNodeWithTag("keyboard-drag-area").fetchSemanticsNode().boundsInRoot
        ui.onRoot().performTouchInput { down(b); advanceEventTime(650); moveTo(Offset(b.x, area.bottom + 100), delayMillis = 150); up() }
        ui.runOnIdle { assertEquals(before, profile.value) }
        ui.onNodeWithText("C").performClick()
        ui.runOnIdle { assertEquals(KeyPosition(0, 1), edited) }
    }
    @Test fun draggingAtTheEdgeScrollsToOffscreenKeys() {
        val profile = mutableStateOf(KeyboardProfile(listOf(List(20) { key("K$it") }, listOf(key("X"), key("Y")))))
        ui.runOnUiThread { ui.activity.setContent { ShellDeckTheme { Column(Modifier.fillMaxSize()) {
            KeyboardLayoutEditor(profile.value, KeyboardSizing(), {}, {}, { from, to -> profile.value = profile.value.moveKey(from, to) })
        } } } }
        val from = ui.onNodeWithText("K0", substring = false).fetchSemanticsNode().boundsInRoot.center
        val area = ui.onNodeWithTag("keyboard-drag-area").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            ui.onRoot().performTouchInput { down(from); advanceEventTime(650); moveTo(Offset(area.right - 2, from.y), delayMillis = 150) }
            ui.mainClock.advanceTimeBy(2500)
            ui.onRoot().performTouchInput { up() }
        } finally { ui.mainClock.autoAdvance = true }
        ui.runOnIdle {
            assertEquals("K0", profile.value.rows[0].last().label)
            assertEquals(20, profile.value.rows[0].size)
        }
    }
}

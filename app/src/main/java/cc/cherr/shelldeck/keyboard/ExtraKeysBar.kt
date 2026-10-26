package cc.cherr.shelldeck.keyboard

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import cc.cherr.shelldeck.TerminalController

@Composable
fun ExtraKeysBar(profile: KeyboardProfile, controller: TerminalController, sizing: KeyboardSizing = KeyboardSizing(), side: Boolean = false) {
    BoxWithConstraints(if (side) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContentColor provides Color(controller.foregroundColor)) {
            if (side) {
                val keyWidth = maxWidth / profile.rows.size
                // Keep keys usable when the IME leaves a short viewport; overflow scrolls vertically.
                val keyHeight = maxOf(sizing.rowHeight.dp, maxHeight / sizing.visibleKeys)
                Row(Modifier.fillMaxSize().testTag("extra-keys-bar").verticalScroll(rememberScrollState())) {
                    profile.rows.forEach { row -> Column {
                        row.forEach { slot ->
                            ExtraKey(slot, controller, Modifier.width(keyWidth).height(keyHeight * slot.width))
                        }
                    } }
                }
            } else {
                val keyWidth = maxWidth / sizing.visibleKeys
                Column(Modifier.testTag("extra-keys-bar").fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    profile.rows.forEach { row -> Row {
                        row.forEach { slot ->
                            ExtraKey(slot, controller, Modifier.width(keyWidth * slot.width).height(sizing.rowHeight.dp))
                        }
                    } }
                }
            }
        }
    }
}

@Composable
private fun ExtraKey(slot: KeySlot, controller: TerminalController, modifier: Modifier) {
    val modifierAction = slot.action as? KeyAction.Modifier
    if (modifierAction == null) Box(modifier.clickable(role = Role.Button) { controller.perform(slot.action) }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(slot.label, Modifier.padding(horizontal = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
    }
    else {
        val state = controller.modifiers
        val active = state.active(modifierAction.key)
        val locked = state.mode(modifierAction.key) == ModifierState.Latch.LOCKED
        val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
        Surface(color = if (active) Color(controller.foregroundColor).copy(alpha = 0.2f).compositeOver(Color(controller.backgroundColor)) else Color(controller.backgroundColor),
            contentColor = Color(controller.foregroundColor),
            modifier = modifier.semantics {
                role = Role.Button
                contentDescription = slot.label
                stateDescription = if (locked) "已锁定" else if (active) "已按下" else "未启用"
                onClick { state.press(modifierAction.key); state.release(modifierAction.key, false); true }
                onLongClick { state.press(modifierAction.key); state.release(modifierAction.key, true); true }
            }.pointerInput(state, modifierAction.key) {
                detectTapGestures(onPress = {
                    val start = SystemClock.uptimeMillis()
                    state.press(modifierAction.key)
                    try {
                        if (tryAwaitRelease()) state.release(modifierAction.key, SystemClock.uptimeMillis() - start >= timeout)
                    } finally { state.cancel(modifierAction.key) }
                })
            }) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(slot.label, Modifier.padding(horizontal = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                if (active) Text(if (locked) "◆" else "•", Modifier.align(androidx.compose.ui.Alignment.TopEnd), fontSize = 8.sp)
            }
        }
    }
}

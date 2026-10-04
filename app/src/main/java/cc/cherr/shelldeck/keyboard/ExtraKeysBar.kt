package cc.cherr.shelldeck.keyboard

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.cherr.shelldeck.TerminalController

@Composable
fun ExtraKeysBar(profile: KeyboardProfile, controller: TerminalController) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        profile.rows.forEach { row ->
            Row {
                row.forEach { slot ->
                    val modifier = slot.action as? KeyAction.Modifier
                    val size = Modifier.width((64 * slot.width).dp).height(48.dp)
                    if (modifier == null) TextButton(onClick = { controller.perform(slot.action) }, modifier = size) { Text(slot.label, maxLines = 1) }
                    else {
                        val state = controller.modifiers
                        val active = state.active(modifier.key)
                        val locked = state.mode(modifier.key) == ModifierState.Latch.LOCKED
                        val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                        Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = size.semantics {
                                role = Role.Button
                                contentDescription = slot.label
                                stateDescription = if (locked) "已锁定" else if (active) "已按下" else "未启用"
                                onClick { state.press(modifier.key); state.release(modifier.key, false); true }
                                onLongClick { state.press(modifier.key); state.release(modifier.key, true); true }
                            }.pointerInput(state, modifier.key) {
                                detectTapGestures(onPress = {
                                    val start = SystemClock.uptimeMillis()
                                    state.press(modifier.key)
                                    try {
                                        if (tryAwaitRelease()) state.release(modifier.key, SystemClock.uptimeMillis() - start >= timeout)
                                    } finally { state.cancel(modifier.key) }
                                })
                            }) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(slot.label + if (locked) " ◆" else if (active) " ●" else "", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

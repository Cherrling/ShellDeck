package cc.cherr.shelldeck.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.cherr.shelldeck.keyboard.KeyboardProfile
import cc.cherr.shelldeck.keyboard.KeyboardSizing
import kotlin.math.roundToInt

@Composable
internal fun KeyboardSizeSettings(profile: KeyboardProfile, saved: KeyboardSizing, save: (KeyboardSizing) -> Unit) {
    var size by remember(saved) { mutableStateOf(saved) }
    Text("快捷键大小", style = MaterialTheme.typography.titleMedium)
    Row {
        TextButton(onClick = { size = KeyboardSizing(); save(size) }) { Text("紧凑") }
        TextButton(onClick = { size = KeyboardSizing(48, 64, 14); save(size) }) { Text("宽松") }
    }
    Text("行高：${size.rowHeight} dp")
    Slider(size.rowHeight.toFloat(), { size = size.copy(rowHeight = it.roundToInt()) }, valueRange = 28f..56f, steps = 27,
        onValueChangeFinished = { save(size) }, modifier = Modifier.testTag("key-height-slider"))
    Text("按键宽度：${size.keyWidth} dp")
    Slider(size.keyWidth.toFloat(), { size = size.copy(keyWidth = it.roundToInt()) }, valueRange = 32f..80f, steps = 47,
        onValueChangeFinished = { save(size) }, modifier = Modifier.testTag("key-width-slider"))
    Text("按键文字：${size.textSize} sp")
    Slider(size.textSize.toFloat(), { size = size.copy(textSize = it.roundToInt()) }, valueRange = 10f..18f, steps = 7,
        onValueChangeFinished = { save(size) }, modifier = Modifier.testTag("key-text-slider"))
    Text("预览（可左右滑动）", style = MaterialTheme.typography.bodySmall)
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("key-size-preview")) {
            profile.rows.forEach { row -> Row {
                row.forEach { key -> Box(Modifier.width((size.keyWidth * key.width).dp).height(size.rowHeight.dp), contentAlignment = Alignment.Center) {
                    Text(key.label, Modifier.padding(horizontal = 2.dp), fontSize = size.textSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
            } }
        }
    }
}

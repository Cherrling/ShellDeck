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
    Text("行高：${size.rowHeight} dp")
    Slider(size.rowHeight.toFloat(), { size = size.copy(rowHeight = it.roundToInt()) }, valueRange = 28f..56f, steps = 27,
        onValueChangeFinished = { save(size) }, modifier = Modifier.testTag("key-height-slider"))
    Text("每行／列显示：${size.visibleKeys} 个")
    Slider(size.visibleKeys.toFloat(), { size = size.copy(visibleKeys = it.roundToInt()) }, valueRange = 4f..12f, steps = 7,
        onValueChangeFinished = { save(size) }, modifier = Modifier.testTag("key-count-slider"))
    Text("底部左右滑动，侧边上下滑动；侧边按键不低于设定行高，空间不足时滑动查看更多。加宽按键在侧边对应加高。", style = MaterialTheme.typography.bodySmall)
    Text("底部布局预览（可左右滑动）", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val keyWidth = with(androidx.compose.ui.platform.LocalDensity.current) { (constraints.maxWidth / size.visibleKeys).toDp() }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.width(keyWidth * size.visibleKeys).horizontalScroll(rememberScrollState()).testTag("key-size-preview")) {
                profile.rows.forEach { row -> Row {
                    row.forEach { key -> Box(Modifier.width(keyWidth * key.width).height(size.rowHeight.dp), contentAlignment = Alignment.Center) {
                        Text(key.label, Modifier.padding(horizontal = 2.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }
                } }
            }
        }
    }
}

package cc.cherr.shelldeck.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Reorder only on drop: cancellation preserves the draft and positions never jump under the finger. */
@Composable
internal fun KeyboardLayoutEditor(
    profile: KeyboardProfile,
    sizing: KeyboardSizing,
    edit: (KeyPosition) -> Unit,
    add: (Int) -> Unit,
    move: (KeyPosition, KeyPosition) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val width = with(density) { sizing.keyWidth.dp.toPx() }
    val height = with(density) { sizing.rowHeight.dp.toPx() }
    val gap = with(density) { 16.dp.toPx() }
    val edge = with(density) { 36.dp.toPx() }
    val speed = with(density) { 420.dp.toPx() }
    val current by rememberUpdatedState(profile)
    val currentMove by rememberUpdatedState(move)
    var viewport by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf<KeyPosition?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    fun rowAt(y: Float): Int? = when {
        y in 0f..height -> 0
        y in (height + gap)..(2 * height + gap) -> 1
        else -> null
    }
    fun leftOf(position: KeyPosition) = current.rows[position.row].take(position.index).sumOf { it.width } * width
    fun target(): KeyPosition? {
        val from = source ?: return null
        if (pointer.x !in 0f..viewport.toFloat()) return null
        val row = rowAt(pointer.y) ?: return null
        if (row != from.row && (current.rows[from.row].size == 1 || current.rows[row].size == 32)) return null
        val x = pointer.x + scroll.value
        var left = 0f
        current.rows[row].forEachIndexed { index, key ->
            if (x < left + key.width * width / 2) return KeyPosition(row, index)
            left += key.width * width
        }
        return KeyPosition(row, current.rows[row].size)
    }
    // Scroll even while the finger is stationary at an edge. No loop exists outside a drag.
    LaunchedEffect(source != null) {
        if (source == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (source != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
            previous = now
            val direction = when {
                pointer.x < edge -> -((edge - pointer.x) / edge).coerceIn(0f, 1f)
                pointer.x > viewport - edge -> ((pointer.x - viewport + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (direction != 0f) scroll.scrollBy(direction * speed * seconds)
        }
    }
    Box(Modifier.fillMaxWidth().height((sizing.rowHeight * 2 + 16).dp).clipToBounds()
        .testTag("keyboard-drag-area").onSizeChanged { viewport = it.width }
        .pointerInput(width, height, gap) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val row = rowAt(position.y)
                    if (row != null) {
                        val x = position.x + scroll.value
                        var left = 0f
                        val index = current.rows[row].indexOfFirst { key ->
                            val hit = x >= left && x < left + key.width * width
                            left += key.width * width
                            hit
                        }
                        if (index >= 0) {
                            source = KeyPosition(row, index)
                            pointer = position
                            grab = Offset(x - leftOf(source!!), position.y - row * (height + gap))
                        }
                    }
                },
                onDrag = { change, amount -> if (source != null) { change.consume(); pointer += amount } },
                onDragEnd = {
                    val from = source
                    val to = target()
                    source = null
                    if (from != null && to != null) currentMove(from, to)
                },
                onDragCancel = { source = null },
            )
        }) {
        Column(Modifier.horizontalScroll(scroll, enabled = source == null), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            profile.rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEachIndexed { index, slot ->
                        val position = KeyPosition(rowIndex, index)
                        Surface(shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width((sizing.keyWidth * slot.width).dp).height(sizing.rowHeight.dp)
                                .alpha(if (source == position) 0.25f else 1f)
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction("向左移动") { move(position, KeyPosition(rowIndex, index - 1)); true })
                                        if (index < row.lastIndex) add(CustomAccessibilityAction("向右移动") { move(position, KeyPosition(rowIndex, index + 2)); true })
                                        if (row.size > 1 && profile.rows[1 - rowIndex].size < 32) add(CustomAccessibilityAction("移到另一行") { move(position, KeyPosition(1 - rowIndex, profile.rows[1 - rowIndex].size)); true })
                                    }
                                }.clickable { edit(position) }) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(slot.label, Modifier.padding(horizontal = 2.dp), fontSize = sizing.textSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Box(Modifier.width(48.dp).height(sizing.rowHeight.dp).testTag("add-key-$rowIndex")
                        .clickable(enabled = row.size < 32) { add(rowIndex) }, contentAlignment = Alignment.Center) { Text("＋") }
                }
            }
        }
        target()?.let { to ->
            Box(Modifier.offset { IntOffset((leftOf(to) - scroll.value - 1).roundToInt(), (to.row * (height + gap)).roundToInt()) }
                .width(3.dp).height(sizing.rowHeight.dp).background(MaterialTheme.colorScheme.primary))
        }
        source?.let { from ->
            val slot = current.rows[from.row][from.index]
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp), shadowElevation = 6.dp,
                modifier = Modifier.offset { IntOffset((pointer.x - grab.x).roundToInt(), (pointer.y - grab.y).roundToInt()) }
                    .width((sizing.keyWidth * slot.width).dp).height(sizing.rowHeight.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(slot.label, fontSize = sizing.textSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

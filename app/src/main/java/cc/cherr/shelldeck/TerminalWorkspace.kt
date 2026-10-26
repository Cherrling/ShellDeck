package cc.cherr.shelldeck

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.cherr.shelldeck.keyboard.*

/** Keep AndroidView at one composition location when the key bar changes sides. */
@Composable
internal fun TerminalWorkspace(
    terminal: TerminalController,
    profile: KeyboardProfile,
    sizing: KeyboardSizing,
    placement: KeyboardPlacement,
    modifier: Modifier = Modifier,
) {
    // Window orientation, not the terminal's IME-reduced width/height ratio.
    val position = placement.resolve(LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
    val side = position != KeyboardPlacement.BOTTOM
    Layout(modifier = modifier.background(Color(terminal.backgroundColor)).testTag("terminal-workspace"), content = {
        key(terminal) {
            AndroidView(factory = terminal::createView, modifier = Modifier.testTag("terminal-surface"),
                onRelease = terminal::releaseView, update = {})
        }
        ExtraKeysBar(profile, terminal, sizing, side)
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val barWidth = if (side) (maxOf(48, sizing.rowHeight).dp.roundToPx() * profile.rows.size).coerceAtMost(width / 2) else width
        val barHeight = if (side) height else (sizing.rowHeight.dp.roundToPx() * profile.rows.size).coerceAtMost(height)
        val bar = children[1].measure(Constraints.fixed(barWidth, barHeight))
        val surface = children[0].measure(Constraints.fixed(if (side) width - barWidth else width, if (side) height else height - barHeight))
        layout(width, height) {
            surface.place(if (position == KeyboardPlacement.LEFT) barWidth else 0, 0)
            bar.place(if (position == KeyboardPlacement.RIGHT) width - barWidth else 0, if (side) 0 else height - barHeight)
        }
    }
}

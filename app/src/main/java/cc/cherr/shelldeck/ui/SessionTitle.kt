package cc.cherr.shelldeck.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import cc.cherr.shelldeck.TerminalController

/** Event-driven, trailing updates: no timer runs while titles are idle or cards are absent. */
@Composable
internal fun rememberSessionTitle(controller: TerminalController): String {
    var displayed by remember(controller) { mutableStateOf(controller.title) }
    DisposableEffect(controller) {
        val main = Handler(Looper.getMainLooper())
        var pending = false
        val publish = Runnable { pending = false; displayed = controller.title }
        displayed = controller.title
        val unsubscribe = controller.observeTitle {
            if (!pending) { pending = true; main.postDelayed(publish, 500) }
        }
        onDispose { unsubscribe(); main.removeCallbacks(publish) }
    }
    return displayed
}

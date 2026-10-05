package cc.cherr.shelldeck.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import cc.cherr.shelldeck.TerminalController

/** Event-driven, trailing updates: no timer runs while titles are idle or cards are absent. */
@Composable
internal fun rememberSessionTitle(controller: TerminalController): String {
    var displayed by remember(controller) { mutableStateOf(controller.title) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(controller, owner) {
        val main = Handler(Looper.getMainLooper())
        var pending = false
        val publish = Runnable { pending = false; displayed = controller.title }
        displayed = controller.title
        var unsubscribe: (() -> Unit)? = null
        fun updateSubscription() {
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                displayed = controller.title
                if (unsubscribe == null) unsubscribe = controller.observeTitle {
                    if (!pending) { pending = true; main.postDelayed(publish, 500) }
                }
            } else {
                unsubscribe?.invoke(); unsubscribe = null
                main.removeCallbacks(publish); pending = false
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateSubscription() }
        owner.lifecycle.addObserver(observer)
        updateSubscription()
        onDispose { owner.lifecycle.removeObserver(observer); unsubscribe?.invoke(); main.removeCallbacks(publish) }
    }
    return displayed
}

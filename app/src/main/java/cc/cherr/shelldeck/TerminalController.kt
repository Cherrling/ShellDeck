package cc.cherr.shelldeck

import android.app.Application
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.*
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Only a weak UI reference; the owning ViewModel retains the session across rotation. */
class TerminalController(private val app: Application, private val finished: () -> Unit) : TerminalSessionClient, TerminalViewClient {
    lateinit var session: TerminalSession
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    fun special(keyCode: Int) {
        if (!session.isReady) return
        terminalView?.handleKeyCode(keyCode, (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or (if (alt) KeyHandler.KEYMOD_ALT else 0))
        ctrl = false; alt = false
    }
    private var viewReference = java.lang.ref.WeakReference<TerminalView>(null)
    private val terminalView: TerminalView? get() = viewReference.get()
    fun createView(context: android.content.Context): TerminalView = TerminalView(context, null).also {
        viewReference = java.lang.ref.WeakReference(it)
        it.setTerminalViewClient(this)
        it.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics).toInt())
        it.setTypeface(Typeface.MONOSPACE)
        it.setBackgroundColor(android.graphics.Color.BLACK)
        it.isFocusableInTouchMode = true
        it.attachSession(session)
        it.requestFocus()
    }
    fun releaseView(view: TerminalView) {
        view.setTerminalCursorBlinkerState(false, false)
        view.stopTextSelectionMode()
        if (terminalView === view) viewReference.clear()
    }
    fun showKeyboard() { terminalView?.let {
        it.requestFocus()
        it.context.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
    } }
    fun close() { session.finishIfRunning(); viewReference.clear() }
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = finished()
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        app.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.let {
            if (it.itemCount > 0) session?.emulator?.paste(it.getItemAt(0).coerceToText(app).toString())
        }
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { terminalView?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    // Upstream's false branch requests TYPE_CLASS_TEXT, enabling composing IMEs (e.g. Chinese).
    override fun isTerminalViewSelected() = false
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey(): Boolean = ctrl.also { ctrl = false }
    override fun readAltKey(): Boolean = alt.also { alt = false }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() { terminalView?.setTerminalCursorBlinkerState(false, false) }
    // Do not log terminal contents or keystrokes.
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}

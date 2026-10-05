package cc.cherr.shelldeck

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import cc.cherr.shelldeck.terminal.*
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.*
import cc.cherr.shelldeck.keyboard.*
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Only a weak UI reference; the process-owned session survives UI recreation. */
class TerminalController(private val app: Application, private val finished: () -> Unit) : TerminalSessionClient, TerminalViewClient {
    lateinit var session: TerminalSession
    val modifiers = ModifierState()
    var title: String = ""; private set
    private val titleObservers = mutableSetOf<() -> Unit>()
    /** Main-thread subscription used only while a session card is composed. */
    fun observeTitle(observer: () -> Unit): () -> Unit {
        titleObservers.add(observer)
        return { titleObservers.remove(observer) }
    }
    var backgroundColor by mutableIntStateOf(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]); private set
    var foregroundColor by mutableIntStateOf(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND]); private set
    var selectionTheme: cc.cherr.shelldeck.settings.TerminalTheme? = null
        set(value) { field = value; terminalView?.invalidate() }
    private var face: Typeface = Typeface.MONOSPACE
    private var fontSize = 14
    fun appearance(typeface: Typeface, size: Int) {
        val fontChanged = face !== typeface
        val sizeChanged = fontSize != size
        face = typeface; fontSize = size
        terminalView?.let {
            if (fontChanged) it.setTypeface(typeface)
            if (sizeChanged) it.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat(), it.resources.displayMetrics).toInt())
        }
    }
    fun special(keyCode: Int, extra: Set<ModifierKey> = emptySet()) {
        if (!session.isReady) return
        fun active(key: ModifierKey) = key in extra || modifiers.active(key)
        val flags = (if (active(ModifierKey.CTRL)) KeyHandler.KEYMOD_CTRL else 0) or
            (if (active(ModifierKey.ALT)) KeyHandler.KEYMOD_ALT else 0) or
            (if (active(ModifierKey.SHIFT)) KeyHandler.KEYMOD_SHIFT else 0)
        if (terminalView?.handleKeyCode(keyCode, flags) == true) modifiers.consumed()
    }
    fun perform(action: KeyAction) {
        if (action == KeyAction.OpenPrompt) { promptVisible = true; return }
        if (action == KeyAction.ToggleKeyboard) { toggleKeyboard(); return }
        if (!session.isReady) return
        when (action) {
            is KeyAction.Character -> action.text.codePoints().forEach { cp ->
                val code = if (modifiers.active(ModifierKey.SHIFT)) Character.toUpperCase(cp) else cp
                terminalView?.inputCodePoint(-1, code, false, false)
            }
            is KeyAction.Special -> special(action.key.code, action.modifiers)
            is KeyAction.EscapeSequence -> { session.write(action.sequence); modifiers.consumed() }
            is KeyAction.Macro -> { session.write(action.text); modifiers.consumed() }
            else -> Unit
        }
    }
    private var viewReference = java.lang.ref.WeakReference<TerminalView>(null)
    private val terminalView: TerminalView? get() = viewReference.get()
    // Process-memory only: drafts survive navigation/rotation, never enter backups or disk.
    var promptDraft by mutableStateOf("")
    var promptVisible by mutableStateOf(false)
    fun sendPrompt(): Boolean {
        if (!session.isReady || session.emulator == null || promptDraft.isBlank() || promptDraft.length > PasteRequest.MAX_CHARS) return false
        session.emulator.paste(promptDraft)
        promptDraft = ""; promptVisible = false
        return true
    }
    var pendingPaste by mutableStateOf<PasteRequest?>(null); private set
    var pasteError by mutableStateOf<String?>(null); private set
    fun dismissPaste() { pendingPaste = null; pasteError = null }
    fun requestPaste(text: String) {
        if (!session.isReady || terminalView == null || pendingPaste != null) return
        if (text.length > PasteRequest.MAX_CHARS) { pasteError = "粘贴内容超过 128K 字符，请改用文件上传。"; return }
        val request = PasteRequest(text)
        if (request.needsConfirmation) pendingPaste = request else session.emulator?.paste(text)
    }
    fun confirmPaste() {
        val request = pendingPaste ?: return
        dismissPaste()
        if (session.isReady && terminalView != null) session.emulator?.paste(request.text)
    }
    fun createView(context: android.content.Context): TerminalView = ShellTerminalView(context,
        { onPasteTextFromClipboard(session) }, ::requestPaste, { selectionTheme }).also {
        viewReference = java.lang.ref.WeakReference(it)
        it.setTerminalViewClient(this)
        it.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat(), context.resources.displayMetrics).toInt())
        it.setTypeface(face)
        it.setBackgroundColor(backgroundColor)
        it.isFocusableInTouchMode = true
        it.attachSession(session)
        it.setOnCreateContextMenuListener { menu, view, _ ->
            menu.add("Prompt 编辑器").setOnMenuItemClickListener { promptVisible = true; true }
            menu.add("粘贴").setEnabled(session.isReady).setOnMenuItemClickListener { onPasteTextFromClipboard(session); true }
            menu.add("选择文本").setOnMenuItemClickListener { val now = android.os.SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, 0)
                try { (view as TerminalView).startTextSelectionMode(event) } finally { event.recycle() }; true }
        }
        it.requestFocus()
    }
    fun releaseView(view: TerminalView) {
        (view as? ShellTerminalView)?.release()
        dismissPaste()
        modifiers.clear()
        view.setTerminalCursorBlinkerState(false, false)
        view.stopTextSelectionMode()
        if (terminalView === view) viewReference.clear()
    }
    fun showKeyboard() { terminalView?.let {
        it.requestFocus()
        it.context.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
    } }
    fun hideKeyboardIfVisible(): Boolean {
        val view = terminalView ?: return false
        if (androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true) return false
        androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        return true
    }
    fun toggleKeyboard() { terminalView?.let {
        val controller = androidx.core.view.ViewCompat.getWindowInsetsController(it)
        if (androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.ime()) else showKeyboard()
    } }
    fun leave() { dismissPaste(); modifiers.clear(); terminalView?.let {
        androidx.core.view.ViewCompat.getWindowInsetsController(it)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    } }
    fun colorsChanged() { session.emulator?.mColors?.reset(); onColorsChanged(session) }
    fun close() { promptDraft = ""; promptVisible = false; dismissPaste(); modifiers.clear(); session.finishIfRunning(); viewReference.clear() }
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) {
        val next = changedSession.title.orEmpty().filterNot { it.isISOControl() }.trim().take(256)
        if (next != title) {
            title = next
            titleObservers.forEach { it() }
        }
    }
    override fun onSessionFinished(finishedSession: TerminalSession) { dismissPaste(); finished() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        app.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.let {
            if (it.itemCount > 0) it.getItemAt(0).text?.let { text -> requestPaste(text.toString()) }
        }
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        session.emulator?.mColors?.mCurrentColors?.let { colors ->
            backgroundColor = colors[TextStyle.COLOR_INDEX_BACKGROUND]
            foregroundColor = colors[TextStyle.COLOR_INDEX_FOREGROUND]
        }
        terminalView?.let { view -> view.setBackgroundColor(backgroundColor); view.invalidate() }
    }
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
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (e != null && e.isCtrlPressed && e.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) {
            if (e.repeatCount == 0) onPasteTextFromClipboard(session)
            return true
        }
        if (e == null || session?.isReady != true || e.isSystem || e.isFunctionPressed || KeyEvent.isModifierKey(keyCode)) return false
        val flags = (if (e.isCtrlPressed || modifiers.active(ModifierKey.CTRL)) KeyHandler.KEYMOD_CTRL else 0) or
            (if (e.isAltPressed || modifiers.active(ModifierKey.ALT)) KeyHandler.KEYMOD_ALT else 0) or
            (if (e.isShiftPressed || modifiers.active(ModifierKey.SHIFT)) KeyHandler.KEYMOD_SHIFT else 0) or
            (if (e.isNumLockOn) KeyHandler.KEYMOD_NUM_LOCK else 0)
        val emulator = session.emulator ?: return false
        if (KeyHandler.getCode(keyCode, flags, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode) == null) return false
        return terminalView?.handleKeyCode(keyCode, flags)?.also { if (it) modifiers.consumed() } ?: false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = modifiers.active(ModifierKey.CTRL)
    override fun readAltKey() = modifiers.active(ModifierKey.ALT)
    override fun readShiftKey() = modifiers.active(ModifierKey.SHIFT)
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (session?.isReady != true) return true
        // TerminalView has already captured Ctrl/Alt and transformed Shift before this callback.
        modifiers.consumed()
        return false
    }
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

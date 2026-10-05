package cc.cherr.shelldeck.terminal

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.termux.view.TerminalView

/** App policy at the View boundary; upstream parser and selection gestures stay untouched. */
// Constructed by AndroidView with required input policy, never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class ShellTerminalView(context: Context, private val paste: () -> Unit,
    private val pasteText: (String) -> Unit,
    private val selectionTheme: () -> cc.cherr.shelldeck.settings.TerminalTheme? = { null }) : TerminalView(context, null) {
    override fun onDraw(canvas: android.graphics.Canvas) {
        val theme = selectionTheme()
        mRenderer?.setSelectionColors(theme?.colors?.get(19) ?: 0, theme?.colors?.get(20) ?: 0)
        super.onDraw(canvas)
    }
    private var dirty = false
    private var scheduled = false
    private var released = false
    private var middlePressed = false
    internal var screenUpdates = 0L; private set
    private val refresh = Runnable {
        scheduled = false
        if (!released && isShown && windowVisibility == VISIBLE && dirty) {
            dirty = false
            screenUpdates++
            super.onScreenUpdated()
        }
    }
    override fun onScreenUpdated() { dirty = true; scheduleRefresh() }
    private fun scheduleRefresh() {
        if (!released && !scheduled && isAttachedToWindow && isShown && windowVisibility == VISIBLE && dirty) {
            scheduled = true; postOnAnimation(refresh)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleRefresh() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleRefresh() else {
            removeCallbacks(refresh); scheduled = false; middlePressed = false; stopTextSelectionMode()
        }
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(refresh); scheduled = false
        super.onDetachedFromWindow()
    }
    fun release() { released = true; removeCallbacks(refresh); scheduled = false }
    // Primary touch/accessibility behavior remains upstream; only mouse middle button is intercepted.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Upstream pastes on every middle-button motion; route one press through App policy.
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && (middlePressed || event.isButtonPressed(MotionEvent.BUTTON_TERTIARY))) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { middlePressed = true; paste() }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) middlePressed = false
            return true
        }
        return super.onTouchEvent(event)
    }
    override fun onCreateInputConnection(info: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(info) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun performContextMenuAction(id: Int): Boolean {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) { paste(); return true }
                return super.performContextMenuAction(id)
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (PasteRequest(value).needsConfirmation) { pasteText(value); return true }
                return super.commitText(text, newCursorPosition)
            }
        }
    }
}

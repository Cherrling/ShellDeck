package cc.cherr.shelldeck.settings

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.withScale
import cc.cherr.shelldeck.TerminalController
import com.termux.terminal.*
import com.termux.view.TerminalRenderer

/** Isolated emulator and renderer: preview never mutates global colors or sends SSH input. */
class ThemePreviewView(context: Context) : View(context) {
    private val client = TerminalController(context.applicationContext as Application) {}
    private val session = TerminalSession(object : TerminalTransport {
        override fun start(size: TerminalSize, listener: TerminalTransport.Listener) = listener.onReady()
        override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
        override fun resize(size: TerminalSize, applied: Runnable) = applied.run()
        override fun close() = Unit
    }, 10, client).also { client.session = it; it.updateSize(40, 8, 8, 16) }
    private var theme = TerminalTheme.preset(TerminalPalette.DARK)
    private var face = Typeface.MONOSPACE
    private var renderer = TerminalRenderer(20, face)
    init {
        val sample = buildString {
            for (row in 0..1) {
                for (i in row * 8 until row * 8 + 8) append("\u001b[48;5;${i}m  ${i.toString().padStart(2, '0')} ")
                append("\u001b[0m\r\n")
            }
            append("ShellDeck  中文与 ANSI 16 色\r\n")
            append("\u001b[31m- removed\u001b[0m  \u001b[32m+ added\u001b[0m\r\n")
            append("\u001b[1mBold\u001b[0m  \u001b[2mDim\u001b[0m  \u001b[7mReverse\u001b[0m\r\n")
            append("选区 Selected text\r\n")
            append("cursor > ")
        }.toByteArray()
        session.emulator.append(sample, sample.size)
    }
    fun update(value: TerminalTheme, typeface: Typeface) {
        theme = value
        if (face !== typeface) { face = typeface; renderer = TerminalRenderer(20, face) }
        val colors = session.emulator.mColors.mCurrentColors
        value.colors.take(16).forEachIndexed { i, color -> colors[i] = color }
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = value.colors[16]
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = value.colors[17]
        colors[TextStyle.COLOR_INDEX_CURSOR] = value.colors[18]
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Fixed grid, scaled as a whole; rows and colors exactly match the live renderer.
        renderer.setSelectionColors(theme.colors[19], theme.colors[20])
        val scale = minOf(width / (40 * renderer.fontWidth), height / (8f * renderer.fontLineSpacing))
        canvas.drawColor(theme.colors[17])
        canvas.withScale(scale, scale) { renderer.render(session.emulator, this, 0, 5, 5, 0, 16) }
    }
    override fun onDetachedFromWindow() { session.finishIfRunning(); super.onDetachedFromWindow() }
}

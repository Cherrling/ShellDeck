package cc.cherr.shelldeck.terminal

import com.termux.terminal.TerminalEmulator
import java.net.URI

/** Resolves a touched cell; never scans on output, draws, or an idle timer. */
object TerminalLinks {
    private val web = Regex("https?://[^\\s<>\"'`\\p{Cntrl}]+", RegexOption.IGNORE_CASE)

    fun canOpen(target: String): Boolean = try {
        val uri = URI(target)
        target.length <= 4096 && !target.any { it.isISOControl() || it == '\\' } &&
            uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }

    fun at(emulator: TerminalEmulator, column: Int, row: Int): String? {
        val screen = emulator.screen
        if (column !in 0 until emulator.mColumns || row !in -screen.activeTranscriptRows until emulator.mRows) return null
        emulator.getHyperlinkAt(column, row)?.let { return it }
        // Only join soft-wrapped rows. Bound both traversal and text even for malicious output.
        var first = row
        var last = row
        var rows = 1
        while (first > -screen.activeTranscriptRows && screen.getLineWrap(first - 1)) {
            if (++rows > 64) return null
            first--
        }
        while (last < emulator.mRows - 1 && screen.getLineWrap(last)) {
            if (++rows > 64) return null
            last++
        }
        val text = StringBuilder()
        var offset = -1
        for (y in first..last) {
            val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(y))
            if (y == row) offset = text.length + line.findStartOfColumn(column)
            if (text.length + line.spaceUsed > 8192) return null
            text.append(line.mText, 0, line.spaceUsed)
        }
        return find(text.toString(), offset)
    }

    internal fun find(text: String, offset: Int): String? {
        if (text.length > 8192 || offset !in text.indices) return null
        for (match in web.findAll(text)) {
            var value = match.value.trimEnd('.', ',', ';', ':', '!', '?', '。', '，', '；', '！', '？', '、')
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                var extra = value.count { it == close } - value.count { it == open }
                var end = value.length
                while (end > 0 && extra > 0 && value[end - 1] == close) { end--; extra-- }
                value = value.substring(0, end)
            }
            if (offset >= match.range.first && offset < match.range.first + value.length && canOpen(value)) return value
        }
        return null
    }
}

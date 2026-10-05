package cc.cherr.shelldeck.terminal

/** Bounded, transient input. Never persisted or logged. */
data class PasteRequest(val text: String) {
    val needsConfirmation get() = text.any { it == '\n' || it == '\r' } || text.length > 4096
    val preview get() = text.take(1200).replace("\r\n", "\n").replace('\r', '\n')
    companion object { const val MAX_CHARS = 128 * 1024 }
}

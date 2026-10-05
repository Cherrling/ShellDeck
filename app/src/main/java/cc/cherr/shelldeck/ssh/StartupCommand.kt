package cc.cherr.shelldeck.ssh

/** Send one shell input line; control characters must not become accidental terminal actions. */
fun startupCommandLine(command: String): ByteArray {
    require(command.none { it.isISOControl() }) { "启动命令必须为单行，不能包含控制字符" }
    val bytes = command.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 4095) { "启动命令过长" }
    return if (command.isBlank()) byteArrayOf() else bytes + byteArrayOf(13)
}

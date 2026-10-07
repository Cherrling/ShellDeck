package cc.cherr.shelldeck.proxy

import cc.cherr.shelldeck.data.CredentialVault
import cc.cherr.shelldeck.data.ProxyRecord
import java.io.*
import java.net.*

class ProxyFailure(val kind: Kind) : IOException(kind.message) {
    enum class Kind(val message: String) {
        CONNECT("无法连接代理"), AUTH("代理认证失败"), REJECTED("代理拒绝目标连接"),
        PROTOCOL("代理响应无效"), TIMEOUT("代理连接或握手超时")
    }
}

/** No plaintext credentials in data-class toString, persistence or process-wide authenticators. */
class ProxyCredentials(val username: ByteArray, val password: ByteArray) : AutoCloseable {
    init { require(username.size in 1..255 && password.size in 1..255) }
    override fun close() { username.fill(0); password.fill(0) }
    fun encode(): ByteArray = byteArrayOf(username.size.toByte()) + username + password
    companion object {
        fun decode(bytes: ByteArray): ProxyCredentials {
            require(bytes.isNotEmpty())
            val n = bytes[0].toInt() and 255
            require(n in 1..255 && bytes.size - n - 1 in 1..255)
            return ProxyCredentials(bytes.copyOfRange(1, n + 1), bytes.copyOfRange(n + 1, bytes.size))
        }
    }
}

fun validateProxy(record: ProxyRecord) {
    require(record.id.isNotBlank() && record.label.isNotBlank() && record.label.length <= 200)
    require(record.hostname.isNotBlank() && record.hostname.length <= 253 &&
        record.hostname.none { it.isWhitespace() || it.isISOControl() || it in "/\\@?#[]" })
    require(record.port in 1..65535)
}

fun proxyCredentials(record: ProxyRecord, vault: CredentialVault): ProxyCredentials? {
    if (!record.authenticated) return null
    val bytes = vault.decrypt("proxy:${record.id}", record.encryptedCredentials)
    return try { ProxyCredentials.decode(bytes) } finally { bytes.fill(0) }
}

object Socks5 {
    /** Caller owns socket before DNS/connect starts, so cancellation always closes the same socket. */
    fun connect(socket: Socket, proxy: ProxyRecord, target: String, port: Int, credentials: ProxyCredentials?, timeoutMs: Int = 15_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        fun remaining(): Int = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().also {
            if (it <= 0) throw SocketTimeoutException()
        }
        try {
            try { socket.connect(InetSocketAddress(proxy.hostname, proxy.port), remaining()) }
            catch (e: SocketTimeoutException) { throw e }
            catch (_: IOException) { throw ProxyFailure(ProxyFailure.Kind.CONNECT) }
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            fun read(): Int { socket.soTimeout = remaining(); return input.read().also { if (it < 0) throw EOFException() } }
            fun bytes(n: Int) { repeat(n) { read() } }
            fun send(data: ByteArray) { out.write(data); out.flush() }
            send(byteArrayOf(5, 1, if (credentials == null) 0 else 2))
            if (read() != 5) throw ProxyFailure(ProxyFailure.Kind.PROTOCOL)
            val method = read()
            if (method != if (credentials == null) 0 else 2) throw ProxyFailure(ProxyFailure.Kind.AUTH)
            if (credentials != null) {
                val request = byteArrayOf(1, credentials.username.size.toByte()) + credentials.username +
                    byteArrayOf(credentials.password.size.toByte()) + credentials.password
                try { send(request) } finally { request.fill(0) }
                if (read() != 1 || read() != 0) throw ProxyFailure(ProxyFailure.Kind.AUTH)
            }
            val literal = numericAddress(target)
            val address = literal ?: if (!proxy.remoteDns) InetAddress.getByName(target).address else null
            val encoded = if (address != null) byteArrayOf(if (address.size == 4) 1 else 4) + address else {
                val name = IDN.toASCII(target).toByteArray(Charsets.US_ASCII)
                require(name.size in 1..255)
                byteArrayOf(3, name.size.toByte()) + name
            }
            require(port in 1..65535)
            send(byteArrayOf(5, 1, 0) + encoded + byteArrayOf((port ushr 8).toByte(), port.toByte()))
            if (read() != 5) throw ProxyFailure(ProxyFailure.Kind.PROTOCOL)
            val reply = read()
            if (read() != 0) throw ProxyFailure(ProxyFailure.Kind.PROTOCOL)
            if (reply != 0) throw ProxyFailure(ProxyFailure.Kind.REJECTED)
            when (read()) { 1 -> bytes(4); 4 -> bytes(16); 3 -> bytes(read().also { if (it == 0) throw IOException() }); else -> throw IOException() }
            bytes(2)
            socket.soTimeout = 0
        } catch (e: ProxyFailure) { throw e }
        catch (_: SocketTimeoutException) { throw ProxyFailure(ProxyFailure.Kind.TIMEOUT) }
        catch (_: Exception) { throw ProxyFailure(ProxyFailure.Kind.PROTOCOL) }
    }

    /** Strict literal parsing; never resolves a hostname while remote DNS is selected. */
    fun numericAddress(value: String): ByteArray? {
        val host = value.removeSurrounding("[", "]")
        if (':' in host) {
            if (!host.all { it in "0123456789abcdefABCDEF:." }) return null
            return runCatching { InetAddress.getByName(host).address }.getOrNull()
        }
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || (it.toIntOrNull() ?: -1) !in 0..255 }) return null
        return parts.map { it.toInt().toByte() }.toByteArray()
    }
}

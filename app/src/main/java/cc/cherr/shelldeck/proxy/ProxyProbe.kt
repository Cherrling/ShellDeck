package cc.cherr.shelldeck.proxy

import cc.cherr.shelldeck.data.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Tracks the raw socket before connect and the TLS layer before handshake. */
class ProbeCall : AutoCloseable {
    private var closed = false
    private val sockets = mutableListOf<Socket>()
    @Synchronized fun own(socket: Socket) { if (closed) { socket.close(); throw IOException("Cancelled") }; sockets.add(socket) }
    @Synchronized override fun close() { closed = true; sockets.forEach { runCatching { it.close() } }; sockets.clear() }
}

data class ProbeResult(val message: String, val latencyMs: Long? = null, val ip: String? = null,
    val ipError: String? = null, val checkedAt: Long = System.currentTimeMillis(), val stale: Boolean = false)

object ProxyProbe {
    const val CHECK_HOST = "cp.cloudflare.com"
    const val TRACE_HOST = "www.cloudflare.com"
    private val deadlines = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "proxy-probe-timeout").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

    fun run(proxy: ProxyRecord?, vault: CredentialVault, call: ProbeCall): ProbeResult {
        val credentials = proxy?.let { proxyCredentials(it, vault) }
        credentials.use {
            val start = System.nanoTime()
            var elapsed: Long? = null
            val state = try {
                val check = request(CHECK_HOST, "/generate_204", proxy, credentials, call)
                if (check.status == 204) {
                    elapsed = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
                    "检测成功"
                } else "检测响应异常（${check.status}）"
            } catch (e: Exception) { message(e) }
            // Separate requests: trace failure cannot turn a successful connectivity check into failure.
            return try {
                val trace = request(TRACE_HOST, "/cdn-cgi/trace", proxy, credentials, call)
                require(trace.status == 200)
                val fields = trace.body.toString(Charsets.UTF_8).lineSequence().mapNotNull {
                    val split = it.indexOf('='); if (split < 0) null else it.substring(0, split) to it.substring(split + 1).trim()
                }.toList()
                val ip = fields.filter { it.first == "ip" }.single().second
                require(Socks5.numericAddress(ip) != null)
                ProbeResult(state, elapsed, ip)
            } catch (_: Exception) { ProbeResult(state, elapsed, ipError = "出口 IP 获取失败") }
        }
    }
    fun message(error: Exception): String = when(error) {
        is ProxyFailure -> error.kind.message
        is javax.net.ssl.SSLException -> "TLS 验证或握手失败"
        is SocketTimeoutException -> "检测超时"
        is UnknownHostException -> "无法解析检测地址"
        else -> "检测失败"
    }
    private fun request(host: String, path: String, proxy: ProxyRecord?, credentials: ProxyCredentials?, call: ProbeCall): HttpReply {
        val raw = Socket(); call.own(raw)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = deadlines.schedule({ timedOut.set(true); runCatching { raw.close() } }, 10, TimeUnit.SECONDS)
        try {
            if (proxy == null) raw.connect(InetSocketAddress(host, 443), 10_000)
            else Socks5.connect(raw, proxy, host, 443, credentials, 10_000)
            raw.soTimeout = 10_000
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            call.own(tls)
            tls.use {
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = 10_000
                tls.startHandshake()
                tls.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: ShellDeck\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n".toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()
                return readHttpReply(tls.inputStream)
            }
        } catch (e: Exception) { if (timedOut.get()) throw SocketTimeoutException(); throw e }
        finally { timeout.cancel(false); raw.close() }
    }
}

internal class HttpReply(val status: Int, val body: ByteArray)
/** Bounded HTTP/1.1 reader for the two fixed HTTPS probes; no redirects, cookies or content decoding. */
internal fun readHttpReply(stream: InputStream): HttpReply {
    val input = BufferedInputStream(stream)
    var headerBytes = 0
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read(); require(b >= 0 && ++headerBytes <= 32768)
            if (b == 10) break
            bytes.write(b)
        }
        val value = bytes.toByteArray(); require(value.isNotEmpty() && value.last() == 13.toByte())
        return String(value, 0, value.size - 1, Charsets.US_ASCII)
    }
    val statusLine = line().split(' ', limit = 3)
    require(statusLine.size >= 2 && statusLine[0] in listOf("HTTP/1.0", "HTTP/1.1"))
    val status = statusLine[1].toInt(); require(status in 200..599)
    val headers = mutableMapOf<String, String>()
    while (true) {
        val line = line(); if (line.isEmpty()) break
        val index = line.indexOf(':'); require(index > 0)
        val name = line.substring(0, index).lowercase(java.util.Locale.ROOT)
        require(headers.put(name, line.substring(index + 1).trim()) == null || name !in listOf("content-length", "transfer-encoding"))
    }
    if (status == 204) return HttpReply(status, byteArrayOf())
    require(headers["content-encoding"] in listOf(null, "identity"))
    val body = ByteArrayOutputStream()
    fun readBytes(size: Int) {
        require(size in 0..16384 - body.size())
        repeat(size) { val b = input.read(); require(b >= 0); body.write(b) }
    }
    val transfer = headers["transfer-encoding"]
    if (transfer != null) {
        require(transfer.equals("chunked", true) && headers["content-length"] == null)
        while (true) {
            val size = line().substringBefore(';').trim().toInt(16)
            if (size == 0) { while (line().isNotEmpty()) { }; break }
            readBytes(size); require(line().isEmpty())
        }
    } else if (headers["content-length"] != null) readBytes(headers.getValue("content-length").toInt())
    else while (true) { val b = input.read(); if (b < 0) break; require(body.size() < 16384); body.write(b) }
    return HttpReply(status, body.toByteArray())
}

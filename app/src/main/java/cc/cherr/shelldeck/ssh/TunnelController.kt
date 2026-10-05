package cc.cherr.shelldeck.ssh

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import java.util.concurrent.Executors

class TunnelController(private val open: (Int, String, Int) -> LocalTunnel) {
    val tunnels = mutableStateListOf<LocalTunnel>()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    fun start(local: String, host: String, remote: String) {
        if (closed || busy) return
        val l = local.toIntOrNull(); val r = remote.toIntOrNull()
        if (l == null || l !in 1024..65535 || r == null || r !in 1..65535 || host.isBlank()) {
            error = "本地端口须为 1024–65535，目标端口须为 1–65535。"; return
        }
        if (tunnels.size >= 8) { error = "每个会话最多开启 8 个转发。"; return }
        busy = true; error = null
        worker.execute {
            try {
                val tunnel = open(l, host.trim().removeSurrounding("[", "]"), r)
                main.post {
                    if (closed) Thread { tunnel.close() }.start() else tunnels.add(tunnel)
                    busy = false
                }
            } catch (_: Exception) { main.post { busy = false; error = "转发启动失败，请检查端口占用、地址和连接状态。" } }
        }
    }
    fun stop(tunnel: LocalTunnel) { if (!closed && tunnels.remove(tunnel)) worker.execute { tunnel.close() } }
    fun close() {
        if (closed) return
        closed = true
        val owned = tunnels.toList(); tunnels.clear()
        worker.execute { owned.forEach { it.close() } }; worker.shutdown()
    }
}

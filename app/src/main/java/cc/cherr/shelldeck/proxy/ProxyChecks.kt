package cc.cherr.shelldeck.proxy

import android.content.Context
import android.net.*
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import cc.cherr.shelldeck.data.*
import java.util.concurrent.*

/** Screen-owned manual checks; never schedules background polling. Main-thread public API. */
class ProxyChecks(context: Context, private val vault: CredentialVault) : AutoCloseable {
    val results = mutableStateMapOf<String, ProbeResult>()
    val pending = mutableStateMapOf<String, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private class Job(val call: ProbeCall) { var future: Future<*>? = null }
    private val jobs = mutableMapOf<String, Job>()
    private var closed = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var network = connectivity.activeNetwork
    private var properties = network?.let(connectivity::getLinkProperties)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        private fun changed() { main.post {
            val active = connectivity.activeNetwork
            val links = active?.let(connectivity::getLinkProperties)
            if (!closed && (network != active || properties != links)) {
                network = active; properties = links; cancelAll()
                results.keys.toList().forEach { id -> results[id]?.let { results[id] = it.copy(stale = true) } }
            }
        } }
    }
    init { connectivity.registerDefaultNetworkCallback(callback) }
    fun check(proxy: ProxyRecord?) {
        if (closed) return
        val id = proxy?.id ?: DIRECT
        cancel(id); results.remove(id); pending[id] = true
        val job = Job(ProbeCall()); jobs[id] = job
        job.future = executor.submit {
            val result = try { ProxyProbe.run(proxy, vault, job.call) } catch (_: Exception) { ProbeResult("检测失败，请检查网络或凭据") }
            finally { job.call.close() }
            main.post { if (!closed && jobs[id] === job) { jobs.remove(id); pending.remove(id); results[id] = result } }
        }
    }
    fun invalidate(id: String) { cancel(id); results.remove(id) }
    private fun cancel(id: String) { jobs.remove(id)?.let { it.call.close(); it.future?.cancel(true) }; pending.remove(id); executor.purge() }
    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }
    override fun close() { closed = true; cancelAll(); connectivity.unregisterNetworkCallback(callback); executor.shutdownNow() }
    companion object { const val DIRECT = "__direct__" }
}

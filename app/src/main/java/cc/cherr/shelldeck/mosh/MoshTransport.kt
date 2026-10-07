package cc.cherr.shelldeck.mosh

import android.content.Context
import android.os.ParcelFileDescriptor
import cc.cherr.shelldeck.data.HostRecord
import cc.cherr.shelldeck.ssh.*
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** SSH exists only during bootstrap; native Mosh owns roaming, UDP and reconnect notifications. */
class MoshTransport(
    context: Context,
    private val host: HostRecord,
    private val connector: SshConnector,
    private val status: (ConnectionState) -> Unit,
) : TerminalTransport {
    private val context = context.applicationContext
    private val closed = AtomicBoolean()
    private val processLock = Any()
    private var pid = 0
    private var pty: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private val reader = Executors.newSingleThreadExecutor()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256))
    private val shutdown = Executors.newSingleThreadScheduledExecutor()
    private val pendingBytes = java.util.concurrent.atomic.AtomicInteger()

    override fun start(initial: TerminalSize, listener: TerminalTransport.Listener) {
        reader.execute {
            var result = 1
            try {
                if (host.protocol != "mosh" || runCatching { validateMoshHost(host) }.isFailure)
                    throw MoshFailure(ConnectionState.MOSH_UNSUPPORTED)
                check(!closed.get())
                val binary = File(context.applicationInfo.nativeLibraryDir, "libmosh-client.so")
                if (!binary.canExecute()) throw MoshFailure(ConnectionState.MOSH_NATIVE_FAILED)
                // Force native linkage before starting any remote process.
                NativePty
                val terminfo = prepareTerminfo()
                val ssh = connector.connect()
                status(ConnectionState.MOSH_STARTING)
                val target = ssh.remoteAddress.hostAddress
                    ?: throw MoshFailure(ConnectionState.ADDRESS_FAILED)
                val bootstrapTimedOut = AtomicBoolean()
                val deadline = shutdown.schedule({ bootstrapTimedOut.set(true); connector.close() }, 30, TimeUnit.SECONDS)
                val connection = try { ssh.startSession().use { channel ->
                    // stdout is bounded; stderr is deliberately discarded and never logged (session keys).
                    channel.exec(MoshBootstrap.command(host.moshPort, host.startupCommand) + " 2>/dev/null").use { command ->
                        val data = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val n = command.inputStream.read(buffer)
                            if (n < 0) break
                            if (data.size() + n > 16 * 1024) throw MoshFailure(ConnectionState.MOSH_START_FAILED)
                            data.write(buffer, 0, n)
                        }
                        MoshBootstrap.parse(data.toString("UTF-8"))
                    }
                } } catch (failure: Exception) {
                    if (bootstrapTimedOut.get()) throw MoshFailure(ConnectionState.TIMEOUT)
                    throw failure
                } finally { deadline.cancel(false) }
                connector.close()
                synchronized(processLock) {
                    check(!closed.get())
                    val child = NativePty.spawn(arrayOf(binary.absolutePath, target, connection.port.toString()),
                        arrayOf("MOSH_KEY=${connection.key}", "TERM=xterm-256color", "TERMINFO=${terminfo.absolutePath}",
                            "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "MOSH_PREDICTION_DISPLAY=never", "MOSH_NO_TERM_INIT=1", "PATH=/system/bin"),
                        initial.rows, initial.columns)
                    pid = child[1]
                    pty = ParcelFileDescriptor.adoptFd(child[0])
                    output = FileOutputStream(pty!!.fileDescriptor)
                }
                val exit = java.util.concurrent.CompletableFuture<Int>()
                val childPid = synchronized(processLock) { pid }
                Thread({
                    val code = runCatching {
                        NativePty.awaitExit(childPid)
                        synchronized(processLock) {
                            // WNOWAIT keeps the PID owned until reaping under the signal lock.
                            NativePty.reap(childPid).also { pid = 0 }
                        }
                    }.getOrDefault(1)
                    exit.complete(code)
                }, "mosh-wait").start()
                status(ConnectionState.MOSH_ACTIVE)
                listener.onReady()
                val input = FileInputStream(requireNotNull(pty).fileDescriptor)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (!closed.get()) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) listener.onBytes(buffer, n)
                    }
                } catch (failure: java.io.IOException) {
                    // Linux PTY master reports EIO when the slave closes; waitpid is authoritative.
                    if (!closed.get() && failure.message?.contains("EIO") != true) throw failure
                }
                result = exit.get(5, TimeUnit.SECONDS)
                if (!closed.get()) status(if (result == 0) ConnectionState.ENDED else ConnectionState.MOSH_NATIVE_FAILED)
            } catch (failure: Exception) {
                if (!closed.get()) status(connectionFailure(failure))
            } catch (_: UnsatisfiedLinkError) {
                if (!closed.get()) status(ConnectionState.MOSH_NATIVE_FAILED)
            } finally {
                close()
                connector.close()
                listener.onClosed(result)
                // The cleanup worker closes the fd after terminating/reaping the child.
            }
        }
    }

    private fun prepareTerminfo(): File = synchronized(ASSET_LOCK) {
        val root = File(context.filesDir, "mosh-terminfo-v1")
        fun copy(path: String, target: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                target.parentFile!!.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                context.assets.open(path).use { input -> temp.outputStream().use(input::copyTo) }
                check(temp.renameTo(target))
            } else children.forEach { copy("$path/$it", File(target, it)) }
        }
        if (!File(root, ".ready").exists()) {
            copy("terminfo", root)
            File(root, ".ready").writeText("1")
        }
        root
    }

    private fun send(action: () -> Unit) {
        if (closed.get()) return
        try { writer.execute {
            if (!closed.get()) try { action() } catch (_: Exception) { failWrite() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed.get()) failWrite() }
    }
    private fun failWrite() { if (!closed.get()) status(ConnectionState.IO_FAILED); close() }
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        if (closed.get()) return
        if (pendingBytes.addAndGet(count) > 1024 * 1024) { pendingBytes.addAndGet(-count); failWrite(); return }
        val copy = bytes.copyOfRange(offset, offset + count)
        send { try { requireNotNull(output).apply { write(copy); flush() } }
            finally { copy.fill(0); pendingBytes.addAndGet(-count) } }
    }
    override fun resize(size: TerminalSize, onApplied: Runnable) = send {
        synchronized(processLock) {
            NativePty.resize(requireNotNull(pty).fd, size.rows, size.columns)
        }
        onApplied.run()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writer.shutdownNow()
        reader.shutdownNow()
        shutdown.execute {
            connector.close()
            synchronized(processLock) { if (pid > 0) runCatching { NativePty.signal(pid, 15) } }
        }
        // SIGTERM requests Mosh protocol shutdown; a bounded fallback handles unreachable peers.
        shutdown.schedule({
            synchronized(processLock) {
                if (pid > 0) runCatching { NativePty.signal(pid, 9) }
                runCatching { pty?.close() }; pty = null; output = null
            }
            shutdown.shutdown()
        }, 2, TimeUnit.SECONDS)
    }
    companion object { private val ASSET_LOCK = Any() }
}

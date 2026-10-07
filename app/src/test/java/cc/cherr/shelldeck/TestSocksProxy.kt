package cc.cherr.shelldeck

import cc.cherr.shelldeck.data.ProxyRecord
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Loopback-only relay; synthetic credentials and destination override belong only to tests. */
class TestSocksProxy(private val destinationPort: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requestedHost = AtomicReference<String>()
    val failure = AtomicReference<Throwable>()
    val record = ProxyRecord().apply { hostname = "127.0.0.1"; port = listener.localPort }
    private val worker = Thread {
        try {
            val client = listener.accept(); sockets.add(client); client.soTimeout = 10000
            val input = DataInputStream(client.getInputStream()); val output = client.getOutputStream()
            check(input.readUnsignedByte() == 5); val methods = input.readNBytes(input.readUnsignedByte())
            check(0.toByte() in methods); output.write(byteArrayOf(5,0))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            requestedHost.set(when (input.readUnsignedByte()) {
                3 -> String(input.readNBytes(input.readUnsignedByte()))
                1 -> InetAddress.getByAddress(input.readNBytes(4)).hostAddress
                4 -> InetAddress.getByAddress(input.readNBytes(16)).hostAddress
                else -> error("address")
            })
            check(input.readUnsignedShort() == destinationPort)
            val remote = Socket("127.0.0.1", destinationPort); sockets.add(remote)
            output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0))
            client.soTimeout = 0
            Thread { runCatching { input.copyTo(remote.getOutputStream()); remote.shutdownOutput() } }.apply { isDaemon = true; start() }
            remote.getInputStream().copyTo(output)
        } catch (e: Throwable) { if (!listener.isClosed) failure.set(e) }
    }.apply { isDaemon = true; start() }
    override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; worker.join(2000) }
}

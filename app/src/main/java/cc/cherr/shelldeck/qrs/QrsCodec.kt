package cc.cherr.shelldeck.qrs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random

/** QRS wire format: LE degree, indices, block count, byte count, CRC32 xor block count, data.
 * Compatible file framing is documented by qifi-dev/qrs (see THIRD_PARTY_NOTICES.md).
 * Bounded peeling decoder; no web content, file IO, or sensitive logging.
 */
object QrsLimits {
    const val MAX_DATA = 8 * 1024 * 1024 + 4096
    const val SLICE = 500
    const val MIN_SLICE = 100
    const val MAX_SLICE = 1500
    const val MAX_BLOCKS = (MAX_DATA + 99) / 100
    const val MAX_DEGREE = 128
    const val MAX_PENDING = 2048
    const val MAX_REFERENCES = 32768
    fun checksum(bytes: ByteArray, blocks: Int) = CRC32().apply { update(bytes) }.value.toInt() xor blocks
}

class QrsEncoder(private val data: ByteArray, private var sliceSize: Int = QrsLimits.SLICE) : AutoCloseable {
    init { require(data.isNotEmpty() && data.size <= QrsLimits.MAX_DATA && sliceSize in QrsLimits.MIN_SLICE..QrsLimits.MAX_SLICE) }
    var blocks = (data.size + sliceSize - 1) / sliceSize; private set
    private var sequence = 0L
    private var closed = false
    private var checksum = QrsLimits.checksum(data, blocks)

    @Synchronized fun resize(bytes: Int) {
        check(!closed)
        require(bytes in QrsLimits.MIN_SLICE..QrsLimits.MAX_SLICE)
        if (bytes == sliceSize) return
        sliceSize = bytes; blocks = (data.size + sliceSize - 1) / sliceSize
        checksum = QrsLimits.checksum(data, blocks); sequence = 0
    }

    /** Systematic blocks interleaved with sparse LT equations. Missed frames need no acknowledgement. */
    @Synchronized fun next(): String {
        check(!closed)
        val index = sequence++
        val indices = if (index % 2L == 0L || blocks == 1) intArrayOf(((index / 2) % blocks).toInt())
        else {
            val degree = listOf(2, 2, 3, 5, 8).random().coerceAtMost(blocks)
            val selected = mutableSetOf<Int>()
            while (selected.size < degree) selected.add(Random.nextInt(blocks))
            selected.sorted().toIntArray()
        }
        val payload = ByteBuffer.allocate(16 + indices.size * 4 + sliceSize).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(indices.size); indices.forEach(payload::putInt)
        payload.putInt(blocks); payload.putInt(data.size); payload.putInt(checksum)
        val chunk = ByteArray(sliceSize)
        try {
            indices.forEach { block ->
                val start = block * sliceSize
                for (i in 0 until minOf(sliceSize, data.size - start)) chunk[i] = (chunk[i].toInt() xor data[start + i].toInt()).toByte()
            }
            payload.put(chunk)
            return Base64.getEncoder().encodeToString(payload.array())
        } finally { chunk.fill(0); payload.array().fill(0) }
    }
    @Synchronized override fun close() { closed = true; data.fill(0) }
}

class QrsDecoder : AutoCloseable {
    private class Equation(val indices: MutableSet<Int>, val bytes: ByteArray)
    private var decoded = arrayOfNulls<ByteArray>(0)
    private var byIndex = arrayOfNulls<MutableSet<Equation>>(0)
    private val pending = mutableSetOf<Equation>()
    private var references = 0
    private var length = 0
    private var slice = 0
    private var checksum = 0
    var count: Int = 0; private set
    val total: Int get() = decoded.size
    private var closed = false

    @Synchronized fun accept(text: String): ByteArray? {
        check(!closed)
        if (text.length > 4096) return null
        val encoded = if (text.startsWith("https://qrss.netlify.app/#")) text.substringAfter('#') else text
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        try { return acceptBytes(bytes) } finally { bytes.fill(0) }
    }
    private fun acceptBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 20) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val degree = input.int
        if (degree !in 1..QrsLimits.MAX_DEGREE || bytes.size < 16 + degree * 4 + 1) return null
        val indices = List(degree) { input.int }.toMutableSet()
        val blocks = input.int; val size = input.int; val crc = input.int
        val blockSize = input.remaining()
        if (blocks !in 1..QrsLimits.MAX_BLOCKS || size !in 1..QrsLimits.MAX_DATA || blockSize !in QrsLimits.MIN_SLICE..QrsLimits.MAX_SLICE ||
            blocks != (size + blockSize - 1) / blockSize || indices.size != degree || indices.any { it !in 0 until blocks }) return null
        if (decoded.isEmpty()) {
            decoded = arrayOfNulls(blocks); byIndex = arrayOfNulls(blocks)
            length = size; slice = blockSize; checksum = crc
        }
        // Keep the first transfer. A different sender must not silently destroy collected progress.
        require(blocks == total && size == length && blockSize == slice && crc == checksum) { "检测到另一份传输，请对准原二维码，或重新开始接收。" }
        val data = ByteArray(blockSize); input.get(data)
        val known = indices.filter { decoded[it] != null }
        known.forEach { xor(data, decoded[it]!!); indices.remove(it) }
        if (indices.isEmpty()) { data.fill(0); return null }
        if (indices.size > 1) {
            if (pending.size >= QrsLimits.MAX_PENDING || references + indices.size > QrsLimits.MAX_REFERENCES || pending.any { it.indices == indices }) {
                data.fill(0); return null
            }
            val eq = Equation(indices, data); pending.add(eq); references += indices.size
            indices.forEach { (byIndex[it] ?: mutableSetOf<Equation>().also { set -> byIndex[it] = set }).add(eq) }
            return null
        }
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.add(indices.first() to data)
        while (queue.isNotEmpty()) {
            val (index, value) = queue.removeFirst()
            if (decoded[index] != null) { value.fill(0); continue }
            decoded[index] = value; count++
            val affected = byIndex[index]?.toList().orEmpty(); byIndex[index] = null
            for (eq in affected) {
                if (!pending.contains(eq)) continue
                xor(eq.bytes, value); eq.indices.remove(index); references--
                if (eq.indices.size <= 1) {
                    pending.remove(eq)
                    eq.indices.forEach { byIndex[it]?.remove(eq); references-- }
                    if (eq.indices.isEmpty()) eq.bytes.fill(0) else queue.add(eq.indices.first() to eq.bytes)
                }
            }
        }
        if (count != total) return null
        val assembled = ByteArray(length)
        decoded.forEachIndexed { i, block -> block!!.copyInto(assembled, i * slice, 0, minOf(slice, length - i * slice)) }
        try {
            // Official QRS supports raw data and zlib data. Check raw first (our encrypted bytes do not compress).
            if (QrsLimits.checksum(assembled, total) == checksum) return assembled.copyOf()
            val inflated = inflate(assembled)
            if (QrsLimits.checksum(inflated, total) != checksum) { inflated.fill(0); error("二维码数据校验失败，请重新开始接收。") }
            return inflated
        } finally { assembled.fill(0); close() }
    }
    private fun xor(target: ByteArray, value: ByteArray) { for (i in target.indices) target[i] = (target[i].toInt() xor value[i].toInt()).toByte() }
    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        val output = cc.cherr.shelldeck.backup.WipingBuffer(QrsLimits.MAX_DATA)
        val buffer = ByteArray(8192)
        try {
            inflater.setInput(bytes)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                require(n > 0 || inflater.finished()) { "二维码压缩数据不完整。" }
                output.write(buffer, 0, n)
            }
            require(inflater.remaining == 0)
            return output.toByteArray()
        } finally { inflater.end(); buffer.fill(0); output.close() }
    }
    @Synchronized override fun close() {
        closed = true
        decoded.forEach { it?.fill(0) }; pending.forEach { it.bytes.fill(0) }
        decoded = emptyArray(); byIndex = emptyArray(); pending.clear(); references = 0
    }
}

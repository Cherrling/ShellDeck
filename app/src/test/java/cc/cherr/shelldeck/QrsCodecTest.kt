package cc.cherr.shelldeck

import cc.cherr.shelldeck.qrs.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater
import kotlin.random.Random

class QrsCodecTest {
    @Test fun lostDuplicatedAndReorderedFramesRecoverExactly() {
        val original = Random(42).nextBytes(27013)
        val framed = QrsBackup.frame(original, true)
        QrsEncoder(framed.copyOf()).use { sender -> QrsDecoder().use { receiver ->
            val frames = List(sender.blocks * 12) { sender.next() }.filterIndexed { i, _ -> i % 3 != 0 }.shuffled(Random(81))
            var result: ByteArray? = null
            for (frame in frames) {
                result = receiver.accept(frame)
                if (result != null) break
                result = receiver.accept(frame)
                if (result != null) break
            }
            assertNotNull(result)
            assertArrayEquals(original, QrsBackup.unframe(result!!))
        } }
    }
    @Test fun changingBlockSizeResetsTheSameReceiverWithoutMixingEquations() {
        val original = Random(32).nextBytes(4013)
        QrsEncoder(original.copyOf()).use { sender ->
            QrsDecoder().use { receiver ->
                for (size in listOf(100, 137, 1500, 1499, 500, 100)) {
                    sender.resize(size)
                    receiver.accept(sender.next())
                    assertEquals(1, receiver.count)
                    assertEquals((original.size + size - 1) / size, receiver.total)
                }
                var restored: ByteArray? = null
                for (i in 0 until 300) {
                    val frame = sender.next()
                    if (i % 3 == 1) continue
                    restored = receiver.accept(frame)
                    if (restored != null) break
                }
                assertArrayEquals(original, restored)
            }
            assertThrows(IllegalArgumentException::class.java) { sender.resize(99) }
            assertThrows(IllegalArgumentException::class.java) { sender.resize(1501) }
        }
    }
    @Test fun officialLittleEndianCompressedFrameDecodes() {
        val original = QrsBackup.frame("test data".repeat(40).toByteArray(), true)
        val deflater = Deflater(); deflater.setInput(original); deflater.finish()
        val buffer = ByteArray(500); val length = deflater.deflate(buffer); assertTrue(deflater.finished()); deflater.end()
        val wire = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(0).putInt(1).putInt(length).putInt(QrsLimits.checksum(original, 1)).put(buffer).array()
        QrsDecoder().use { decoder -> assertArrayEquals(original, decoder.accept("https://qrss.netlify.app/#" + Base64.getEncoder().encodeToString(wire))) }
    }
    @Test fun malformedFramesAndOtherTransfersDoNotAllocateOrOverwrite() {
        QrsDecoder().use { receiver ->
            assertNull(receiver.accept("not a qr")); assertNull(receiver.accept("x".repeat(5000)))
            val invalid = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE).array()
            assertNull(receiver.accept(Base64.getEncoder().encodeToString(invalid))); assertEquals(0, receiver.total)
            QrsEncoder(ByteArray(2000) { 1 }).use { a -> QrsEncoder(ByteArray(2000) { 2 }).use { b ->
                receiver.accept(a.next()); assertEquals(1, receiver.count)
                assertThrows(IllegalArgumentException::class.java) { receiver.accept(b.next()) }
                b.resize(137)
                assertThrows(IllegalArgumentException::class.java) { receiver.accept(b.next()) }
                assertEquals(1, receiver.count)
            } }
        }
    }
    @Test fun pinnedUpstreamVectorsMatchRawSenderAndCompressedReceiver() {
        for (mode in listOf("raw", "zlib")) {
            val lines = javaClass.getResourceAsStream("/qrs/upstream-$mode.txt")!!.bufferedReader().use { it.readLines() }
            val expected = Base64.getDecoder().decode(lines.first())
            val frames = lines.drop(1)
            QrsDecoder().use { receiver ->
                var restored: ByteArray? = null
                for (frame in frames.reversed()) {
                    restored = receiver.accept(frame)
                    if (restored != null) break
                    assertNull(receiver.accept(frame))
                }
                assertArrayEquals(expected, restored)
            }
            if (mode == "raw") QrsEncoder(expected.copyOf()).use { sender ->
                for (frame in frames) {
                    assertEquals(frame, sender.next()) // Byte-identical to frames accepted by the upstream decoder.
                    sender.next() // Interleaved random equation.
                }
            }
        }
    }
    @Test fun corruptFramesAndOversizedInflatedPayloadAreRejected() {
        QrsEncoder(ByteArray(300) { 42 }).use { sender ->
            val corrupt = Base64.getDecoder().decode(sender.next())
            corrupt[20] = 1
            QrsDecoder().use { decoder -> assertThrows(Exception::class.java) { decoder.accept(Base64.getEncoder().encodeToString(corrupt)) } }
        }
        val deflater = Deflater(); val large = ByteArray(QrsLimits.MAX_DATA + 1)
        val buffer = ByteArray(large.size)
        deflater.setInput(large); deflater.finish()
        val length = deflater.deflate(buffer); assertTrue(deflater.finished()); deflater.end()
        val blocks = (length + 499) / 500
        QrsDecoder().use { decoder ->
            assertThrows(Exception::class.java) {
                for (block in 0 until blocks) {
                    val data = ByteArray(500)
                    buffer.copyInto(data, 0, block * 500, minOf(length, (block + 1) * 500))
                    val wire = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(block)
                        .putInt(blocks).putInt(length).putInt(QrsLimits.checksum(large, blocks)).put(data).array()
                    decoder.accept(Base64.getEncoder().encodeToString(wire))
                }
            }
        }
    }
    @Test fun plainEnvelopeRequiresItsOwnMagicLengthAndDigest() {
        val plain = byteArrayOf(1, 2, 3, 4)
        val envelope = QrsBackup.wrapPlain(plain)
        assertFalse(QrsBackup.isEncrypted(envelope))
        assertArrayEquals(plain, QrsBackup.unwrapPlain(envelope))
        envelope[envelope.lastIndex] = 42
        assertThrows(IllegalStateException::class.java) { QrsBackup.unwrapPlain(envelope) }
        assertThrows(IllegalArgumentException::class.java) { QrsBackup.unwrapPlain(ByteArray(44)) }
        assertThrows(IllegalArgumentException::class.java) { QrsBackup.unframe(ByteArray(8)) }
    }
    @Test fun singleBlockAndCloseWipeOwnedInput() {
        val owned = byteArrayOf(1,2,3)
        val sender = QrsEncoder(owned)
        QrsDecoder().use { assertArrayEquals(owned, it.accept(sender.next())) }
        sender.close(); assertArrayEquals(ByteArray(3), owned)
        assertThrows(IllegalStateException::class.java) { sender.next() }
    }
}

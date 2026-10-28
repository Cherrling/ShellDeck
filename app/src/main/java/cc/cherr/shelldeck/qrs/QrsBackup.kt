package cc.cherr.shelldeck.qrs

import cc.cherr.shelldeck.backup.BackupCrypto
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Plain backups are permitted only in the explicitly selected, in-memory QR transfer path. */
object QrsBackup {
    private val plainMagic = "SHLDQRP1".toByteArray(Charsets.US_ASCII)
    private val encryptedMagic = "SHLDECKB".toByteArray(Charsets.US_ASCII)
    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= 53 && bytes.take(8).toByteArray().contentEquals(encryptedMagic)
    fun wrapPlain(plain: ByteArray): ByteArray {
        require(plain.size <= BackupCrypto.MAX_PLAINTEXT)
        return ByteBuffer.allocate(44 + plain.size).put(plainMagic).putInt(plain.size)
            .put(MessageDigest.getInstance("SHA-256").digest(plain)).put(plain).array()
    }
    fun unwrapPlain(file: ByteArray): ByteArray {
        require(file.size in 44..(BackupCrypto.MAX_PLAINTEXT + 44))
        val input = ByteBuffer.wrap(file)
        val magic = ByteArray(8); input.get(magic)
        require(magic.contentEquals(plainMagic) && input.int == file.size - 44)
        val expected = ByteArray(32); input.get(expected)
        val plain = ByteArray(input.remaining()); input.get(plain)
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(plain))) { plain.fill(0); error("备份校验失败") }
        return plain
    }
    fun frame(file: ByteArray, encrypted: Boolean): ByteArray {
        val meta = "{\"filename\":\"ShellDeck.${if (encrypted) "sdbak" else "qrsbackup"}\",\"contentType\":\"application/octet-stream\"}".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + meta.size + file.size).putInt(meta.size).put(meta).putInt(file.size).put(file).array()
    }
    fun unframe(data: ByteArray): ByteArray {
        require(data.size in 8..QrsLimits.MAX_DATA)
        val input = ByteBuffer.wrap(data)
        val metaSize = input.int
        require(metaSize in 2..2048 && metaSize <= input.remaining() - 4)
        input.position(input.position() + metaSize)
        val size = input.int
        require(size == input.remaining() && size in 1..BackupCrypto.MAX_FILE)
        return ByteArray(size).also { input.get(it) }
    }
}

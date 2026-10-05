package cc.cherr.shelldeck.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** v1: magic[8], version[1], salt[16], nonce[12], GCM ciphertext and tag[16]. Entire header is AAD. */
object BackupCrypto {
    const val MAX_PLAINTEXT = 8 * 1024 * 1024
    const val MAX_FILE = MAX_PLAINTEXT + 53
    private val magic = "SHLDECKB".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 37
    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8 && plaintext.size <= MAX_PLAINTEXT)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + byteArrayOf(1) + salt + nonce
        val derived = key(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally { derived.fill(0) }
    }
    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in (HEADER + 16)..MAX_FILE)
        require(file.copyOfRange(0, 8).contentEquals(magic) && file[8] == 1.toByte())
        val derived = key(password, file.copyOfRange(9, 25))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, file.copyOfRange(25, HEADER)))
            cipher.updateAAD(file, 0, HEADER)
            // Do not expose any plaintext until authentication of the whole file succeeds.
            cipher.doFinal(file, HEADER, file.size - HEADER)
        } finally { derived.fill(0) }
    }
}

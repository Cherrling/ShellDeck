package cc.cherr.shelldeck.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never recreates a missing decryption key: loss of the device key requires re-import. */
class CredentialVault {
    private val alias = "shelldeck.credentials.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "设备加密密钥不可用，请重新导入身份" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun encrypt(id: String, plaintext: ByteArray): ByteArray = SecretEnvelope.encrypt(key(true), id, plaintext)
    @Synchronized fun decrypt(id: String, blob: ByteArray): ByteArray = SecretEnvelope.decrypt(key(false), id, blob)
}

/** Versioned authenticated envelope; the identity ID prevents swapping ciphertext between rows. */
internal object SecretEnvelope {
    fun encrypt(key: SecretKey, id: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("shelldeck:v1:$id".toByteArray())
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }
    fun decrypt(key: SecretKey, id: String, blob: ByteArray): ByteArray {
        require(blob.size >= 29 && blob[0] == 1.toByte()) { "不支持的凭据格式" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD("shelldeck:v1:$id".toByteArray())
        return cipher.doFinal(blob, 13, blob.size - 13)
    }
}

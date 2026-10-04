package cc.cherr.shelldeck.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64

object SshKeys {
    @Synchronized fun configure() {
        // Android's built-in BC lacks algorithms SSHJ needs. Replace only BC, not AndroidKeyStore.
        if (Security.getProvider("BC") !is BouncyCastleProvider) {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
        SecurityUtils.setSecurityProvider("BC")
    }
    fun load(client: SSHClient, pem: ByteArray, passphrase: CharArray): KeyProvider {
        require(pem.size <= 256 * 1024) { "私钥文件过大" }
        val provider = client.loadKeys(pem.toString(Charsets.UTF_8), null,
            if (passphrase.isEmpty()) null else PasswordUtils.createOneOff(passphrase))
        // SSHJ loads lazily: force parsing/decryption before accepting an import.
        provider.getPrivate()
        provider.getPublic()
        return provider
    }
    fun fingerprint(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }
    fun algorithm(key: PublicKey): String = KeyType.fromKey(key).toString()
}

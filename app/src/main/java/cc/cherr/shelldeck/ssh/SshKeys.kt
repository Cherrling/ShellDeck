package cc.cherr.shelldeck.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
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
        return loadWithPassphraseRequest(client, pem) { passphrase.copyOf() }
    }
    /** The parser requests a password only when the key is encrypted. Never infer this from an auth failure. */
    fun loadWithPassphraseRequest(client: SSHClient, pem: ByteArray, request: () -> CharArray?): KeyProvider {
        require(pem.size <= 256 * 1024) { "私钥文件过大" }
        var password: CharArray? = null
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray? = request().also { password = it }
            override fun shouldRetry(resource: Resource<*>?) = false
        }
        try {
            val text = pem.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim() + "\n"
            val provider = client.loadKeys(normalizePkcs8(text) { finder.reqPassword(null) }, null, finder)
            // SSHJ loads lazily: force parsing/decryption before accepting an import.
            provider.getPrivate()
            provider.getPublic()
            return provider
        } finally { password?.fill('\u0000') }
    }
    // SSHJ 0.40.0's PKCS8 reader has no Ed25519 OID and assumes EC public points are embedded.
    // Let BC parse PKCS8 and derive public components, then reuse SSHJ's existing SSH key readers.
    private fun normalizePkcs8(text: String, request: () -> CharArray?): String {
        if (!text.startsWith("-----BEGIN PRIVATE KEY-----") &&
            !text.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----")) return text
        val info = org.bouncycastle.openssl.PEMParser(text.reader()).use { parser ->
            when (val parsed = parser.readObject()) {
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> parsed
                is org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo -> {
                    val password = request() ?: throw java.io.IOException("Private key unlock cancelled")
                    parsed.decryptPrivateKeyInfo(org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder()
                        .setProvider("BC").build(password))
                }
                else -> throw java.io.IOException("Unsupported PKCS8 private key")
            }
        }
        val key = org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(info)
        val label = when (key) {
            is org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters -> "RSA PRIVATE KEY"
            is org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters,
            is org.bouncycastle.crypto.params.ECPrivateKeyParameters -> "OPENSSH PRIVATE KEY"
            else -> throw java.io.IOException("Unsupported PKCS8 key algorithm")
        }
        val encoded = org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil.encodePrivateKey(key)
        return try {
            "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(encoded) + "\n-----END $label-----\n"
        } finally { encoded.fill(0) }
    }
    enum class GenerationType(val label: String, val jcaName: String) {
        ED25519("Ed25519", "Ed25519"), RSA3072("RSA 3072", "RSA")
    }

    /** The caller must wipe the returned private bytes after encrypting them into the vault. */
    fun generate(type: GenerationType, passphrase: CharArray): ByteArray {
        configure()
        val random = java.security.SecureRandom()
        val generator = java.security.KeyPairGenerator.getInstance(type.jcaName, "BC")
        if (type == GenerationType.RSA3072) generator.initialize(3072, random)
        val pair = generator.generateKeyPair()
        val encryptor = if (passphrase.isEmpty()) null else
            org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(org.bouncycastle.openssl.PKCS8Generator.AES_256_CBC)
                .setProvider("BC").setRandom(random).setPassword(passphrase)
                .setPRF(org.bouncycastle.openssl.PKCS8Generator.PRF_HMACSHA256)
                .setIterationCount(210_000).build()
        val pem = org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(pair.private, encryptor).generate()
        return try {
            ("-----BEGIN ${pem.type}-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pem.content) +
                "\n-----END ${pem.type}-----\n").toByteArray(Charsets.UTF_8)
        } finally { pem.content.fill(0) }
    }

    /** One authorized_keys-compatible OpenSSH public key, without a user-controlled comment. */
    fun publicKey(key: PublicKey): String = algorithm(key) + " " +
        Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key).compactData)

    fun fingerprint(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
    }
    fun algorithm(key: PublicKey): String = KeyType.fromKey(key).toString()
}

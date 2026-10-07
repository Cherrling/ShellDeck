package cc.cherr.shelldeck.backup

import cc.cherr.shelldeck.data.*
import cc.cherr.shelldeck.settings.*
import java.util.UUID
import java.util.concurrent.Callable

enum class RestoreChoice(val label: String) { KEEP("保留本机"), REPLACE("使用备份"), COPY("导入副本") }
data class RestoreEntry(val id: String, val label: String, val detail: String, val existingId: String?, val existingLabel: String?, val existingDetail: String? = null)
class PreparedRestore internal constructor(
    internal val identities: List<IdentityRecord>, internal val hosts: List<HostRecord>,
    val settings: AppSettings, val missingFont: Boolean,
    internal val beforeIdentities: List<IdentityRecord>, internal val beforeHosts: List<HostRecord>,
    val identityEntries: List<RestoreEntry>, val hostEntries: List<RestoreEntry>,
    internal val proxies: List<ProxyRecord> = emptyList(), internal val beforeProxies: List<ProxyRecord> = emptyList(),
    val proxyEntries: List<RestoreEntry> = emptyList(),
) : AutoCloseable {
    override fun close() { identities.forEach { it.encryptedKey.fill(0) }; proxies.forEach { it.encryptedCredentials.fill(0) } }
}
data class RestoreResult(val identities: Int, val hosts: Int, val settingsSaved: Boolean, val proxies: Int = 0)

class BackupRepository(
    private val database: ShellDeckDatabase, private val vault: CredentialVault,
    private val settingsStore: SettingsStore, private val fontIds: () -> Set<String>,
) {
    private val dao = database.records()
    fun export(password: CharArray): ByteArray {
        val snapshot = database.runInTransaction(Callable { Triple(dao.identities(), dao.hosts(), dao.proxies()) })
        val keys = mutableListOf<BackupIdentity>()
        val proxies = mutableListOf<BackupProxy>()
        try {
            snapshot.first.forEach { keys.add(BackupIdentity(it, vault.decrypt(it.id, it.encryptedKey))) }
            snapshot.third.forEach { proxies.add(BackupProxy(it, if (it.authenticated) vault.decrypt("proxy:${it.id}", it.encryptedCredentials) else byteArrayOf())) }
            val plain = BackupCodec.encode(BackupData(keys, snapshot.second, SettingsStore.encode(settingsStore.read()), proxies))
            return try { BackupCrypto.encrypt(plain, password) } finally { plain.fill(0) }
        } finally { keys.forEach { it.privateKey.fill(0) }; proxies.forEach { it.credentials.fill(0) } }
    }
    fun prepare(file: ByteArray, password: CharArray): PreparedRestore {
        val plain = BackupCrypto.decrypt(file, password)
        val wrapped = mutableListOf<IdentityRecord>()
        val proxies = mutableListOf<ProxyRecord>()
        try {
            BackupCodec.decode(plain).use { data ->
                val settings = SettingsStore.decode(data.settings)
                val missingFont = settings.fontId !in fontIds()
                data.identities.forEach { entry ->
                    // Preserve inner key passphrases. Only the old device envelope is replaced.
                    val record = entry.record
                    record.encryptedKey = vault.encrypt(record.id, entry.privateKey)
                    wrapped.add(record)
                }
                data.proxies.forEach { entry ->
                    entry.record.encryptedCredentials = if (entry.record.authenticated) vault.encrypt("proxy:${entry.record.id}", entry.credentials) else byteArrayOf()
                    proxies.add(entry.record)
                }
                val snapshot = database.runInTransaction(Callable { Triple(dao.identities(), dao.hosts(), dao.proxies()) })
                val identities = wrapped.map { key ->
                    val match = snapshot.first.find { it.id == key.id }
                        ?: snapshot.first.filter { it.fingerprint == key.fingerprint }.singleOrNull()
                        ?: snapshot.first.filter { it.label == key.label }.singleOrNull()
                    RestoreEntry(key.id, key.label, "${key.algorithm}\n${key.fingerprint}", match?.id, match?.label, match?.let { "${it.algorithm}\n${it.fingerprint}" })
                }
                val hosts = data.hosts.map { host ->
                    val match = snapshot.second.find { it.id == host.id }
                        ?: snapshot.second.filter { it.hostname == host.hostname && it.port == host.port && it.username == host.username }.singleOrNull()
                        ?: snapshot.second.filter { it.label == host.label }.singleOrNull()
                    RestoreEntry(host.id, host.label, "${host.username}@${host.hostname}:${host.port}\n协议：${host.protocol} · Mosh UDP：${host.moshPort.takeIf { it > 0 } ?: "自动"}\n启动命令：${host.startupCommand.ifBlank { "无" }}", match?.id, match?.label,
                        match?.let { "${it.username}@${it.hostname}:${it.port}\n协议：${it.protocol} · Mosh UDP：${it.moshPort.takeIf { it > 0 } ?: "自动"}\n启动命令：${it.startupCommand.ifBlank { "无" }}" })
                }
                val proxyEntries = proxies.map { proxy ->
                    val match = snapshot.third.find { it.id == proxy.id }
                        ?: snapshot.third.filter { it.label == proxy.label }.singleOrNull()
                    RestoreEntry(proxy.id, proxy.label, "${proxy.hostname}:${proxy.port}", match?.id, match?.label,
                        match?.let { "${it.hostname}:${it.port}" })
                }
                return PreparedRestore(wrapped, data.hosts, if (missingFont) settings.copy(fontId = "maple") else settings,
                    missingFont, snapshot.first, snapshot.second, identities, hosts, proxies, snapshot.third, proxyEntries)
            }
        } catch (failure: Exception) { wrapped.forEach { it.encryptedKey.fill(0) }; proxies.forEach { it.encryptedCredentials.fill(0) }; throw failure }
        finally { plain.fill(0) }
    }
    fun restore(plan: PreparedRestore, identities: Map<String, RestoreChoice>, hosts: Map<String, RestoreChoice>, restoreSettings: Boolean, proxies: Map<String, RestoreChoice> = emptyMap()): RestoreResult {
        var keyCount = 0; var hostCount = 0; var proxyCount = 0
        database.runInTransaction {
            check(sameIdentities(dao.identities(), plan.beforeIdentities) && sameHosts(dao.hosts(), plan.beforeHosts) && sameProxies(dao.proxies(), plan.beforeProxies)) {
                "Data changed since preview"
            }
            val identityMap = mutableMapOf<String, String>()
            val writtenKeys = mutableSetOf<String>(); val writtenHosts = mutableSetOf<String>()
            plan.identities.zip(plan.identityEntries).forEach { (source, entry) ->
                val choice = identities[source.id] ?: RestoreChoice.KEEP
                if (entry.existingId != null && choice == RestoreChoice.KEEP) identityMap[source.id] = entry.existingId
                else {
                    val id = destination(source.id, entry, choice)
                    check(writtenKeys.add(id)) { "Conflicting identity replacements" }
                    val bytes = vault.decrypt(source.id, source.encryptedKey)
                    val record = IdentityRecord().apply {
                        this.id = id; label = copyLabel(source.label, entry, choice)
                        algorithm = source.algorithm; fingerprint = source.fingerprint; publicKey = source.publicKey
                        encryptedKey = try { vault.encrypt(id, bytes) } finally { bytes.fill(0) }
                    }
                    dao.saveIdentity(record); identityMap[source.id] = id; keyCount++
                }
            }
            val proxyMap = mutableMapOf<String, String>()
            val writtenProxies = mutableSetOf<String>()
            plan.proxies.zip(plan.proxyEntries).forEach { (source, entry) ->
                val choice = proxies[source.id] ?: RestoreChoice.KEEP
                val id = destination(source.id, entry, choice)
                proxyMap[source.id] = id
                if (entry.existingId == null || choice != RestoreChoice.KEEP) {
                    check(writtenProxies.add(id)) { "Conflicting proxy replacements" }
                    val bytes = if (source.authenticated) vault.decrypt("proxy:${source.id}", source.encryptedCredentials) else byteArrayOf()
                    try {
                        dao.saveProxy(ProxyRecord().apply {
                            this.id = id; label = copyLabel(source.label, entry, choice); hostname = source.hostname; port = source.port
                            remoteDns = source.remoteDns; authenticated = source.authenticated
                            encryptedCredentials = if (authenticated) vault.encrypt("proxy:$id", bytes) else byteArrayOf()
                        }); proxyCount++
                    } finally { bytes.fill(0) }
                }
            }
            val hostMap = plan.hosts.zip(plan.hostEntries).associate { (source, entry) ->
                source.id to destination(source.id, entry, hosts[source.id] ?: RestoreChoice.KEEP)
            }
            plan.hosts.zip(plan.hostEntries).forEach { (source, entry) ->
                val choice = hosts[source.id] ?: RestoreChoice.KEEP
                if (entry.existingId == null || choice != RestoreChoice.KEEP) {
                    val id = hostMap.getValue(source.id)
                    check(writtenHosts.add(id)) { "Conflicting host replacements" }
                    dao.saveHost(HostRecord().apply {
                        this.id = id; label = copyLabel(source.label, entry, choice); hostname = source.hostname
                        port = source.port; username = source.username; identityId = source.identityId?.let { identityMap.getValue(it) }
                        proxyId = source.proxyId?.let { proxyMap.getValue(it) }
                        protocol = source.protocol; moshPort = source.moshPort
                        jumpHostId = source.jumpHostId?.let { hostMap.getValue(it) }; startupCommand = source.startupCommand; favorite = source.favorite; lastUsedAt = source.lastUsedAt
                    }); hostCount++
                }
            }
            val merged = dao.hosts().associateBy { it.id }
            merged.values.forEach { cc.cherr.shelldeck.ssh.jumpRoute(it, merged::get) }
        }
        // SharedPreferences is a separate store: never report that DB restore failed after it committed.
        val settingsSaved = !restoreSettings || runCatching { settingsStore.saveRestored(plan.settings) }.getOrDefault(false)
        return RestoreResult(keyCount, hostCount, settingsSaved, proxyCount)
    }
    private fun destination(id: String, entry: RestoreEntry, choice: RestoreChoice) = when {
        entry.existingId == null -> id
        choice == RestoreChoice.COPY -> UUID.randomUUID().toString()
        else -> entry.existingId
    }
    private fun copyLabel(label: String, entry: RestoreEntry, choice: RestoreChoice) =
        if (entry.existingId != null && choice == RestoreChoice.COPY) "$label（导入副本）" else label

    private fun sameIdentities(a: List<IdentityRecord>, b: List<IdentityRecord>): Boolean {
        val before = b.associateBy { it.id }
        return a.size == b.size && a.all { x -> before[x.id]?.let { y ->
            x.label == y.label && x.fingerprint == y.fingerprint && x.algorithm == y.algorithm &&
                x.publicKey == y.publicKey && x.encryptedKey.contentEquals(y.encryptedKey)
        } == true }
    }
    private fun sameProxies(a: List<ProxyRecord>, b: List<ProxyRecord>): Boolean {
        val before = b.associateBy { it.id }
        return a.size == b.size && a.all { x -> before[x.id]?.let { y ->
            x.label == y.label && x.hostname == y.hostname && x.port == y.port && x.remoteDns == y.remoteDns &&
                x.authenticated == y.authenticated && x.encryptedCredentials.contentEquals(y.encryptedCredentials)
        } == true }
    }
    private fun sameHosts(a: List<HostRecord>, b: List<HostRecord>): Boolean {
        val before = b.associateBy { it.id }
        return a.size == b.size && a.all { x -> before[x.id]?.let { y ->
            x.label == y.label && x.hostname == y.hostname && x.port == y.port && x.username == y.username &&
                x.protocol == y.protocol && x.moshPort == y.moshPort && x.proxyId == y.proxyId && x.jumpHostId == y.jumpHostId && x.identityId == y.identityId && x.startupCommand == y.startupCommand && x.favorite == y.favorite && x.lastUsedAt == y.lastUsedAt
        } == true }
    }
}

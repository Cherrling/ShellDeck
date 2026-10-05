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
) : AutoCloseable {
    override fun close() { identities.forEach { it.encryptedKey.fill(0) } }
}
data class RestoreResult(val identities: Int, val hosts: Int, val settingsSaved: Boolean)

class BackupRepository(
    private val database: ShellDeckDatabase, private val vault: CredentialVault,
    private val settingsStore: SettingsStore, private val fontIds: () -> Set<String>,
) {
    private val dao = database.records()
    fun export(password: CharArray): ByteArray {
        val snapshot = database.runInTransaction(Callable { dao.identities() to dao.hosts() })
        val keys = mutableListOf<BackupIdentity>()
        try {
            snapshot.first.forEach { keys.add(BackupIdentity(it, vault.decrypt(it.id, it.encryptedKey))) }
            val plain = BackupCodec.encode(BackupData(keys, snapshot.second, SettingsStore.encode(settingsStore.read())))
            return try { BackupCrypto.encrypt(plain, password) } finally { plain.fill(0) }
        } finally { keys.forEach { it.privateKey.fill(0) } }
    }
    fun prepare(file: ByteArray, password: CharArray): PreparedRestore {
        val plain = BackupCrypto.decrypt(file, password)
        val wrapped = mutableListOf<IdentityRecord>()
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
                val snapshot = database.runInTransaction(Callable { dao.identities() to dao.hosts() })
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
                    RestoreEntry(host.id, host.label, "${host.username}@${host.hostname}:${host.port}\n启动命令：${host.startupCommand.ifBlank { "无" }}", match?.id, match?.label,
                        match?.let { "${it.username}@${it.hostname}:${it.port}\n启动命令：${it.startupCommand.ifBlank { "无" }}" })
                }
                return PreparedRestore(wrapped, data.hosts, if (missingFont) settings.copy(fontId = "maple") else settings,
                    missingFont, snapshot.first, snapshot.second, identities, hosts)
            }
        } catch (failure: Exception) { wrapped.forEach { it.encryptedKey.fill(0) }; throw failure }
        finally { plain.fill(0) }
    }
    fun restore(plan: PreparedRestore, identities: Map<String, RestoreChoice>, hosts: Map<String, RestoreChoice>, restoreSettings: Boolean): RestoreResult {
        var keyCount = 0; var hostCount = 0
        database.runInTransaction {
            check(sameIdentities(dao.identities(), plan.beforeIdentities) && sameHosts(dao.hosts(), plan.beforeHosts)) {
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
                        jumpHostId = source.jumpHostId?.let { hostMap.getValue(it) }; startupCommand = source.startupCommand; favorite = source.favorite; lastUsedAt = source.lastUsedAt
                    }); hostCount++
                }
            }
            val merged = dao.hosts().associateBy { it.id }
            merged.values.forEach { cc.cherr.shelldeck.ssh.jumpRoute(it, merged::get) }
        }
        // SharedPreferences is a separate store: never report that DB restore failed after it committed.
        val settingsSaved = !restoreSettings || runCatching { settingsStore.saveRestored(plan.settings) }.getOrDefault(false)
        return RestoreResult(keyCount, hostCount, settingsSaved)
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
    private fun sameHosts(a: List<HostRecord>, b: List<HostRecord>): Boolean {
        val before = b.associateBy { it.id }
        return a.size == b.size && a.all { x -> before[x.id]?.let { y ->
            x.label == y.label && x.hostname == y.hostname && x.port == y.port && x.username == y.username &&
                x.jumpHostId == y.jumpHostId && x.identityId == y.identityId && x.startupCommand == y.startupCommand && x.favorite == y.favorite && x.lastUsedAt == y.lastUsedAt
        } == true }
    }
}

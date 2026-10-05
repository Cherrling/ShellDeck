package cc.cherr.shelldeck.sftp

import net.schmizz.sshj.sftp.*
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.util.UUID

/** One worker owns one SFTP channel. No shell commands or whole-file buffering. */
class SftpFiles(private val client: SFTPClient, private val cancelled: () -> Boolean = { false }) {
    data class Entry(val name: String, val path: String, val directory: Boolean, val link: Boolean, val size: Long)
    fun checkActive() { if (cancelled()) throw InterruptedIOException("Transfer cancelled") }
    fun list(path: String, canonicalPath: Boolean = false): Pair<String, List<Entry>> {
        checkActive()
        val canonical = if (canonicalPath) path else client.canonicalize(path)
        var count = 0
        val entries = client.ls(canonical, RemoteResourceFilter {
            checkActive(); require(++count <= 10_000) { "目录超过 10,000 项，请进入更小的目录。" }; true
        }).filter { it.name != "." && it.name != ".." }.map {
            Entry(it.name, child(canonical, it.name), it.isDirectory, it.attributes.type == FileMode.Type.SYMLINK, it.attributes.size)
        }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
        return canonical to entries
    }
    fun resolve(entry: Entry): Entry {
        checkActive()
        val attrs = client.stat(entry.path)
        require(attrs.type == FileMode.Type.DIRECTORY || attrs.type == FileMode.Type.REGULAR) { "仅支持目录和普通文件。" }
        return entry.copy(directory = attrs.type == FileMode.Type.DIRECTORY, size = attrs.size)
    }
    fun mkdir(directory: String, name: String) { checkActive(); client.mkdir(child(directory, name)) }
    fun rename(entry: Entry, name: String) {
        checkActive(); client.rename(entry.path, child(entry.path.substringBeforeLast('/'), name))
    }
    fun delete(entry: Entry) {
        checkActive()
        // lstat: deleting a symlink never follows it. Directories must be empty.
        if (client.lstat(entry.path).type == FileMode.Type.DIRECTORY) client.rmdir(entry.path) else client.rm(entry.path)
    }
    fun upload(directory: String, name: String, input: InputStream, progress: (Long) -> Unit) =
        upload(directory, name, input, false, progress)
    fun upload(directory: String, name: String, input: InputStream, overwrite: Boolean, progress: (Long) -> Unit) {
        val target = child(directory, name)
        val temporary = child(directory, ".shelldeck-" + UUID.randomUUID() + ".part")
        var created = false
        var published = false
        try {
            checkActive()
            client.open(temporary, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                FileAttributes.Builder().withPermissions(0b110000000).build()).use { remote ->
                created = true
                val bytes = ByteArray(32 * 1024)
                var position = 0L
                try {
                    while (true) {
                        checkActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        checkActive(); remote.write(position, bytes, 0, count)
                        position += count; progress(position)
                    }
                } finally { bytes.fill(0) }
            }
            checkActive()
            // Empty rename flags deliberately do not allow overwrite, including races after browsing.
            if (overwrite) client.rename(temporary, target, setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
            else client.rename(temporary, target)
            published = true
        } finally {
            if (created && !published) try { client.rm(temporary) } catch (_: Exception) {
                // A lost connection may leave this clearly named .part file; never delete the target.
            }
        }
    }
    fun download(path: String, output: OutputStream, progress: (Long) -> Unit) {
        checkActive()
        client.open(path).use { remote ->
            require(remote.fetchAttributes().type == FileMode.Type.REGULAR) { "仅支持下载普通文件。" }
            val bytes = ByteArray(32 * 1024)
            var position = 0L
            try {
                while (true) {
                    checkActive()
                    val count = remote.read(position, bytes, 0, bytes.size)
                    if (count < 0) break
                    checkActive(); output.write(bytes, 0, count)
                    position += count; progress(position)
                }
                checkActive(); output.flush()
            } finally { bytes.fill(0) }
        }
    }
    companion object {
        fun child(directory: String, name: String): String {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "请输入有效文件名（不能包含 /）。" }
            return directory.trimEnd('/') + "/" + name
        }
    }
}

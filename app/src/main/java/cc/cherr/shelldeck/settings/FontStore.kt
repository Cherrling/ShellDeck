package cc.cherr.shelldeck.settings

import android.content.Context
import androidx.core.content.edit
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import cc.cherr.shelldeck.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FontEntry(val id: String, val label: String, val imported: Boolean = false)
/** All disk/typeface operations run on the model's worker. Never retain Activity contexts. */
class FontStore(private val context: Context) {
    private val folder = File(context.filesDir, "terminal-fonts")
    private val prefs = context.getSharedPreferences("font_catalog", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, Typeface>("system" to Typeface.MONOSPACE)
    private fun imported(): List<FontEntry> = try {
        val array = JSONArray(prefs.getString("fonts", "[]")!!)
        List(array.length()) { i -> array.getJSONObject(i).let { FontEntry(it.getString("id"), it.getString("label"), true) } }
            .filter { it.id.matches(Regex("[0-9a-f-]{36}")) && File(folder, it.id).isFile }
    } catch (_: Exception) { emptyList() }
    fun entries() = listOf(FontEntry("system", "系统等宽字体"), FontEntry("maple", "Maple Mono NF CN")) + imported()
    private fun save(entries: List<FontEntry>) { prefs.edit { putString("fonts", JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
    }.toString()) } }
    fun load(id: String): Typeface = cache.getOrPut(id) {
        if (id == "maple") context.resources.getFont(R.font.maple_mono_nf_cn_regular)
        else {
            require(imported().any { it.id == id })
            Typeface.createFromFile(File(folder, id))
        }
    }
    fun import(uri: Uri): FontEntry {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "导入字体"
        require(name.endsWith(".ttf", true) || name.endsWith(".otf", true))
        folder.mkdirs()
        val entry = FontEntry(UUID.randomUUID().toString(), name.substringBeforeLast('.').take(80), true)
        val file = File(folder, entry.id)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 40 * 1024 * 1024); output.write(buffer, 0, count) }
            } }
            val face = Typeface.createFromFile(file)
            save(imported() + entry); cache[entry.id] = face
            return entry
        } catch (e: Exception) { file.delete(); throw e }
    }
    fun rename(id: String, label: String) {
        require(label.isNotBlank() && label.length <= 80)
        save(imported().map { if (it.id == id) it.copy(label = label.trim()) else it })
    }
    fun delete(id: String) {
        require(imported().any { it.id == id })
        check(File(folder, id).delete())
        cache.remove(id); save(imported().filterNot { it.id == id })
    }
}

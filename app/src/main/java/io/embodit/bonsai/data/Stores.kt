package io.embodit.bonsai.data

import android.content.Context
import android.util.Log
import io.embodit.bonsai.domain.GardenStore
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.SaveCodec
import io.embodit.bonsai.domain.SavedPot
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.ShelfItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/current.txt and files/shelf.txt, written atomically after every cut and watering (design persistence): a temp
 * file, a read-back check, then a rename; the previous good copy stays as *.bak.
 */
class FileGardenStore(context: Context) : GardenStore {
    private val dir = context.filesDir

    private fun write(name: String, text: String, check: (String) -> Boolean) {
        val file = File(dir, name)
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            check(check(tmp.readText())) { "read-back mismatch" }
            if (file.exists()) file.copyTo(File(dir, "$name.bak"), overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("SnipGrow", "SAVE $name failed", it) }
    }

    private fun read(name: String): List<String> = listOf(File(dir, name), File(dir, "$name.bak")).mapNotNull { f -> runCatching { if (f.exists()) f.readText() else null }.getOrNull() }

    override fun loadPot(): SavedPot? = read(CURRENT).firstNotNullOfOrNull { SaveCodec.decode(it) }

    override fun savePot(p: SavedPot) { val t = SaveCodec.encode(p); write(CURRENT, t) { SaveCodec.decode(it) != null } }

    override fun loadShelf(): List<ShelfItem> = read(SHELF).firstNotNullOfOrNull { SaveCodec.decodeShelf(it) } ?: emptyList()

    override fun saveShelf(items: List<ShelfItem>) { write(SHELF, SaveCodec.encodeShelf(items)) { SaveCodec.decodeShelf(it)?.size == items.size } }

    fun clearAll() { listOf(CURRENT, SHELF).forEach { File(dir, it).delete(); File(dir, "$it.bak").delete() } }

    companion object {
        const val CURRENT = "current.txt"
        const val SHELF = "shelf.txt"
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putInt("volume", s.volume)
            .putBoolean("sound", s.sound)
            .putBoolean("motion", s.motion)
            .putBoolean("outline", s.outline)
            .putBoolean("guideDone", s.guideDone)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            sound = prefs.getBoolean("sound", true),
            motion = prefs.getBoolean("motion", true),
            outline = prefs.getBoolean("outline", true),
            guideDone = prefs.getBoolean("guideDone", false),
        )
    }.getOrElse { Settings() }
}

package io.embodit.bonsai.data

import android.content.Context
import io.embodit.bonsai.domain.ForeseeStore
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.Saved
import io.embodit.bonsai.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** design persistence.current: species, day, yaw, T0 (the first-launch date, fixed after that) in SharedPreferences. */
class PrefsForeseeStore(context: Context) : ForeseeStore {
    private val prefs = context.getSharedPreferences("foresee", Context.MODE_PRIVATE)

    override fun load(): Saved? {
        if (!prefs.contains("t0")) return null
        return Saved(prefs.getString("species", null) ?: return null, prefs.getInt("day", 0), prefs.getFloat("yaw", 0f), prefs.getLong("t0", 0L))
    }

    override fun save(s: Saved) {
        prefs.edit().putString("species", s.species).putInt("day", s.day).putFloat("yaw", s.yawDeg).putLong("t0", s.t0EpochDay).commit()
    }

    fun clear() = prefs.edit().clear().commit()
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
            .putBoolean("dashed", s.dashed)
            .putBoolean("guideDone", s.guideDone)
            .commit()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            sound = prefs.getBoolean("sound", true),
            dashed = prefs.getBoolean("dashed", true),
            guideDone = prefs.getBoolean("guideDone", false),
        )
    }.getOrElse { Settings() }
}

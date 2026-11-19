package io.embodit.bonsai.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.bonsai.data.FileGardenStore
import io.embodit.bonsai.data.SettingsStore
import io.embodit.bonsai.domain.Garden
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.Sound
import io.embodit.bonsai.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

const val TAG = "SnipGrow"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.bonsai.ui.scene.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGardenStore(context)
    val window = WindowCtl()
    val sceneReady = MutableStateFlow(false)
    val garden = Garden(
        scope = scope,
        compute = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
        random = Random(System.nanoTime()),
    )

    /** True when the shown language is Chinese (system language followed unless the user picked one). */
    fun zh(s: Settings = settings.value): Boolean = when (s.language) {
        Language.ZH -> true
        Language.EN -> false
        Language.SYSTEM -> Locale.getDefault().language == "zh"
    }

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        garden.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Event sounds from assets/sounds (profile D51 / B30, design assets.sounds): every one is a cut of a CC0 recording
 * (build/sound-sources.md, tools/import_sounds.py). SoundPool, USAGE_GAME + SONIFICATION (design soundEngine). No loop, no
 * ambience (B24). One instance per kind at a time; the snip has three variants with a ±4 % pitch jitter; the singing-bowl
 * sounds sit 6 dB under the event sounds (B30).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastVariant = HashMap<Sound, Int>()
    private val lastStream = HashMap<Sound, Int>()

    private fun base(s: Sound) = when (s) {
        Sound.SNIP -> "snip"; Sound.DROP -> "drop"; Sound.WATER -> "water"; Sound.GROW -> "grow"
        Sound.DONE -> "done"; Sound.TICK -> "tick"; Sound.SEASON -> "season"
    }

    /** Relative gains: event sounds 1.0, the bowl sounds −6 dB (0.5), the soft tick −6 dB (B30 / design assets.sounds). */
    private fun gain(s: Sound) = when (s) { Sound.GROW, Sound.DONE -> 0.5f; Sound.TICK -> 0.5f; Sound.DROP -> 0.8f; else -> 1f }

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val found = ArrayList<Int>()
            for (name in listOf("${base(s)}.ogg", "${base(s)}_2.ogg", "${base(s)}_3.ogg")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val variants = ids[s] ?: return
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * gain(s)
        var v = if (variants.size > 1) random.nextInt(variants.size) else 0
        if (variants.size > 1 && v == lastVariant[s]) v = (v + 1) % variants.size
        lastVariant[s] = v
        val rate = if (s == Sound.SNIP) 0.96f + random.nextFloat() * 0.08f else 1f
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        lastStream[s]?.let { pool.stop(it) }
        lastStream[s] = pool.play(id, vol, vol, 1, 0, rate)
        Log.i(TAG, "SOUND $s v=$v vol=${"%.2f".format(vol)}")
    }

    fun release() = pool.release()

    companion object {
        /** Volume percent of each snap stop (Settings.volume 0..2). */
        val VOLUME_PERCENT = intArrayOf(35, 65, 100)
    }
}

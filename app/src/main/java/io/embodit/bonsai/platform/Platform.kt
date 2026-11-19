package io.embodit.bonsai.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.bonsai.data.PrefsForeseeStore
import io.embodit.bonsai.data.SettingsStore
import io.embodit.bonsai.domain.Foresee
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.MetaJson
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.Sound
import io.embodit.bonsai.domain.Species
import io.embodit.bonsai.domain.TreeMeta
import io.embodit.bonsai.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

const val TAG = "BonsaiAhead"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.bonsai.ui.scene.input.BoardInputLog.attach(this)
        graph = Graph(this)
        // D60 补: controller B = system BACK. It never exits or sends the scene to the background: the window closed = reopen
        // it, otherwise eaten (logged). Caught at the earliest layer of every window (BackKeys, copied from circuit-lab).
        BackKeys.handler = { where -> graph.onBack(where) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                BackKeys.wrap(a)
                val ca = a as? androidx.activity.ComponentActivity
                if (ca == null) { Log.w(TAG, "BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity"); return }
                ca.onBackPressedDispatcher.addCallback(ca, object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { BackKeys.handler("dispatcher:${a.javaClass.simpleName}") }
                })
                Log.i(TAG, "BACK guard on ${a.javaClass.simpleName}")
            }
            override fun onActivityPostCreated(a: android.app.Activity, b: android.os.Bundle?) { BackKeys.wrap(a); BackKeys.sweep("created") }
            override fun onActivityStarted(a: android.app.Activity) { BackKeys.sweep("started") }
            override fun onActivityResumed(a: android.app.Activity) { BackKeys.wrap(a); BackKeys.sweep("resumed") }
            override fun onActivityPostResumed(a: android.app.Activity) { BackKeys.sweep("post-resumed") }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = PrefsForeseeStore(context)
    val window = WindowCtl()
    val sceneReady = MutableStateFlow(false)

    /** meta.json of every species (anchors, rings, leaf colour), read once (tiny files). */
    private val metas = ConcurrentHashMap<String, TreeMeta>()
    fun meta(s: Species): TreeMeta? = metas[s.id] ?: runCatching {
        app.assets.open("trees/${s.file}/meta.json").use { MetaJson.treeMeta(it.readBytes().decodeToString()) }
    }.onFailure { Log.e(TAG, "META ${s.id}", it) }.getOrNull()?.also { metas[s.id] = it }

    val foresee = Foresee(
        scope = scope,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
        today = { LocalDate.now() },
        meta = { meta(it) },
    )

    /** True when the shown language is Chinese (system language followed unless the user picked one). */
    fun zh(s: Settings = settings.value): Boolean = when (s.language) {
        Language.ZH -> true
        Language.EN -> false
        Language.SYSTEM -> Locale.getDefault().language == "zh"
    }

    /** D60 补: BACK / controller B never exits; the window closed = reopen it beside the pots, otherwise eaten. */
    fun onBack(where: String) {
        when {
            foresee.state.value.tip != null -> { Log.i(TAG, "BACK $where -> close tip"); foresee.clearTip() }
            !window.panelOpen -> { Log.i(TAG, "BACK $where -> reopen window"); window.open("back") }
            else -> Log.i(TAG, "BACK $where -> eaten")
        }
    }

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        Species.ALL.forEach { meta(it) }
        foresee.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Event sounds from assets/sounds (profile D51 / B24, design assets.sounds): CC0 recordings cut by tools/import_sounds.py
 * (build/sound-sources.md). SoundPool, USAGE_GAME + SONIFICATION. No loop, no ambience. One instance per kind at a time,
 * the same sound is not repeated within 0.3 s while scrubbing (design muteRule).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastStream = HashMap<Sound, Int>()
    private val lastAt = HashMap<Sound, Long>()

    private fun base(s: Sound) = when (s) { Sound.TICK, Sound.TIP -> "tick"; Sound.SNIP -> "snip"; Sound.LEAVES -> "season" }
    private fun gain(s: Sound) = when (s) { Sound.TICK -> 0.6f; Sound.TIP -> 0.6f; Sound.SNIP -> 1f; Sound.LEAVES -> 0.9f }

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
        val now = SystemClock.uptimeMillis()
        if (now - (lastAt[s] ?: 0L) < 300) return
        lastAt[s] = now
        val variants = ids[s] ?: return
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f * gain(s)
        val id = variants[if (variants.size > 1) random.nextInt(variants.size) else 0]
        if (synchronized(loaded) { id !in loaded }) return
        val rate = if (s == Sound.TIP) 1.05f else if (s == Sound.SNIP) 0.96f + random.nextFloat() * 0.08f else 1f
        lastStream[s]?.let { pool.stop(it) }
        lastStream[s] = pool.play(id, vol, vol, 1, 0, rate)
        Log.i(TAG, "SOUND $s vol=${"%.2f".format(vol)}")
    }

    fun release() = pool.release()

    companion object {
        /** Volume percent of each snap stop (Settings.volume 0..2). */
        val VOLUME_PERCENT = intArrayOf(35, 65, 100)
    }
}

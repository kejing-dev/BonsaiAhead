package io.embodit.bonsai.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Language { SYSTEM, ZH, EN;
    fun next() = entries[(ordinal + 1) % entries.size]
}

data class Settings(
    val language: Language = Language.SYSTEM,
    /** Volume stop 0..2 (35 / 65 / 100 %). */
    val volume: Int = 2,
    val sound: Boolean = true,
    /** [虚线：开 / 关] (design interactions I7). */
    val dashed: Boolean = true,
    val guideDone: Boolean = false,
)

enum class Sound { TICK, SNIP, LEAVES, TIP }

enum class Page { FORESEE, PICK, SETTINGS }

/** What the volume keeps across launches (design persistence.current). */
data class Saved(val species: String, val day: Int, val yawDeg: Float, val t0EpochDay: Long)

interface ForeseeStore {
    fun load(): Saved?
    fun save(s: Saved)
}

/** The tip label of a tapped yellow shoot (design S06): which anchor, until when (wall clock ms). */
data class Tip(val anchor: Int, val month: Int, val untilMs: Long)

data class ForeseeState(
    val species: Species = Species.DEFAULT,
    val day: Int = 0,
    /** Both pots turn together (design I5), degrees; the scene eases to it on the wall clock. */
    val yawDeg: Float = 0f,
    val t0: LocalDate = LocalDate.of(2026, 11, 18),
    val page: Page = Page.FORESEE,
    val howTo: Boolean = false,
    val confirmExit: Boolean = false,
    /** 0 = no guide, 1 = drag the time, 2 = dashed line + yellow (design content.tone.guide). */
    val guideStep: Int = 0,
    val tip: Tip? = null,
    /** True while the time bar is held (scrubbing, S02). */
    val scrubbing: Boolean = false,
    val version: Int = 0,
)

/**
 * The single state source of the volume and the window (D38): every input (3D pinch / trigger, window controls, the debug
 * broadcast) calls the same functions here. Growth is a pure function of (species, T0, day): nothing is simulated
 * forward, so scrubbing back and forth twenty times is the same as once (D59).
 */
class Foresee(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val store: ForeseeStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onSound: (Sound) -> Unit,
    private val log: (String) -> Unit,
    private val today: () -> LocalDate,
    /** The anchors of a species (assets meta.json); empty until loaded. */
    private val meta: (Species) -> TreeMeta?,
) {
    private val _state = MutableStateFlow(ForeseeState())
    val state: StateFlow<ForeseeState> = _state.asStateFlow()
    private val growthCache = HashMap<Pair<String, Long>, Growth>()
    private var stopJob: Job? = null

    fun start() {
        val saved = runCatching { store.load() }.getOrNull()
        val t0 = saved?.let { LocalDate.ofEpochDay(it.t0EpochDay) } ?: today()
        val guide = if (settings().guideDone) 0 else 1
        set(ForeseeState(species = Species.byId(saved?.species), day = saved?.day?.coerceIn(0, 365) ?: 0, yawDeg = saved?.yawDeg ?: 0f, t0 = t0, guideStep = guide), "start saved=${saved != null}")
        if (saved == null) persist()
    }

    /** The growth model of the current species (null until its meta.json is loaded). */
    fun growth(st: ForeseeState = _state.value): Growth? {
        val m = meta(st.species) ?: return null
        return growthCache.getOrPut(st.species.id to st.t0.toEpochDay()) { Growth(st.species, st.t0, m.anchors, m.maxY) }
    }

    private fun set(s: ForeseeState, why: String) {
        _state.value = s.copy(version = _state.value.version + 1)
        log("STATE $why species=${s.species.id} day=${s.day} yaw=${s.yawDeg} page=${s.page} guide=${s.guideStep}")
    }

    private fun persist() { val s = _state.value; runCatching { store.save(Saved(s.species.id, s.day, s.yawDeg, s.t0.toEpochDay())) } }

    // ---- time ----

    /** Scrub to [day] (window slider / 3D bead / debug). Plays the snip / leaves sound when the scrub crosses those days. */
    fun scrub(day: Int, src: String) {
        stopJob?.cancel()
        val old = _state.value
        val d = day.coerceIn(0, 365)
        if (d == old.day && old.scrubbing) return
        crossSounds(old.day, d)
        set(old.copy(day = d, scrubbing = true, tip = null), "scrub $src")
        if (old.guideStep == 1 && d >= 30) advanceGuide()
    }

    /** Release of the slider / bead: snap to a stop within 6 days (design I1). */
    fun release(src: String) {
        val s = _state.value
        val snapped = Growth.snap(s.day)
        if (snapped != s.day || s.scrubbing) {
            if (snapped in Growth.STOPS) onSound(Sound.TICK)
            set(s.copy(day = snapped, scrubbing = false), "release $src")
            persist()
        }
    }

    /** Tap a tick (design I3): 0.6 s ease on the wall clock (D55), sounds as the days pass. */
    fun goStop(k: Int, src: String) {
        val target = Growth.STOPS[k.coerceIn(0, 3)]
        stopJob?.cancel()
        val from = _state.value.day
        val t0 = clock()
        onSound(Sound.TICK)
        stopJob = scope.launch {
            while (true) {
                val t = ((clock() - t0) / STOP_MS.toFloat()).coerceIn(0f, 1f)
                val e = 1 - (1 - t) * (1 - t) * (1 - t)
                val d = (from + (target - from) * e).toInt()
                val prev = _state.value.day
                if (d != prev) { crossSounds(prev, d); _state.value = _state.value.copy(day = d, tip = null, version = _state.value.version + 1) }
                if (t >= 1f) break
                delay(16)
            }
            set(_state.value.copy(day = target, scrubbing = false), "stop $k $src")
            if (_state.value.guideStep == 1 && target >= 30) advanceGuide()
            persist()
        }
    }

    private fun crossSounds(a: Int, b: Int) {
        if (a == b) return
        val g = growth() ?: return
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val sp = _state.value.species
        if ((lo until hi).any { val dt = g.date(it); dt.dayOfMonth == 15 && dt.monthValue in sp.pruneMonths }) onSound(Sound.SNIP)
        else if (!sp.evergreen && (lo until hi).any { g.leafState(it) != g.leafState(it + 1) && g.leafState(it + 1) != LeafState.GREEN }) onSound(Sound.LEAVES)
    }

    // ---- the pots ----

    /** Tap a pot: both turn 90° (design I5). */
    fun turnStep(src: String) {
        val s = _state.value
        onSound(Sound.TICK)
        set(s.copy(yawDeg = s.yawDeg + 90f), "turn $src")
        persist()
    }

    /** Drag a pot (continuous turn); [release] snaps back to the front within 5° (design I5). */
    fun turnTo(deg: Float) { _state.value = _state.value.copy(yawDeg = deg, version = _state.value.version + 1) }
    fun turnRelease() {
        val s = _state.value
        val m = ((s.yawDeg % 360f) + 360f) % 360f
        val snapped = if (m < 5f || m > 355f) s.yawDeg - (if (m > 180f) m - 360f else m) else s.yawDeg
        set(s.copy(yawDeg = snapped), "turn release"); persist()
    }

    /** Tap a shoot of the left pot (design I4): only yellow shoots answer. */
    fun tapShoot(anchor: Int, src: String) {
        val s = _state.value
        val g = growth() ?: return
        if (anchor !in g.vigour.indices || !g.shoot(Side.LEFT_ALONE, anchor, s.day).highlight) { log("TAP shoot $anchor ignored (not yellow) $src"); return }
        onSound(Sound.TIP)
        set(s.copy(tip = Tip(anchor, g.nextPruneMonth(s.day), clock() + TIP_MS)), "tip $anchor $src")
        if (s.guideStep == 2) finishGuide()
    }

    fun clearTip() { val s = _state.value; if (s.tip != null) set(s.copy(tip = null), "tip off") }

    // ---- window ----

    fun page(p: Page) { val s = _state.value; set(s.copy(page = p, howTo = false, confirmExit = false), "page $p") }
    fun howTo(on: Boolean) { val s = _state.value; set(s.copy(page = Page.SETTINGS, howTo = on, confirmExit = false), "howTo $on") }
    fun confirmExit(on: Boolean) { val s = _state.value; set(s.copy(confirmExit = on), "confirmExit $on") }

    /** Pick a species (design I6): the volume swaps the same second (D57); the day and the angle stay. */
    fun pick(sp: Species, src: String) {
        val s = _state.value
        if (s.species == sp) return
        onSound(Sound.TICK)
        set(s.copy(species = sp, tip = null), "pick ${sp.id} $src")
        persist()
    }

    fun toggleDashed() { val st = settings(); onSettings(st.copy(dashed = !st.dashed)); onSound(Sound.TICK); log("DASHED ${!st.dashed}") }

    // ---- guide ----

    fun advanceGuide() { val s = _state.value; if (s.guideStep == 1) set(s.copy(guideStep = 2), "guide 2") }
    fun finishGuide() {
        val s = _state.value
        if (s.guideStep != 0) set(s.copy(guideStep = 0), "guide done")
        onSettings(settings().copy(guideDone = true))
    }
    fun restartGuide() { onSettings(settings().copy(guideDone = false)); set(_state.value.copy(guideStep = 1), "guide restart") }

    // ---- lifecycle / debug ----

    fun onBackground() { stopJob?.cancel(); val s = _state.value; if (s.scrubbing) set(s.copy(scrubbing = false), "background"); persist() }

    /** Debug: a fresh install (T0 = today, day 0, elm, guide on). */
    fun reset(t0: LocalDate = today()) {
        stopJob?.cancel()
        onSettings(settings().copy(guideDone = false))
        set(ForeseeState(t0 = t0, guideStep = 1), "reset")
        persist()
    }

    fun setT0(t0: LocalDate) { set(_state.value.copy(t0 = t0), "t0 $t0"); persist() }
    fun setDay(d: Int, why: String) { set(_state.value.copy(day = d.coerceIn(0, 365), scrubbing = false, tip = null), why); persist() }
    fun setYaw(deg: Float) { set(_state.value.copy(yawDeg = deg), "yaw $deg"); persist() }
    fun setGuide(step: Int) { set(_state.value.copy(guideStep = step), "guide set $step") }

    fun dump(): String {
        val s = _state.value; val g = growth(s)
        return "species=${s.species.id} day=${s.day} date=${g?.date(s.day)} month=${g?.month(s.day)} leaves=${g?.leafState(s.day)} variant=${g?.variant(s.day)} toTrim=${g?.toTrim(s.day)} cut=${g?.recentCutMonth(s.day)} yaw=${s.yawDeg} page=${s.page} guide=${s.guideStep} t0=${s.t0}"
    }

    companion object {
        const val STOP_MS = 600L
        const val TIP_MS = 2500L
    }
}

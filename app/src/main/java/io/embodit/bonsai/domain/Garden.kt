package io.embodit.bonsai.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

enum class Language { SYSTEM, ZH, EN;
    fun next() = entries[(ordinal + 1) % entries.size]
}

data class Settings(
    val language: Language = Language.SYSTEM,
    /** Volume stop 0..2 (35 / 65 / 100 %). */
    val volume: Int = 2,
    val sound: Boolean = true,
    /** 动态: the breeze in the pads + falling leaves / snow. */
    val motion: Boolean = true,
    val outline: Boolean = true,
    val guideDone: Boolean = false,
)

enum class Sound { SNIP, DROP, WATER, GROW, DONE, TICK, SEASON }

enum class Page { TEND, SHELF, SETTINGS }

enum class Confirm { CHANGE, DELETE, EXIT }

/** Short-lived notes on the season sign / status line (D31: they only read results). */
enum class Note { TRUNK, VIEWING, SHELF_FULL }

data class Selection(val start: Int, val tapped: Int, val ids: Set<Int>, val site: Int)

/** A cut branch falling to the sand (scene animation, 0.6 s fall + 1.2 s rest + 0.4 s shrink). */
data class Fall(val nodes: List<NodeV>, val siteId: Int, val startMs: Long, val yawDeg: Float)

/** The 1.0 s growth tween of the last watering (D55: wall clock). */
data class Growth(val startMs: Long, val newIds: Set<Int>, val padBefore: Map<Int, Double>, val weak: Boolean)

data class GardenState(
    val ready: Boolean = false,
    val form: Form = Form.SHAKAN,
    val year: Int = 0,
    val score: Int = 0,
    val scoreDelta: Int = 0,
    val deltaAt: Long = 0L,
    val weakNext: Boolean = false,
    val weakNow: Boolean = false,
    val nearWeak: Boolean = false,
    val crowded: Boolean = false,
    val cutsThisYear: Int = 0,
    val totalCuts: Int = 0,
    val selection: Selection? = null,
    val guideStep: Int = 0,
    val look: Season = Season.SUMMER,
    val done: Boolean = false,
    val doneCard: Boolean = false,
    val name: Int = 0,
    val shelved: Boolean = false,
    val canUndo: Boolean = false,
    val undoBlocked: Boolean = false,
    val busyUntil: Long = 0L,
    val growth: Growth? = null,
    val fall: Fall? = null,
    val note: Note? = null,
    val noteAt: Long = 0L,
    val viewing: ShelfItem? = null,
    val confirm: Confirm? = null,
    val picking: Boolean = false,
    val pickForm: Form = Form.SHAKAN,
    val shelf: List<ShelfItem> = emptyList(),
    val shelfIndex: Int = 0,
    val shelfHighlight: Long = 0L,
    val page: Page = Page.TEND,
    val howTo: Boolean = false,
    /** Turntable target yaw (degrees, + = the pot's front turns to the user's right, design turnButtons.direction). */
    val yawDeg: Float = 0f,
    val yawAnimMs: Long = 0L,
    val turnedTotal: Float = 0f,
    val spinStartMs: Long = 0L,
    /** Bumped on every change of the tree's structure; the scene rebuilds from [view]. */
    val version: Int = 0,
    val view: List<NodeV> = emptyList(),
    val threshold: Int = 75,
    val waterings: Int = 0,
    val freezeMs: Long? = null,
) {
    val busy: Boolean get() = false
    val resting: Boolean get() = weakNext || weakNow
}

interface GardenStore {
    fun loadPot(): SavedPot?
    fun savePot(p: SavedPot)
    fun loadShelf(): List<ShelfItem>
    fun saveShelf(items: List<ShelfItem>)
}

/**
 * The single state source (design container.notes: one process-wide store; the control window changes it, the volume's
 * System reads it, D38). Every input — real pinch, button, controller, debug command — enters through the same calls.
 */
class Garden(
    private val scope: CoroutineScope,
    private val compute: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GardenStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onSound: (Sound) -> Unit,
    private val log: (String) -> Unit,
    private val random: Random = Random(System.nanoTime()),
) {
    private val _state = MutableStateFlow(GardenState())
    val state: StateFlow<GardenState> = _state.asStateFlow()

    var tree: Tree = Tree.create(Form.SHAKAN, 1); private set
    private var meta = PotMeta()
    private val undo = ArrayList<Tree.Snapshot>()
    private var lastWaterYear = -1
    private var busyUntil = 0L
    private var doneJob: Job? = null

    private fun set(f: (GardenState) -> GardenState) { _state.value = f(_state.value) }

    // ------------------------------------------------------------------ start / save

    fun start() {
        val shelf = store.loadShelf()
        val saved = store.loadPot()
        if (saved != null) {
            tree = saved.tree; meta = saved.meta
            log("START restored form=${tree.form.id} seed=${tree.seed} year=${tree.year} nodes=${tree.nodes.size}")
        } else {
            newTree(Form.SHAKAN, random.nextLong(1, Long.MAX_VALUE / 4), guide = !settings().guideDone)
            log("START first pot form=${tree.form.id} seed=${tree.seed}")
        }
        set { it.copy(ready = true, shelf = shelf) }
        publish(structure = true)
    }

    private fun newTree(form: Form, seed: Long, guide: Boolean) {
        tree = Tree.create(form, seed)
        val longs = tree.nodes.filter { it.isLong && it.alive }.map { it.id }
        meta = PotMeta(guideStep = if (guide) 1 else 0, startLongs = longs, name = Names.pick(random))
        undo.clear()
        lastWaterYear = -1
    }

    private fun save() {
        val s = SavedPot(tree, meta)
        runCatching { store.savePot(s) }.onFailure { log("SAVE failed $it") }
    }

    /** Recomputes the derived fields; [structure] = the tree's nodes changed (scene rebuild). */
    private fun publish(structure: Boolean, scoreBefore: Int? = null) {
        val sc = tree.score()
        val now = clock()
        val st = _state.value
        set {
            it.copy(
                form = tree.form, year = tree.year, score = sc,
                scoreDelta = if (scoreBefore != null && scoreBefore != sc) sc - scoreBefore else it.scoreDelta,
                deltaAt = if (scoreBefore != null && scoreBefore != sc) now else it.deltaAt,
                weakNext = tree.weakNext, weakNow = tree.weakNow,
                nearWeak = !tree.weakNext && tree.cutRatio >= tree.tuning.nearWeak,
                crowded = tree.crowded, cutsThisYear = undo.size, totalCuts = tree.cuts,
                guideStep = meta.guideStep, look = meta.look, name = meta.name, shelved = meta.shelvedId != 0L,
                canUndo = undo.isNotEmpty(), undoBlocked = undo.isEmpty() && lastWaterYear == tree.year && tree.year > 0,
                threshold = tree.tuning.threshold, waterings = tree.year,
                version = if (structure) st.version + 1 else st.version,
                view = if (structure) tree.view() else st.view,
                busyUntil = busyUntil,
            )
        }
    }

    fun onBackground() {
        save()
        if (_state.value.selection != null) set { it.copy(selection = null) }
        log("LIFECYCLE saved")
    }

    // ------------------------------------------------------------------ taps in the volume

    private fun locked(): Boolean = clock() < busyUntil

    /** A tap on a branch segment or a pad (node id) — real pinch, controller trigger or debug `tap <id>`. */
    fun tapNode(id: Int) {
        val st = _state.value
        if (st.viewing != null) { note(Note.VIEWING); return }
        if (locked()) { log("TAP node=$id ignored: growing"); return }
        val n = tree.nodes.getOrNull(id) ?: return
        if (!n.alive) return
        if (n.kind == Kind.TRUNK) { note(Note.TRUNK); log("TAP trunk -> keep the trunk"); return }
        val sel = st.selection
        if (sel != null && id in sel.ids) { confirmCut("tap-red"); return }
        val start = tree.selectFrom(id) ?: return
        select(start, id)
    }

    private fun select(start: Int, tapped: Int) {
        val ids = tree.subtree(start).toSet()
        val site = tree.nodes[start].parent
        set { it.copy(selection = Selection(start, tapped, ids, site)) }
        log("SELECT start=$start tapped=$tapped nodes=${ids.size}")
    }

    fun tapRing() { if (_state.value.selection != null) confirmCut("tap-ring") }

    /** Pot / stand / tray tap = cancel the preview (design rules.cut.confirm). */
    fun tapBase(what: String) {
        if (_state.value.selection != null) { cancelSelection(); log("CANCEL by $what") }
    }

    fun cancelSelection() { set { it.copy(selection = null) } }

    fun stepIn() {
        val sel = _state.value.selection ?: return
        val s = tree.stepIn(sel.start)
        if (s != sel.start) { select(s, sel.tapped); onSound(Sound.TICK) }
    }

    fun stepOut() {
        val sel = _state.value.selection ?: return
        val s = tree.stepOut(sel.start, sel.tapped)
        if (s != sel.start) { select(s, sel.tapped); onSound(Sound.TICK) }
    }

    fun confirmCut(why: String) {
        val sel = _state.value.selection ?: return
        if (locked()) return
        val before = tree.score()
        val fallen = tree.view().filter { it.id in sel.ids }
        undo += tree.snapshot()
        val r = tree.cut(sel.start)
        log("CUT why=$why start=${sel.start} nodes=${r.ids.size} vol=${"%.5f".format(r.vol)} ratio=${"%.2f".format(r.ratio)} weakNext=${tree.weakNext}")
        val now = clock()
        set { it.copy(selection = null, fall = Fall(fallen, r.site, now, it.yawDeg)) }
        onSound(Sound.SNIP)
        scope.launch { delay(FALL_MS); onSound(Sound.DROP) }
        if (meta.guideStep == 1 && meta.startLongs.all { id -> tree.nodes.getOrNull(id)?.alive != true }) advanceGuide()
        publish(structure = true, scoreBefore = before)
        save()
        checkDone()
    }

    fun undo() {
        if (undo.isEmpty() || _state.value.viewing != null || locked()) return
        val before = tree.score()
        tree.restore(undo.removeAt(undo.lastIndex))
        onSound(Sound.TICK)
        log("UNDO left=${undo.size}")
        publish(structure = true, scoreBefore = before)
        save()
    }

    // ------------------------------------------------------------------ water = one year

    /** The can in the volume and the window's [浇水 · 长一年] call this same function (D43, M3). */
    fun water(source: String) {
        val st = _state.value
        if (st.viewing != null) { note(Note.VIEWING); return }
        if (locked()) { log("WATER ignored: still growing"); return }
        val now = clock()
        busyUntil = now + GROW_MS
        val before = tree.score()
        set { it.copy(selection = null, busyUntil = busyUntil) }
        onSound(Sound.WATER); onSound(Sound.GROW)
        scope.launch {
            val t0 = clock()
            val g = withContext(compute) { tree.water() }
            undo.clear()
            lastWaterYear = tree.year
            meta = meta.copy(look = Season.SUMMER)
            log("WATER src=$source year=${tree.year} weak=${g.weak} grown=${g.grown} budded=${g.budded} crowded=${g.crowded} new=${g.newIds.size} in ${clock() - t0} ms")
            set { it.copy(growth = Growth(now, g.newIds.toSet(), g.padBefore, g.weak)) }
            if (meta.guideStep == 2) advanceGuide()
            publish(structure = true, scoreBefore = before)
            save()
            delay(maxOf(0L, busyUntil - clock()))
            checkDone()
        }
    }

    // ------------------------------------------------------------------ turntable

    /** 3D ◀ ▶ (design turnButtons): 45° per press, 0.4 s; [dir] +1 = ▶ (front turns to the user's right). */
    fun turnStep(dir: Int) {
        val st = _state.value
        var y = st.yawDeg + 45f * dir
        if (abs(normDeg(y)) < SNAP_DEG) y = Math.round(y / 360f) * 360f
        set { it.copy(yawDeg = y, yawAnimMs = clock(), turnedTotal = it.turnedTotal + 45f) }
        onSound(Sound.TICK)
        if (meta.guideStep == 3 && _state.value.turnedTotal >= 45f) finishGuide()
        log("TURN step dir=$dir yaw=$y")
    }

    /** Pinch-drag on the pot / stand: absolute yaw while held (no animation), snap to front on release. */
    fun turnDrag(deg: Float) {
        set { it.copy(yawDeg = deg, yawAnimMs = 0L) }
    }

    fun turnRelease() {
        val st = _state.value
        val turned = st.turnedTotal + 45f
        var y = st.yawDeg
        if (abs(normDeg(y)) < SNAP_DEG) y = Math.round(y / 360f) * 360f
        set { it.copy(yawDeg = y, yawAnimMs = clock(), turnedTotal = turned) }
        if (meta.guideStep == 3) finishGuide()
        log("TURN release yaw=$y")
    }

    // ------------------------------------------------------------------ looks / outline / guide

    fun cycleLook() {
        if (_state.value.viewing != null) return
        meta = meta.copy(look = meta.look.next())
        onSound(Sound.SEASON)
        publish(structure = false)
        save()
    }

    fun setLook(s: Season) { meta = meta.copy(look = s); publish(structure = false) }

    fun toggleOutline() { val s = settings(); onSettings(s.copy(outline = !s.outline)); onSound(Sound.TICK) }

    private fun advanceGuide() {
        meta = meta.copy(guideStep = meta.guideStep + 1)
        log("GUIDE step ${meta.guideStep}")
        publish(structure = false)
    }

    fun skipGuide() { finishGuide() }

    private fun finishGuide() {
        if (meta.guideStep == 0) return
        meta = meta.copy(guideStep = 0)
        onSettings(settings().copy(guideDone = true))
        log("GUIDE done")
        publish(structure = false)
        save()
    }

    fun guideTo(step: Int) { meta = meta.copy(guideStep = step); publish(structure = false) }

    // ------------------------------------------------------------------ done / shelf

    private fun checkDone() {
        val st = _state.value
        if (meta.doneShown || st.viewing != null) return
        if (st.score >= tree.tuning.threshold && tree.year >= 2 && !tree.resting) {
            meta = meta.copy(doneShown = true)
            set { it.copy(done = true, doneCard = true, page = Page.TEND, spinStartMs = clock()) }
            onSound(Sound.DONE)
            log("DONE score=${st.score} year=${tree.year} name=${meta.name}")
            publish(structure = false)
            save()
        }
    }

    fun forceDone() {
        meta = meta.copy(doneShown = true)
        set { it.copy(done = true, doneCard = true, page = Page.TEND, spinStartMs = clock()) }
        onSound(Sound.DONE)
        publish(structure = false)
    }

    fun rename() { meta = meta.copy(name = Names.pick(random, meta.name)); onSound(Sound.TICK); publish(structure = false); save() }

    fun keepGrowing() { set { it.copy(doneCard = false) } }

    /** [上架子]: the snapshot goes to the shelf, the shelf page opens on it, highlighted (D57). */
    fun putOnShelf() {
        val st = _state.value
        if (st.shelf.size >= SHELF_MAX) { note(Note.SHELF_FULL); return }
        val item = ShelfItem(clock().coerceAtLeast(1L) + random.nextLong(1, 1000), tree.form, meta.name, tree.year, st.score, System.currentTimeMillis(), tree.view())
        val shelf = st.shelf + item
        store.saveShelf(shelf)
        meta = meta.copy(shelvedId = item.id)
        set { it.copy(shelf = shelf, shelfIndex = shelf.lastIndex, shelfHighlight = item.id, page = Page.SHELF, doneCard = false) }
        onSound(Sound.TICK)
        log("SHELF put id=${item.id} size=${shelf.size}")
        publish(structure = false)
        save()
    }

    fun shelfStep(d: Int) {
        val st = _state.value
        val n = shelfCards(st)
        if (n == 0) return
        set { it.copy(shelfIndex = ((it.shelfIndex + d) % n + n) % n, shelfHighlight = 0L) }
        onSound(Sound.TICK)
    }

    /** The shelf shows "your pot (done, not on the shelf)" first when it applies (design R2-05). */
    fun shelfCards(st: GardenState = _state.value): Int = st.shelf.size + if (pendingOwn(st)) 1 else 0

    fun pendingOwn(st: GardenState = _state.value) = st.done && !st.shelved

    fun view(index: Int) {
        val st = _state.value
        val item = st.shelf.getOrNull(index) ?: return
        set { it.copy(viewing = item, selection = null, page = Page.TEND, doneCard = false, confirm = null) }
        onSound(Sound.TICK)
        log("VIEW shelf id=${item.id}")
    }

    fun backToMine() { set { it.copy(viewing = null, confirm = null) }; onSound(Sound.TICK) }

    fun deleteViewed() {
        val st = _state.value
        val item = st.viewing ?: return
        val shelf = st.shelf.filter { it.id != item.id }
        store.saveShelf(shelf)
        if (meta.shelvedId == item.id) meta = meta.copy(shelvedId = 0L)
        set { it.copy(shelf = shelf, viewing = null, confirm = null, page = Page.SHELF, shelfIndex = 0) }
        log("SHELF delete id=${item.id}")
        publish(structure = false)
        save()
    }

    /** [换一盆] (shelf page): ask first when this pot is not on the shelf (design I13). */
    fun askChange() {
        set { it.copy(confirm = Confirm.CHANGE) }
    }

    fun openPick() { set { it.copy(confirm = null, picking = true, pickForm = tree.form) } }

    fun pickForm(f: Form) { set { it.copy(pickForm = f) }; onSound(Sound.TICK) }

    fun cancelPick() { set { it.copy(picking = false) } }

    /** [开始养]: a new pot appears at once in the volume, the window goes back to the tend page (D57). */
    fun startPot(form: Form = _state.value.pickForm, seed: Long = random.nextLong(1, Long.MAX_VALUE / 4)) {
        newTree(form, seed, guide = false)
        busyUntil = 0L
        set { it.copy(picking = false, confirm = null, viewing = null, selection = null, done = false, doneCard = false, page = Page.TEND, yawDeg = 0f, yawAnimMs = clock(), growth = null, fall = null) }
        onSound(Sound.TICK)
        log("POT new form=${form.id} seed=$seed")
        publish(structure = true)
        save()
    }

    // ------------------------------------------------------------------ window pages

    fun page(p: Page) { set { it.copy(page = p, howTo = false, confirm = if (it.confirm == Confirm.DELETE) it.confirm else null) } }
    fun howTo(on: Boolean) { set { it.copy(howTo = on) } }
    fun ask(c: Confirm?) { set { it.copy(confirm = c) } }

    // ------------------------------------------------------------------ notes, debug

    private fun note(n: Note) { set { it.copy(note = n, noteAt = clock()) }; log("NOTE $n") }

    fun freeze(ms: Long?) { set { it.copy(freezeMs = ms) } }

    fun reset() {
        set { it.copy(selection = null, confirm = null, picking = false, viewing = null, doneCard = false, howTo = false, note = null, fall = null, growth = null, freezeMs = null) }
    }

    /** Debug `autoprune n`: the auto player's cuts then a watering, n times, through the same cut / water paths. */
    suspend fun autoYears(n: Int) {
        repeat(n) {
            val before = tree.score()
            if (tree.autoprune() > 0) { undo.clear(); publish(structure = true, scoreBefore = before) }
            water("autoprune")
            delay(GROW_MS + 150)
        }
    }

    fun randomCuts(k: Int) { val before = tree.score(); tree.randomcut(k); publish(structure = true, scoreBefore = before); save() }

    fun cutAbout(ratio: Double) { val before = tree.score(); undo += tree.snapshot(); tree.cutAbout(ratio); log("CUT about ${"%.2f".format(tree.cutRatio)} weakNext=${tree.weakNext}"); publish(structure = true, scoreBefore = before); save() }

    fun dump(): String {
        val pads = tree.pads()
        return "form=${tree.form.id} seed=${tree.seed} year=${tree.year} nodes=${tree.nodes.count { it.alive }} segs=${tree.segments()} pads=${pads.size} " +
            "score=${tree.score()} cuts=${tree.cuts} weakNext=${tree.weakNext} weakNow=${tree.weakNow} cutRatio=${"%.2f".format(tree.cutRatio)} " +
            "inEnvelope=${Envelope.allInside(tree)} guide=${meta.guideStep} look=${meta.look} done=${meta.doneShown}"
    }

    companion object {
        const val GROW_MS = 1000L
        const val FALL_MS = 600L
        const val SNAP_DEG = 5f
        const val SHELF_MAX = 6
        const val NOTE_MS = 1500L
        const val DELTA_MS = 1500L

        fun normDeg(d: Float): Float { var x = d % 360f; if (x > 180f) x -= 360f; if (x < -180f) x += 360f; return x }
    }
}

/** canopyEnvelope checks (M7): every pad's / segment's box in the cylinder (tree-local design units). */
object Envelope {
    fun padInside(n: NodeV): Boolean {
        val h = Math.hypot(n.pos.x, n.pos.z)
        return h + n.padR <= Tree.ENV_R + 1e-6 && n.pos.y + 0.55 * n.padR <= Tree.ENV_TOP + 1e-6 && n.pos.y - 0.55 * n.padR >= Tree.ENV_BOTTOM - 1e-6
    }

    fun nodeInside(n: NodeV): Boolean = Math.hypot(n.pos.x, n.pos.z) <= Tree.ENV_R && n.pos.y <= Tree.ENV_TOP && n.pos.y >= Tree.ENV_BOTTOM

    fun allInside(t: Tree): Boolean = t.view().all { (it.padR <= 0 || padInside(it)) && nodeInside(it) }
}

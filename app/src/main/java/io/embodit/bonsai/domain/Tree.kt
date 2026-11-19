package io.embodit.bonsai.domain

import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The growth / cut / score parameters (design rules.score.tuning: "阈值、缓 35%、vigor、回芽数、padR 倍数 … 研发按
 * leniencyTests 调"). Tuned with the build station's sim (same code, 30 seeds x 3 forms x 3 strategies) and checked by
 * LeniencyTest; the final values and the curves are in apps/bonsai/build/ui-audit.md. One place, per form overrides.
 */
data class Tuning(
    val k: Double = 0.62,            // the start tree grows at 62 % of the outline (rules.generator 60–70 %)
    val want: IntArray,              // start tips per target lobe
    val pad0: Double = 0.045, val padJ: Double = 0.008,
    val step: Double = 0.03,         // one node per watering, x vigor
    val vigTop: Double = 1.4, val shade: Double = 0.7,
    val padGrow: Double = 1.2, val padMax: Double = 0.07, val padMaxLow: Double = 0.04,
    val fork: Int = 1, val forkAng: Double = 0.7,
    val frontR: Double = 0.025,      // the new tip's own small pad; the old pad stays (foliage along the shoot)
    val budR: Double = 0.025, val budLen: Double = 0.015,
    val longN: Int = 3, val longPad: Double = 0.035, val longStep: Double = 0.03, val longFrac: Double = 1.0, val longAge: Int = 2,
    val weak: Double = 0.35, val nearWeak: Double = 0.30,
    val merge: Double = 0.6, val segMax: Int = 240, val padCap: Int = 64,
    val outW: Double = 0.55, val upW: Double = 0.0, val noise: Double = 0.25,
    /** 像不像 needed for 成景 (design 75; the cascade 80, design rules.season.tuningFromSample option). */
    val threshold: Int = 75,
) {
    companion object {
        fun of(form: Form): Tuning = when (form) {
            Form.CHOKKAN -> Tuning(want = intArrayOf(7, 5, 2), fork = 3, step = 0.02)
            Form.SHAKAN -> Tuning(want = intArrayOf(5, 3, 2), fork = 2, step = 0.015, padMax = 0.075, budR = 0.032)
            Form.KENGAI -> Tuning(want = intArrayOf(2, 1, 1, 1), fork = 3, step = 0.015, longFrac = 0.3, padMax = 0.06, threshold = 80)
        }
    }
}

enum class Kind { TRUNK, PRIMARY, SHOOT }

/** One node of the tree graph; the segment parent -> this node is "this node's segment". */
class Node(
    val id: Int,
    val parent: Int,
    var pos: D3,
    val kind: Kind,
    val born: Int,
    val line: Int,
    val isLong: Boolean,
    var alive: Boolean = true,
    var padR: Double = 0.0,
    val r: Double = 0.003,
    var sinceFork: Int = 0,
) {
    fun copy() = Node(id, parent, pos, kind, born, line, isLong, alive, padR, r, sinceFork)
}

class CutSite(val nodeId: Int, val year: Int, var budded: Boolean = false) {
    fun copy() = CutSite(nodeId, year, budded)
}

/** What one watering did, for the 1.0 s tween and the tests. */
data class GrowResult(val weak: Boolean, val grown: Int, val budded: Int, val crowded: Boolean, val newIds: List<Int>, val padBefore: Map<Int, Double>)

data class CutResult(val ids: List<Int>, val vol: Double, val ratio: Double, val start: Int, val site: Int)

/** Deterministic 64-bit LCG (same as the tuning sim): a seed and the same actions give the same tree. */
class Rng(var state: Long) {
    fun next(): Double {
        state = state * 6364136223846793005L + 1442695040888963407L
        return (state ushr 40).toDouble() / 16777216.0
    }

    companion object { fun seeded(seed: Long) = Rng(seed * 6364136223846793005L + 1442695040888963407L) }
}

/**
 * The bonsai (design rules): a node graph that grows one year per watering, gets cut, buds from its cuts, rests a year
 * after a heavy cut and is scored against its form's outline. Pure Kotlin, design units, tree-local.
 */
class Tree private constructor(val form: Form, val seed: Long, private val rng: Rng) {
    val tuning: Tuning = Tuning.of(form)
    val nodes = ArrayList<Node>()
    val cutSites = ArrayList<CutSite>()
    var year = 0; private set
    var weakNext = false; private set
    var weakNow = false; private set
    var cuts = 0; private set
    var yearStartVol = 0.0; private set
    var cutVolThisYear = 0.0; private set
    var crowded = false; private set

    val rngState: Long get() = rng.state

    /** The weak / rest flag the UI shows: a heavy cut this year (next watering rests) or resting this year. */
    val resting: Boolean get() = weakNext || weakNow
    val cutRatio: Double get() = if (yearStartVol > 0) cutVolThisYear / yearStartVol else 0.0

    // ------------------------------------------------------------------ structure

    private fun add(parent: Int, pos: D3, kind: Kind, r: Double = 0.003, padR: Double = 0.0, isLong: Boolean = false, sinceFork: Int = 0, line: Int = year): Node {
        val n = Node(nodes.size, parent, pos, kind, year, line, isLong, padR = padR, r = r, sinceFork = sinceFork)
        nodes += n
        return n
    }

    fun kids(id: Int): List<Node> = nodes.filter { it.alive && it.parent == id }

    fun tips(): List<Node> {
        val has = HashSet<Int>()
        for (n in nodes) if (n.alive && n.parent >= 0) has += n.parent
        return nodes.filter { it.alive && it.kind != Kind.TRUNK && it.id !in has }
    }

    fun pads(): List<Node> = nodes.filter { it.alive && it.padR > 0 }
    fun padVol(): Double = nodes.sumOf { if (it.alive) padVolOf(it.padR) else 0.0 }
    fun segments(): Int = nodes.count { it.alive && it.parent >= 0 }

    fun subtree(id: Int): List<Int> {
        val out = arrayListOf(id)
        var i = 0
        while (i < out.size) { val p = out[i]; for (m in nodes) if (m.alive && m.parent == p) out += m.id; i++ }
        return out
    }

    fun volOf(id: Int): Double = subtree(id).sumOf { padVolOf(nodes[it].padR) }

    private fun clampEnv(p: D3): D3 = Companion.clampEnv(p)
    private fun padCapAt(p: D3): Double = padCap(p, tuning)

    // ------------------------------------------------------------------ generation

    private fun gen() {
        val f = form
        var prev = -1
        f.trunk.forEachIndexed { i, p -> prev = add(prev, p, Kind.TRUNK, r = f.baseR * (1 - i * 0.13)).id }
        val trunk = nodes.subList(1, nodes.size).toList()
        val k = tuning.k
        f.target.forEachIndexed { ti, lobe ->
            val want = tuning.want[ti]
            for (j in 0 until want) {
                val a = (j.toDouble() / want + rng.next() * 0.3) * PI * 2
                val u = 0.35 + 0.45 * rng.next()
                var tip = D3(lobe.c.x + cos(a) * u * lobe.r.x, lobe.c.y + (rng.next() - 0.5) * lobe.r.y * 0.5, lobe.c.z + sin(a) * u * lobe.r.z)
                var anchor = trunk[0]
                for (n in trunk) if ((n.pos - tip).length < (anchor.pos - tip).length) anchor = n
                tip = anchor.pos + (tip - anchor.pos) * k
                val mid0 = anchor.pos + (tip - anchor.pos) * 0.5
                val mid = D3(mid0.x, mid0.y + 0.01, mid0.z)
                val p1 = add(anchor.id, mid, Kind.PRIMARY, r = 0.0045)
                add(p1.id, tip, Kind.SHOOT, r = 0.003, padR = tuning.pad0 + rng.next() * tuning.padJ)
            }
        }
        // two long shoots (pale) off the two highest tips, poking >= 6 cm out of the outline: the first snip (design generator)
        val top = tips().sortedByDescending { it.pos.y }.take(2)
        val axis = f.trunk[4]
        top.forEachIndexed { i, t ->
            var p = t
            val o = D3(t.pos.x - axis.x + if (i > 0) 0.15 else -0.15, 0.0, t.pos.z - axis.z + 0.1).norm()
            val dir = (o * 0.8 + D3(0.0, 0.75, 0.0)).norm()
            repeat(4) { p = add(p.id, clampEnv(p.pos + dir * 0.035), Kind.SHOOT, r = 0.0022, isLong = true) }
            p.padR = tuning.longPad
        }
        mergePads()
        yearStartVol = padVol()
    }

    // ------------------------------------------------------------------ growth

    private fun shaded(t: Node): Boolean = nodes.any { n ->
        n.alive && n.padR > 0 && n !== t && n.pos.y > t.pos.y + 0.01 && n.pos.y < t.pos.y + 0.12 && hypot(n.pos.x - t.pos.x, n.pos.z - t.pos.z) < n.padR
    }

    /** One watering = one year (design rules.season): computed at once, the scene tweens it over 1.0 s. */
    fun water(): GrowResult {
        val before = HashMap<Int, Double>()
        for (n in nodes) if (n.alive && n.padR > 0) before[n.id] = n.padR
        val firstNew = nodes.size
        year++
        if (weakNext) {
            weakNext = false; weakNow = true
            endYear()
            return GrowResult(true, 0, 0, crowded, emptyList(), before)
        }
        weakNow = false
        val tp = tuning
        val tips = tips()
        crowded = segments() >= tp.segMax
        val hs = tips.map { it.pos.y }.sortedDescending()
        val hTop = if (hs.isEmpty()) 0.0 else hs[max(0, ceil(tips.size * tp.longFrac).toInt() - 1)]
        val axis = form.trunk[4]
        var grown = 0
        var budded = 0
        val order = if (crowded) tips.sortedByDescending { it.pos.y }.take(20) else tips
        val hardCap = tp.segMax + 100
        for (t in order) {
            if (segments() >= hardCap) break
            val vig = if (t.pos.y >= hTop) tp.vigTop else if (shaded(t)) tp.shade else 1.0
            val par = nodes[t.parent]
            val d0 = (t.pos - par.pos).norm()
            val dir = if (t.isLong) (d0 + D3(0.0, 0.2, 0.0)).norm() else {
                val out = D3(t.pos.x - axis.x + 1e-4, 0.0, t.pos.z - axis.z).norm()
                val flat = D3(d0.x, d0.y * 0.2, d0.z).norm()
                val nx = (rng.next() - 0.5) * tp.noise
                val ny = (rng.next() - 0.5) * tp.noise * 0.3
                val nz = (rng.next() - 0.5) * tp.noise
                (flat * (1 - tp.outW) + out * tp.outW + D3(nx, tp.upW + ny, nz)).norm()
            }
            val np = clampEnv(t.pos + dir * ((if (t.isLong) tp.longStep else tp.step) * vig))
            if ((np - t.pos).length < 0.005) { t.padR = min(t.padR * tp.padGrow, padCapAt(t.pos)); continue }
            val n = add(t.id, np, Kind.SHOOT, r = 0.0025, padR = if (!t.isLong) tp.frontR else (if (t.padR > 0) t.padR else tp.budR),
                isLong = t.isLong, sinceFork = t.sinceFork + 1, line = t.line)
            if (t.isLong) t.padR = 0.0
            grown++
            if (!t.isLong && n.sinceFork >= tp.fork) {
                n.sinceFork = 0
                val side = D3(-dir.z, 0.0, dir.x).norm()
                val sgn = if (rng.next() < 0.5) 1.0 else -1.0
                val q = clampEnv(t.pos + (dir + side * (sgn * tp.forkAng)).norm() * (tp.step * vig))
                add(t.id, q, Kind.SHOOT, r = 0.002, padR = tp.budR, line = t.line)
            }
            if (!t.isLong && vig >= 1.3 && year - t.line >= tp.longAge) {
                var p = n
                val d2 = (dir + D3(0.0, 0.9, 0.0)).norm()
                for (s in 0 until tp.longN) {
                    val q = clampEnv(p.pos + d2 * tp.longStep)
                    if ((q - p.pos).length < 0.005) break
                    p = add(p.id, q, Kind.SHOOT, r = 0.002, isLong = true)
                }
                if (p !== n) p.padR = tp.longPad
            }
        }
        // buds wake under the cuts of the last two years: two new shoots each (design R1-02, M4)
        for (cs in cutSites) {
            if (cs.budded || year - cs.year > 2) continue
            if (segments() >= hardCap) break
            cs.budded = true
            val base = nodes.getOrNull(cs.nodeId) ?: continue
            if (!base.alive) continue
            val pp = if (base.parent >= 0) nodes[base.parent].pos else D3(0.0, base.pos.y - 0.01, 0.0)
            val ax = (base.pos - pp).norm()
            val side = D3(-ax.z, 0.0, ax.x).norm()
            for (s in intArrayOf(-1, 1)) {
                val d = (ax * 0.5 + side * (s * 0.87)).norm()
                val q = clampEnv(base.pos + d * tp.budLen + D3(0.0, 0.006, 0.0))
                add(base.id, q, Kind.SHOOT, r = 0.002, padR = tp.budR)
                budded++
            }
        }
        for (n in nodes) if (n.alive && n.padR > 0) n.padR = min(n.padR * tp.padGrow, padCapAt(n.pos))
        mergePads()
        endYear()
        return GrowResult(false, grown, budded, crowded, (firstNew until nodes.size).toList(), before)
    }

    private fun endYear() { yearStartVol = padVol(); cutVolThisYear = 0.0 }

    /** Neighbouring pads closer than 0.6 x (r1 + r2) merge (volume adds up); over 64 pads the two smallest merge. */
    private fun mergePads() {
        val pads = nodes.filter { it.alive && it.padR > 0 }
        for (i in pads.indices) for (j in i + 1 until pads.size) {
            val a = pads[i]; val b = pads[j]
            if (a.padR <= 0 || b.padR <= 0) continue
            if ((a.pos - b.pos).length < tuning.merge * (a.padR + b.padR)) {
                val big = if (a.padR >= b.padR) a else b
                val small = if (big === a) b else a
                big.padR = min(cbrt(big.padR * big.padR * big.padR + small.padR * small.padR * small.padR), padCapAt(big.pos))
                small.padR = 0.0
            }
        }
        var live = nodes.filter { it.alive && it.padR > 0 }
        while (live.size > tuning.padCap) {
            live = live.sortedBy { it.padR }
            val a = live[0]; val b = live[1]
            b.padR = min(cbrt(a.padR * a.padR * a.padR + b.padR * b.padR * b.padR), padCapAt(b.pos))
            a.padR = 0.0
            live = live.drop(1)
        }
    }

    // ------------------------------------------------------------------ select / cut

    /** Tapping node [id] selects from the nearest fork outward (design rules.cut.select). Trunk -> null. */
    fun selectFrom(id: Int): Int? {
        var n = nodes.getOrNull(id) ?: return null
        if (!n.alive || n.kind == Kind.TRUNK) return null
        while (n.parent >= 0) {
            val p = nodes[n.parent]
            if (p.kind == Kind.TRUNK || kids(p.id).size > 1 || (!p.isLong && n.isLong)) break
            n = p
        }
        return n.id
    }

    /** [往里一节]: the selection starts one node nearer the trunk (at most the primary branch's base). */
    fun stepIn(start: Int): Int {
        val n = nodes.getOrNull(start) ?: return start
        val p = nodes.getOrNull(n.parent) ?: return start
        return if (p.kind == Kind.TRUNK) start else p.id
    }

    /** [往外一节]: back toward the tap ([path] = the nodes from the tapped one up to the first selection). */
    fun stepOut(start: Int, tapped: Int): Int {
        if (start == tapped) return start
        var n = nodes.getOrNull(tapped) ?: return start
        while (n.parent >= 0 && n.parent != start) n = nodes[n.parent]
        return if (n.parent == start) n.id else start
    }

    fun cut(id: Int): CutResult {
        val ids = subtree(id)
        val vol = ids.sumOf { padVolOf(nodes[it].padR) }
        ids.forEach { nodes[it].alive = false }
        val start = nodes[id]
        cutSites += CutSite(start.parent, year)
        cutVolThisYear += vol
        cuts++
        val ratio = cutRatio
        if (ratio > tuning.weak) weakNext = true
        return CutResult(ids, vol, ratio, id, start.parent)
    }

    // ------------------------------------------------------------------ undo (this year's cuts only)

    class Snapshot(val nodes: List<Node>, val sites: List<CutSite>, val cutVol: Double, val cuts: Int, val weakNext: Boolean)

    fun snapshot() = Snapshot(nodes.map { it.copy() }, cutSites.map { it.copy() }, cutVolThisYear, cuts, weakNext)

    fun restore(s: Snapshot) {
        nodes.clear(); s.nodes.forEach { nodes += it.copy() }
        cutSites.clear(); s.sites.forEach { cutSites += it.copy() }
        cutVolThisYear = s.cutVol; cuts = s.cuts; weakNext = s.weakNext
    }

    // ------------------------------------------------------------------ score

    /** 像不像 0–100: Dice of leafy voxels vs the outline dilated one voxel (design rules.score), 32³ over the envelope. */
    fun score(): Int = Score.of(this)

    // ------------------------------------------------------------------ test players (LeniencyTest, debug autoprune / randomcut)

    /** The auto player: cut every long shoot and every pad outside the outline back to the outline, <= 30 % a year. */
    fun autoprune(): Int {
        val cands = ArrayList<Int>()
        for (t in tips()) if (t.isLong) selectFrom(t.id)?.let { cands += it }
        for (n in nodes) if (n.alive && n.padR > 0 && !n.isLong && !form.inTarget(n.pos, 1.05)) {
            var s = n.id
            while (true) {
                val m = nodes[s]
                val p = nodes.getOrNull(m.parent)
                if (p == null || p.kind == Kind.TRUNK || form.inTarget(p.pos, 1.0) || kids(p.id).size > 1) break
                s = p.id
            }
            cands += s
        }
        var made = 0
        for (s in cands) {
            val n = nodes[s]
            if (!n.alive) continue
            if (yearStartVol > 0 && (cutVolThisYear + volOf(s)) / yearStartVol > 0.30) continue
            cut(s); made++
        }
        return made
    }

    /** Random player: [k] random cuts a year, <= 30 % a year. */
    fun randomcut(k: Int): Int {
        var made = 0
        for (i in 0 until k) {
            val ts = tips()
            if (ts.isEmpty()) return made
            val t = ts[floor(rng.next() * ts.size).toInt()]
            val s = selectFrom(t.id) ?: continue
            if (yearStartVol > 0 && (cutVolThisYear + volOf(s)) / yearStartVol > 0.30) continue
            cut(s); made++
        }
        return made
    }

    /** One cut of about [ratio] of this year's pad volume (debug `cut <ratio>`, LeniencyTest "一刀剪掉 50%"). */
    fun cutAbout(ratio: Double): CutResult? {
        val total = yearStartVol
        if (total <= 0) return null
        // the primary branch whose subtree is closest to the wanted share
        val primaries = nodes.filter { it.alive && it.kind == Kind.PRIMARY }
        var best: Node? = null
        var bestD = Double.MAX_VALUE
        for (p in primaries) { val d = kotlin.math.abs(volOf(p.id) / total - ratio); if (d < bestD) { bestD = d; best = p } }
        // several primaries if one is not enough
        var made: CutResult? = null
        while (cutRatio < ratio) {
            val cand = nodes.filter { it.alive && it.kind == Kind.PRIMARY }.maxByOrNull { volOf(it.id) } ?: break
            made = cut(if (best != null && best.alive) best.id else cand.id)
            best = null
        }
        return made
    }

    companion object {
        const val ENV_R = 0.28
        const val ENV_TOP = 0.48
        const val ENV_BOTTOM = -0.122

        fun padVolOf(r: Double): Double = if (r > 0) 4.0 / 3.0 * PI * r * r * r * 0.55 else 0.0

        /** canopyEnvelope for a tip (design spatialLayout): main zone <= 0.22 off the axis, low zone 0.19–0.24, y -0.10…0.44. */
        fun clampEnv(p: D3): D3 {
            val low = p.y < 0
            val rmax = if (low) 0.24 else 0.22
            val h = hypot(p.x, p.z)
            var x = p.x; var z = p.z
            if (h > rmax) { x *= rmax / h; z *= rmax / h }
            else if (low && h < 0.19) { if (h < 1e-4) x = -0.19 else { val k = 0.19 / h; x *= k; z *= k } }
            return D3(x, p.y.coerceIn(-0.10, 0.44), z)
        }

        /** The pad radius cap at [p]: the envelope's side and top, and 4 cm in the low zone (rules.season.envelope). */
        fun padCap(p: D3, t: Tuning): Double {
            val low = p.y < 0
            val side = ENV_R - hypot(p.x, p.z)
            val topRoom = (ENV_TOP - p.y) / 0.55
            return max(0.012, min(min(if (low) t.padMaxLow else t.padMax, side), topRoom))
        }

        fun create(form: Form, seed: Long): Tree = Tree(form, seed, Rng.seeded(seed)).also { it.gen() }

        /** Rebuilds a saved tree (persistence); [rngState] continues the same random sequence. */
        fun restore(form: Form, seed: Long, rngState: Long, year: Int, weakNext: Boolean, weakNow: Boolean, cuts: Int,
                    yearStartVol: Double, cutVol: Double, nodes: List<Node>, sites: List<CutSite>): Tree {
            val t = Tree(form, seed, Rng(rngState))
            t.nodes += nodes; t.cutSites += sites
            t.year = year; t.weakNext = weakNext; t.weakNow = weakNow; t.cuts = cuts
            t.yearStartVol = yearStartVol; t.cutVolThisYear = cutVol
            return t
        }
    }
}

/** The 像不像 voxel score (design rules.score, R1-03). */
object Score {
    const val N = 32

    fun of(t: Tree): Int {
        val f = t.form
        val x0 = -Tree.ENV_R; val y0 = Tree.ENV_BOTTOM
        val s = 2 * Tree.ENV_R / N; val sy = (Tree.ENV_TOP - Tree.ENV_BOTTOM) / N
        val l = BooleanArray(N * N * N); val tt = BooleanArray(N * N * N)
        fun ix(i: Int, j: Int, k: Int) = (i * N + j) * N + k
        for (p in t.nodes) {
            if (!p.alive || p.padR <= 0) continue
            val r = p.padR; val ry = r * 0.55
            val i0 = max(0, floor((p.pos.x - r - x0) / s).toInt()); val i1 = min(N - 1, floor((p.pos.x + r - x0) / s).toInt())
            val j0 = max(0, floor((p.pos.y - ry - y0) / sy).toInt()); val j1 = min(N - 1, floor((p.pos.y + ry - y0) / sy).toInt())
            val k0 = max(0, floor((p.pos.z - r - x0) / s).toInt()); val k1 = min(N - 1, floor((p.pos.z + r - x0) / s).toInt())
            for (i in i0..i1) for (j in j0..j1) for (k in k0..k1) {
                val x = x0 + (i + 0.5) * s; val y = y0 + (j + 0.5) * sy; val z = x0 + (k + 0.5) * s
                val dx = (x - p.pos.x) / r; val dy = (y - p.pos.y) / ry; val dz = (z - p.pos.z) / r
                if (dx * dx + dy * dy + dz * dz <= 1) l[ix(i, j, k)] = true
            }
        }
        for (n in t.nodes) if (n.alive && n.isLong && n.parent >= 0) {
            val a = t.nodes[n.parent].pos; val b = n.pos
            var u = 0.0
            while (u <= 1.0001) {
                val q = a + (b - a) * u
                val i = floor((q.x - x0) / s).toInt(); val j = floor((q.y - y0) / sy).toInt(); val k = floor((q.z - x0) / s).toInt()
                if (i in 0 until N && j in 0 until N && k in 0 until N) l[ix(i, j, k)] = true
                u += 0.25
            }
        }
        var nL = 0; var nT = 0; var inter = 0
        for (i in 0 until N) for (j in 0 until N) for (k in 0 until N) {
            if (f.inTarget(D3(x0 + (i + 0.5) * s, y0 + (j + 0.5) * sy, x0 + (k + 0.5) * s))) { tt[ix(i, j, k)] = true; nT++ }
            if (l[ix(i, j, k)]) nL++
        }
        for (i in 0 until N) for (j in 0 until N) for (k in 0 until N) {
            if (!l[ix(i, j, k)]) continue
            var hit = false
            loop@ for (a in -1..1) for (b in -1..1) for (c in -1..1) {
                val ii = i + a; val jj = j + b; val kk = k + c
                if (ii in 0 until N && jj in 0 until N && kk in 0 until N && tt[ix(ii, jj, kk)]) { hit = true; break@loop }
            }
            if (hit) inter++
        }
        return min(100, Math.round(200.0 * inter / max(1, nL + nT)).toInt())
    }
}

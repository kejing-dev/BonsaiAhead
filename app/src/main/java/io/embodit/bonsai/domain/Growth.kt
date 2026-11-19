package io.embodit.bonsai.domain

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** One anchor on the Tripo foliage shell (assets/trees/<file>/meta.json): position on the tree (m) + outward direction. */
data class Anchor(val p: V3, val out: V3)

/** A dashed ring around one foliage layer (meta.json rings). */
data class Ring(val x: Float, val z: Float, val y: Float, val r: Float)

/** What tools/prepare_trees.py measured for one species. */
data class TreeMeta(val leaf: V3, val anchors: List<Anchor>, val rings: List<Ring>, val maxY: Float)

/** Untrimmed vs trimmed-as-advised. */
enum class Side { LEFT_ALONE, TRIMMED }

/** One shoot on one day: its length (cm), whether it is a yellow "to trim" shoot, and the day of its last cut (-1 none). */
data class ShootDay(val lengthCm: Float, val highlight: Boolean, val lastCut: Int)

/**
 * The growth model of design rules (pure: same species + anchors + T0 + day -> same answer; GrowthTest).
 *
 * - calendar: day 0 = T0 (the first launch date), 0..365; the month of a day is the month of T0 + day.
 * - vigour: 0.6 + 0.8 x height share of the anchor (apical dominance) x a fixed 0.85..1.15 per-anchor factor.
 * - left alone: length = vigour x sum of the daily rate (cmPerMonth / 30), capped at vigour x yearlyMaxCm.
 * - trimmed: on the 15th of every prune month a shoot longer than 2 internodes (candles: 1) is cut back to keepFraction.
 * - highlight: only on the left-alone pot, when the shoot is past [Species.trimAtCm].
 */
class Growth(val species: Species, val t0: LocalDate, anchors: List<Anchor>, maxY: Float) {
    val vigour: FloatArray = FloatArray(anchors.size) { i ->
        val h = (anchors[i].p.y / maxY).coerceIn(0f, 1f)
        (0.6f + 0.8f * h) * (0.85f + 0.3f * jitter(i))
    }

    /** Month 1..12 of a day. */
    fun month(day: Int): Int = t0.plusDays(day.toLong()).monthValue
    fun date(day: Int): LocalDate = t0.plusDays(day.toLong())

    private val dailyRate = FloatArray(366) { d -> species.cmPerMonth[month(d) - 1] / 30f }
    private val isPruneDay = BooleanArray(366) { d -> val dt = date(d); dt.dayOfMonth == 15 && dt.monthValue in species.pruneMonths }

    fun leftAlone(i: Int, day: Int): Float {
        val d = day.coerceIn(0, 365); var l = 0f
        for (k in 0 until d) l += dailyRate[k]
        return minOf(l * vigour[i], species.yearlyMaxCm * vigour[i])
    }

    /** Trimmed length and the day of the last cut (-1). */
    fun trimmed(i: Int, day: Int): Pair<Float, Int> {
        val d = day.coerceIn(0, 365); var l = 0f; var last = -1
        val minLen = species.internodeCm * if (species.shape == ShootShape.CANDLE) 1f else 2f
        for (k in 0 until d) {
            l += dailyRate[k] * vigour[i]
            if (isPruneDay[k] && l > minLen) { l *= species.keepFraction; last = k }
        }
        return minOf(l, species.yearlyMaxCm * vigour[i]) to last
    }

    fun shoot(side: Side, i: Int, day: Int): ShootDay = when (side) {
        Side.LEFT_ALONE -> leftAlone(i, day).let { ShootDay(it, it > species.trimAtCm, -1) }
        Side.TRIMMED -> trimmed(i, day).let { (l, c) -> ShootDay(l, false, c) }
    }

    /** Yellow shoots on the left pot (the "该剪 n 处" tag). */
    fun toTrim(day: Int): Int = vigour.indices.count { leftAlone(it, day) > species.trimAtCm }

    /** The month of the last cut on the right pot if it was within [CUT_SHOW_DAYS] days (the "5 月剪过" tag), else null. */
    fun recentCutMonth(day: Int): Int? {
        val last = vigour.indices.maxOfOrNull { trimmed(it, day).second } ?: -1
        return if (last >= 0 && day - last <= CUT_SHOW_DAYS) month(last) else null
    }

    /** The next prune month at or after [day] (for the tip label); wraps to next year. */
    fun nextPruneMonth(day: Int): Int {
        val m = month(day)
        return species.pruneMonths.sorted().firstOrNull { it >= m } ?: species.pruneMonths.min()
    }

    fun leafState(day: Int): LeafState = species.leafState(month(day))
    fun variant(day: Int): LeafVariant? = species.variant(month(day))

    companion object {
        const val CUT_SHOW_DAYS = 10
        /** The four stops (design content.stops). */
        val STOPS = intArrayOf(0, 30, 182, 365)
        fun jitter(i: Int): Float { var s = (i * 2654435761L + 97L) and 0xffffffffL; s = (s * 1103515245L + 12345L) and 0x7fffffffL; return (s % 1000) / 1000f }

        /** Snap a released day to a stop within 6 days (design interactions I1). */
        fun snap(day: Int): Int = STOPS.firstOrNull { abs(it - day) <= 6 } ?: day.coerceIn(0, 365)

        /** Time bar (design spatialLayout.timeBar): the four stops are evenly spaced, linear in between. */
        val TICK_X = floatArrayOf(-0.48f, -0.16f, 0.16f, 0.48f)
        fun dayToX(day: Int): Float {
            val d = day.coerceIn(0, 365)
            for (k in 0 until 3) if (d <= STOPS[k + 1]) return TICK_X[k] + (TICK_X[k + 1] - TICK_X[k]) * (d - STOPS[k]) / (STOPS[k + 1] - STOPS[k]).toFloat()
            return TICK_X[3]
        }
        fun xToDay(x: Float): Int {
            val c = x.coerceIn(TICK_X[0], TICK_X[3])
            for (k in 0 until 3) if (c <= TICK_X[k + 1]) return (STOPS[k] + (STOPS[k + 1] - STOPS[k]) * (c - TICK_X[k]) / (TICK_X[k + 1] - TICK_X[k])).roundToInt()
            return 365
        }
    }
}

/**
 * Where the nodes of one shoot sit (tree-local metres): the shoot leaves its anchor along out x 0.95 + up x 0.3 (candles go
 * up), and every node bends a few degrees about one horizontal axis so long shoots arc outward instead of standing like
 * sticks (sample bonsai_app.js). Nodes are [nodeLen] apart; [count] covers the longest possible shoot.
 */
object ShootPath {
    data class Node(val base: V3, val q: Q4)

    fun nodeLen(s: Species): Float = maxOf(s.internodeCm, s.yearlyMaxCm * 1.15f * 1.3f / MAX_NODES) / 100f
    const val MAX_NODES = 10

    fun nodes(s: Species, a: Anchor, i: Int): List<Node> {
        val up = if (s.shape == ShootShape.CANDLE) 1.2f else 0.3f
        val j1 = Growth.jitter(i * 7 + 1) - 0.5f; val j2 = Growth.jitter(i * 7 + 2) - 0.5f
        val dir = (a.out * 0.95f + V3(0f, up, 0f) + V3(j1, 0f, j2) * 0.3f).norm()
        var q = Q4.fromTo(V3.UP, dir)
        val axis = V3(Growth.jitter(i * 7 + 3) - 0.5f, 0f, Growth.jitter(i * 7 + 4) - 0.5f).let { if (it.length < 1e-3f) V3(1f, 0f, 0f) else it.norm() }
        val bend = ((if (s.shape == ShootShape.CANDLE) 1.5f else 4f + 5f * Growth.jitter(i * 7 + 5)) * PI / 180).toFloat()
        val step = Q4.axis(axis, bend)
        val len = nodeLen(s)
        var p = a.p
        return List(MAX_NODES) {
            // design rules.envelope: a node whose tip would leave the 0.30 m cylinder turns up (pot-bound growth); one that
            // would pass the top turns sideways; one that would sink under ENV_LOW turns up. The node is emitted after the fix.
            var tip = p + q.rotate(V3(0f, len, 0f))
            val r = kotlin.math.hypot(tip.x, tip.z)
            if (r > ENV_R) { q = Q4.fromTo(V3.UP, V3(-tip.x * 0.1f / r, 1f, -tip.z * 0.1f / r)); tip = p + q.rotate(V3(0f, len, 0f)) }
            if (tip.y < ENV_LOW) { q = Q4.fromTo(V3.UP, V3(tip.x * 0.4f / maxOf(r, 1e-3f), 1f, tip.z * 0.4f / maxOf(r, 1e-3f))); tip = p + q.rotate(V3(0f, len, 0f)) }
            if (tip.y > ENV_TOP) { val rr = maxOf(kotlin.math.hypot(p.x, p.z), 1e-3f); q = Q4.fromTo(V3.UP, V3(-p.z / rr, 0f, p.x / rr)); tip = p + q.rotate(V3(0f, len, 0f)) }
            val n = Node(p, q)
            p = tip; q = (q * step).norm()
            n
        }
    }

    /** Tree-local envelope (design spatialLayout.envelope: r 0.30, top 0.66 in the volume = 0.63 above the slab). */
    const val ENV_R = 0.29f
    const val ENV_TOP = 0.61f
    /** Lowest node above the slab (the pot tags sit below 0.09 m). */
    const val ENV_LOW = 0.12f

    /** How many nodes a shoot of [cm] shows (a partly grown node shows once it is half way). */
    fun visible(s: Species, cm: Float): Int = ((cm / 100f) / nodeLen(s) + 0.5f).toInt().coerceIn(0, MAX_NODES)
}

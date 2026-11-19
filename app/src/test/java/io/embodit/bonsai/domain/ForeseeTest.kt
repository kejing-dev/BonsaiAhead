package io.embodit.bonsai.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot

private val T0 = LocalDate.of(2026, 11, 18)

/** meta.json written by tools/prepare_trees.py (the same files the app ships). */
internal fun metaOf(s: Species): TreeMeta {
    val f = listOf(File("src/main/assets/trees/${s.file}/meta.json"), File("app/src/main/assets/trees/${s.file}/meta.json")).first { it.exists() }
    return MetaJson.treeMeta(f.readText())
}

private fun growth(s: Species) = metaOf(s).let { Growth(s, T0, it.anchors, it.maxY) }

/** design rules.unitTests: GrowthTest. */
class GrowthTest {
    @Test fun sameInputSameOutput() {
        for (s in Species.ALL) {
            val a = growth(s); val b = growth(s)
            for (d in intArrayOf(0, 30, 100, 182, 365)) for (i in a.vigour.indices) {
                assertEquals(a.leftAlone(i, d), b.leftAlone(i, d), 1e-6f)
                assertEquals(a.trimmed(i, d), b.trimmed(i, d))
            }
        }
    }

    @Test fun leftAloneNeverShrinks() {
        for (s in Species.ALL) { val g = growth(s); for (i in g.vigour.indices) { var last = 0f; for (d in 0..365) { val l = g.leftAlone(i, d); assertTrue("${s.id} $i $d", l >= last - 1e-6f); last = l } } }
    }

    @Test fun pineRestsFromJulyToMarch() {
        val g = growth(Species.PINE)
        // Nov 18 -> Mar 1 (day 103): no growth at all; Jun 30 -> next Nov 18: no growth
        for (i in g.vigour.indices) {
            assertEquals(0f, g.leftAlone(i, 103), 1e-6f)
            assertEquals(g.leftAlone(i, 224), g.leftAlone(i, 365), 1e-6f)
            assertTrue(g.leftAlone(i, 194) > 0f) // May 31
        }
    }

    @Test fun elmBareDecemberToFebruaryBudsInMarch() {
        val g = growth(Species.ELM)
        assertEquals(LeafState.YELLOW, g.leafState(0))     // Nov 18
        assertEquals(LeafState.BARE, g.leafState(30))      // Dec 18
        assertNull(g.variant(30))
        assertEquals(LeafState.BARE, g.leafState(80))      // Feb 6
        assertEquals(LeafState.GREEN, g.leafState(110))    // Mar 8
        assertEquals(LeafVariant.SPRING, g.variant(110))
        assertEquals(LeafVariant.SUMMER, g.variant(240))   // Jul
    }

    @Test fun evergreensKeepLeaves() {
        for (s in listOf(Species.PINE, Species.JUNIPER, Species.FICUS)) { val g = growth(s); for (d in 0..365) assertNotNull("${s.id} $d", g.variant(d)) }
    }

    @Test fun ficusIndoorGrowsEveryMonth() { assertTrue(Species.FICUS.cmPerMonth.all { it > 0f }) }
}

/** design rules.unitTests: PruneTest. */
class PruneTest {
    @Test fun trimmedNeverLongerThanLeftAlone() {
        for (s in Species.ALL) { val g = growth(s); for (d in 0..365 step 3) for (i in g.vigour.indices) assertTrue("${s.id} $i $d", g.trimmed(i, d).first <= g.leftAlone(i, d) + 1e-3f) }
    }

    @Test fun afterAYearTrimmedIsAtMostHalf() {
        for (s in Species.ALL) {
            val g = growth(s)
            val left = g.vigour.indices.maxOf { g.leftAlone(it, 365) }
            val right = g.vigour.indices.maxOf { g.trimmed(it, 365).first }
            assertTrue("${s.id} left=$left right=$right", right <= 0.5f * left)
        }
    }

    @Test fun trimmedNeverPastTheDashedLineByMoreThan3cm() {
        // two weeks after a trim no shoot is past the dashed line (1.5 cm out) by more than 3.5 cm (length > 5 cm)
        for (s in Species.ALL) { val g = growth(s); for (d in 0..365) for (i in g.vigour.indices) {
            val (l, last) = g.trimmed(i, d)
            val prunedRecently = last >= 0 && d - last < 14
            if (prunedRecently) assertTrue("${s.id} $i $d $l", l <= 5f)
        } }
    }

    @Test fun mayCutShowsOnTheRightPot() {
        val g = growth(Species.ELM)
        // May 15 2027 = day 178; day 182 shows "5 月剪过"
        assertEquals(5, g.recentCutMonth(182))
        assertNull(g.recentCutMonth(30))
    }

    @Test fun pineCandlesPinchedInJune() {
        val g = growth(Species.PINE)
        for (i in g.vigour.indices) assertTrue(g.trimmed(i, 220).first < g.leftAlone(i, 220)) // Jun 15 2027 = day 209
    }
}

/** design rules.unitTests: HighlightTest. */
class HighlightTest {
    @Test fun noneToday() { for (s in Species.ALL) assertEquals(s.id, 0, growth(s).toTrim(0)) }

    @Test fun manyAfterAYear() {
        for (s in Species.ALL) { val n = growth(s).toTrim(365); assertTrue("${s.id} $n", n >= if (s == Species.PINE) 3 else 5) }
    }

    @Test fun rightPotNeverYellow() {
        for (s in Species.ALL) { val g = growth(s); for (d in 0..365 step 5) for (i in g.vigour.indices) assertFalse(g.shoot(Side.TRIMMED, i, d).highlight) }
    }
}

/** design rules.unitTests: EnvelopeTest (D16) + the pot tags below the shoots (D35). */
class EnvelopeTest {
    @Test fun everyNodeInsideTheEnvelopeAndTheVolume() {
        for (s in Species.ALL) {
            val m = metaOf(s)
            m.anchors.forEachIndexed { i, a ->
                for (n in ShootPath.nodes(s, a, i)) {
                    for (yawDeg in intArrayOf(0, 90, 180, 270)) {
                        val q = Q4.axis(V3.UP, Layout.deg(yawDeg.toFloat()))
                        for (side in 0..1) {
                            val tip = n.base + n.q.rotate(V3(0f, ShootPath.nodeLen(s), 0f))
                            for (p0 in listOf(n.base, tip)) {
                                val p = q.rotate(p0)
                                val r = hypot(p.x, p.z)
                                assertTrue("${s.id} $i r=$r", r <= ShootPath.ENV_R + 0.005f + 0.025f)
                                val w = V3(Layout.POT_X[side] + p.x, Layout.POT_Y + p.y, Layout.POT_Z + p.z)
                                val pad = 0.025f // the leaf cluster around the node
                                assertTrue("${s.id} $i $w", w.x - pad >= Layout.VOLUME_LO.x && w.x + pad <= Layout.VOLUME_HI.x)
                                assertTrue("${s.id} $i $w", w.y >= ShootPath.ENV_LOW - 0.001f && w.y + pad <= Layout.VOLUME_HI.y)
                                assertTrue("${s.id} $i $w", w.z - pad >= Layout.VOLUME_LO.z && w.z + pad <= Layout.VOLUME_HI.z)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun potEnvelopesDoNotTouch() {
        val gap = (Layout.POT_X[1] - Layout.POT_X[0]) - 2 * (ShootPath.ENV_R + 0.025f)
        assertTrue("gap=$gap", gap >= 0f)
    }

    @Test fun tagsBelowEveryShoot() {
        val tagTop = Layout.TAG_Y + Layout.TAG_H / 2
        assertTrue(tagTop < ShootPath.ENV_LOW - 0.02f)
    }
}

/** D54 + design tapTargets + D35. */
class GeometryTest {
    @Test fun subjectFillsTheVolume() {
        val w = Layout.SLAB_W / Layout.VOL_W; val d = Layout.SLAB_D / Layout.VOL_D; val h = Layout.ENV_TOP_Y / Layout.VOL_H
        assertTrue("w=$w", w >= 0.7f); assertTrue("h=$h d=$d", minOf(h, d) >= 0.5f)
        assertTrue(Layout.angleDeg(Layout.SLAB_W) >= 25f)
    }

    @Test fun beadAndTicksAreBigEnough() {
        assertTrue(Layout.angleDeg(Layout.BEAD_HIT_R * 2) >= 2f)
        assertTrue(Layout.angleDeg(Layout.TICK_HIT.x) >= 2f)
        assertTrue(Layout.angleDeg(0.037f * 2) >= 2f) // yellow shoot collider
    }

    @Test fun timeBarMapsBothWays() {
        for (d in intArrayOf(0, 30, 182, 365, 100, 250)) assertTrue(abs(Growth.xToDay(Growth.dayToX(d)) - d) <= 1)
        assertEquals(Growth.TICK_X[1], Growth.dayToX(30), 1e-6f)
        assertEquals(30, Growth.snap(34)); assertEquals(100, Growth.snap(100))
    }

    @Test fun tagCornersOutsideThePots() {
        // the tag (tilted 30°) sits in front of the pot rim (pot radius <= 0.20 m, z <= POT_Z + 0.16)
        val tz = Layout.TAG_Z - Layout.TAG_H / 2 * kotlin.math.sin(Layout.deg(Layout.TAG_TILT_DEG))
        assertTrue("tz=$tz", tz > Layout.POT_Z + 0.16f)
    }
}

/** D59 domain side + D57: scrubbing / picking twenty times leaves a consistent state. */
class StressTest {
    private class Mem : ForeseeStore { var s: Saved? = null; override fun load() = s; override fun save(s: Saved) { this.s = s } }

    private fun make(store: Mem = Mem()): Pair<Foresee, MutableList<Sound>> {
        val sounds = ArrayList<Sound>(); var settings = Settings()
        val metas = Species.ALL.associateWith { metaOf(it) }
        val f = Foresee(CoroutineScope(Dispatchers.Unconfined), { 0L }, store, { settings }, { settings = it }, { sounds += it }, {}, { T0 }, { metas[it] })
        f.start(); return f to sounds
    }

    @Test fun twentyScrubsAndPicks() {
        val (f, _) = make()
        repeat(20) { k ->
            f.scrub(365, "test"); f.release("test"); f.scrub(0, "test"); f.release("test")
            val sp = Species.ALL[k % 5]; f.pick(sp, "test")
            assertEquals(sp, f.state.value.species)  // D57: picked = shown at once
            assertNotNull(f.growth())
        }
        assertEquals(0, f.state.value.day)
    }

    @Test fun releaseSnaps() {
        val (f, sounds) = make()
        f.scrub(27, "t"); f.release("t")
        assertEquals(30, f.state.value.day); assertTrue(Sound.TICK in sounds)
    }

    @Test fun guideAdvancesThenFinishesOnATip() {
        val (f, _) = make()
        assertEquals(1, f.state.value.guideStep)
        f.scrub(365, "t"); f.release("t")
        assertEquals(2, f.state.value.guideStep)
        val g = f.growth()!!
        val yellow = g.vigour.indices.first { g.shoot(Side.LEFT_ALONE, it, 365).highlight }
        f.tapShoot(yellow, "t")
        assertNotNull(f.state.value.tip); assertEquals(0, f.state.value.guideStep)
    }

    @Test fun greenShootTapIgnored() {
        val (f, _) = make()
        f.tapShoot(0, "t") // day 0: nothing is yellow
        assertNull(f.state.value.tip)
    }

    @Test fun restoreKeepsT0DayAndSpecies() {
        val store = Mem()
        val (f, _) = make(store)
        f.pick(Species.FICUS, "t"); f.setDay(182, "t"); f.setT0(LocalDate.of(2026, 1, 1))
        val (g, _) = make(store)
        assertEquals(Species.FICUS, g.state.value.species); assertEquals(182, g.state.value.day); assertEquals(LocalDate.of(2026, 1, 1), g.state.value.t0)
    }

    @Test fun turnSnapsBackToFront() {
        val (f, _) = make()
        f.turnTo(3f); f.turnRelease(); assertEquals(0f, f.state.value.yawDeg, 1e-4f)
        f.turnTo(-358f); f.turnRelease(); assertEquals(-360f, f.state.value.yawDeg, 1e-4f)
        f.turnTo(40f); f.turnRelease(); assertEquals(40f, f.state.value.yawDeg, 1e-4f)
    }
}

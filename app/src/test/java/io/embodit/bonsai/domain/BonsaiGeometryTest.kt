package io.embodit.bonsai.domain

import io.embodit.bonsai.ui.scene.PotTargets
import io.embodit.bonsai.ui.scene.Tween
import io.embodit.bonsai.ui.scene.input.DragTotal
import io.embodit.bonsai.ui.scene.input.Offset3M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** design d35Check.unitTest + subjectFill + tapTargets, the hit replay (build.md 命中回放), D55 wall clock, copy keys. */
class BonsaiGeometryTest {
    @Test fun signCornersStayOutOfEveryPartAndSightlinesAreClear() {
        val corners = Layout.signCorners()
        val boxes = Layout.partBoxes()
        for (c in corners) for ((name, b) in boxes) assertTrue("corner $c inside $name", !b.contains(c))
        // eye -> corner lines through the volume do not cross the tree / props (the tray's front edge is behind the sign)
        for (eye in Layout.EYES) for (c in corners) for ((name, b) in boxes) {
            if (name == "tray") continue
            assertTrue("eye $eye -> $c crosses $name", !b.hits(eye, c))
        }
        // reverse: the line over the sign's top edge passes under the low cascade pads at the canopy front (z 0.35)
        val top = corners[2]
        val eye = Layout.EYE_FRONT
        val k = (0.35f - eye.z) / (top.z - eye.z)
        val y = eye.y + (top.y - eye.y) * k
        assertTrue("sightline at z 0.35 y=$y", y < (Layout.SOIL_Y + Tree.ENV_BOTTOM.toFloat()) * Layout.K)
    }

    @Test fun treeNeverMeetsTheProps() {
        for (f in Form.entries) {
            val t = Tree.create(f, 11)
            repeat(6) { t.autoprune(); t.water() }
            val boxes = Layout.partBoxes().filterKeys { it == "stone" || it == "can" }
            for (yaw in 0 until 360 step 45) for (n in t.view()) {
                val p = Layout.treeToVolume(n.pos, yaw.toFloat())
                for ((name, b) in boxes) assertTrue("${f.id} yaw $yaw node ${n.id} in $name", !b.contains(p))
            }
            assertTrue(Envelope.allInside(t))
        }
    }

    @Test fun subjectFillsTheVolume() {
        val b = Layout.subjectBox()
        val w = b.hi.x - b.lo.x; val h = b.hi.y - b.lo.y; val d = b.hi.z - b.lo.z
        assertTrue("width ${w / Layout.VOL_W}", w / Layout.VOL_W >= 0.70f)
        assertTrue("height ${h / Layout.VOL_H}", h / Layout.VOL_H >= 0.50f)
        assertTrue("depth ${d / Layout.VOL_D}", d / Layout.VOL_D >= 0.50f)
        assertTrue("angle", Layout.visualAngle(w, Layout.EYE_FRONT) >= 25f)
    }

    @Test fun tapTargetsAreTwoDegrees() {
        val p = V3(0f, (Layout.SOIL_Y + 0.2f) * Layout.K, 0f)
        assertTrue("pad", Layout.angleOf(Layout.PAD_HIT_MIN * Layout.K, p, Layout.EYE_FRONT) >= 2.0f)
        assertTrue("ring", Layout.angleOf(Layout.RING_HIT * Layout.K, p, Layout.EYE_FRONT) >= 2.0f)
        assertTrue("button", Layout.angleOf(Layout.BTN_HIT / 2, V3(Layout.BTN_X, Layout.BTN_Y, Layout.BTN_Z), Layout.EYE_FRONT) >= 2.0f)
    }

    @Test fun hitReplayResolvesEveryTarget() {
        assertEquals("pad:12", PotTargets.resolvePath(listOf("padMesh:12", "pad:12", "tree", "turntable", "content")))
        assertEquals("seg:7", PotTargets.resolvePath(listOf("segMesh:7", "seg:7", "tree")))
        assertEquals("trunk", PotTargets.resolvePath(listOf("trunkMesh", "trunk", "tree")))
        assertEquals("ring", PotTargets.resolvePath(listOf("ringMesh", "ring", "tree")))
        assertEquals("can", PotTargets.resolvePath(listOf("can", "cal:can", "can")))
        assertEquals("pot", PotTargets.resolvePath(listOf("pot", "cal:pot", "pot")))
        assertEquals("turnL", PotTargets.resolvePath(listOf("turnLFace", "turnLBody", "turnL")))
        assertEquals(null, PotTargets.resolvePath(listOf("outline0", "outline", "turntable")))
        // every real pad / segment name of a grown tree resolves to itself and to a live node
        val t = Tree.create(Form.SHAKAN, 3); t.water()
        for (n in t.view()) if (n.padR > 0) assertEquals("pad:${n.id}", PotTargets.resolvePath(listOf("padMesh:${n.id}", "pad:${n.id}")))
    }

    @Test fun dragDeltasAddUp() {
        val d = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = d.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-4f)
    }

    @Test fun growthTweenFollowsTheWallClock() {
        // D55: ten steps of 0.1 s finish the 1.0 s tween within 1 %, whatever the frame rate
        var now = 0L
        var t = 0f
        repeat(10) { now += 100; t = Tween.progress(0, now, Garden.GROW_MS) }
        assertEquals(1f, t, 0.01f)
        assertEquals(0.5f, Tween.progress(0, 500, Garden.GROW_MS), 0.01f)
    }

    @Test fun copyKeysMatchAndSignStringsAreShort() {
        val res = File("src/main/res")
        fun keys(p: String) = Regex("<string name=\"([^\"]+)\">(.*?)</string>").findAll(File(res, p).readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val en = keys("values/strings.xml"); val zh = keys("values-zh/strings.xml")
        assertEquals(en.keys, zh.keys)
        for (k in listOf("sign_preview", "sign_trunk", "sign_weak", "sign_viewing", "sign_open_panel")) assertTrue("$k ${en[k]}", en[k]!!.length <= 18)
        assertTrue(Names.EN_FRONT.all { f -> Names.EN_BACK.all { b -> "$f $b".length <= 15 } })
        for (v in zh.values + en.values) assertTrue(v, !v.contains("首先") && !v.contains("其次"))
    }
}

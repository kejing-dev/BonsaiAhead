package io.embodit.bonsai.ui.scene

import io.embodit.bonsai.ui.scene.input.parseLoggedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Board-input replay (build.md「命中目标 → 格子必须有回放单测」): real hit paths as the template logs them (hit entity first,
 * then its parents) must resolve to the holder the scene names. Every holder kind of ForeseeScene is covered.
 */
class InputReplayTest {
    private fun r(p: String) = PotTargets.resolvePath(parseLoggedPath(p))

    @Test fun beadMeshResolvesToBead() = assertEquals("bead", r("beadMesh < bead < floor < root"))
    @Test fun beadItself() = assertEquals("bead", r("bead < floor < root"))
    @Test fun tickMarkResolvesToTick() { for (k in 0..3) assertEquals("tick:$k", r("tickMark:$k < tick:$k < floor < root")) }
    @Test fun potCollider() = assertEquals("pot", r("pot < potBase:0 < floor < root"))
    @Test fun potColliderRight() = assertEquals("pot", r("pot < potBase:1 < floor < root"))
    @Test fun yellowShootHolder() = assertEquals("shoot:0:7", r("shoot:0:7 < turn:0 < potBase:0 < floor < root"))
    @Test fun rightPotShootsAreNotTargets() = assertNull(r("shoot:1:7 < turn:1 < potBase:1 < floor < root"))
    @Test fun leavesAndWoodAreNotTargets() {
        assertNull(r("leaf:0:3:2 < turn:0 < potBase:0 < floor < root"))
        assertNull(r("wood:0 < turn:0 < potBase:0 < floor < root"))
        assertNull(r("railMesh < floor < root"))
    }
    @Test fun badNumbersIgnored() { assertNull(r("tick:x < floor")); assertNull(r("shoot:0:x < turn:0")) }
}

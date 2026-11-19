package io.embodit.bonsai.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixed geometry of the pot volume (design spatialLayout, container.windows[0].scale). Design units (the v1.1 numbers)
 * live under a content root scaled [K] = 1.25 (D54); the season sign and the 3D turn buttons are placed in actual metres.
 * Volume frame: origin = volume floor centre, +X right, +Y up, +Z toward the user.
 */
object Layout {
    const val K = 1.25f
    /** Volume size, actual metres (manifest defaultsize). */
    const val VOL_W = 1.0f
    const val VOL_H = 0.9f
    const val VOL_D = 0.78f

    // ---- design units (content root)
    const val TRAY_W = 0.78f
    const val TRAY_H = 0.03f
    const val TRAY_D = 0.40f
    const val STAND_TOP = 0.13f
    const val POT_TOP = 0.20f
    /** tree-local origin = the soil centre. */
    const val SOIL_Y = 0.20f
    const val MOSS_Y = 0.195f
    val STONE_AT = V3(-0.34f, TRAY_H, -0.02f)
    val CAN_AT = V3(0.34f, TRAY_H, 0.05f)
    /** Where cut branches land: clamped inside the sand (design R1-15). */
    const val FALL_X = 0.37f
    const val FALL_Z = 0.18f
    /** Pot + stand turntable collider (design units): 0.28 x 0.17 x 0.20, bottom on the tray. */
    val TURNTABLE_BOX = V3(0.30f, 0.17f, 0.22f)
    val CAN_BOX = V3(0.11f, 0.11f, 0.08f)
    /** Minimum pad collider radius, design units (3.0 cm design = 3.75 cm actual = 2.0° at 2.14 m; design R2-03). */
    const val PAD_HIT_MIN = 0.03f
    const val RING_HIT = 0.03f
    /** Branch collider half-thickness, design (max(r + 1.0 cm, 1.5 cm), tapTargets). */
    const val SEG_HIT_MIN = 0.015f

    // ---- actual metres (volume floor frame, not under the content root)
    /** Season sign (design panels.seasonSign): 0.30 x 0.08 m, centre (0, 0.04, 0.285), leaning back 30°. */
    val SIGN_C = V3(0f, 0.04f, 0.285f)
    const val SIGN_W = 0.30f
    const val SIGN_H = 0.08f
    const val SIGN_TILT_DEG = 30f
    /** 3D turn buttons (design turnButtons): Ø 0.10, 0.015 thick, at (±0.43, sand + 0.045, 0.20), face leaning back 30°. */
    const val BTN_X = 0.43f
    const val BTN_R = 0.05f
    const val BTN_T = 0.015f
    val BTN_Y = TRAY_H * K + 0.045f
    const val BTN_Z = 0.20f
    const val BTN_TILT_DEG = 30f
    const val BTN_HIT = 0.12f

    /** Design eyes, actual metres (design spatialLayout.eye): front, left / right 30°, seated 0.25 m lower. */
    val EYE_FRONT = V3(0f, 0.45f, 2.14f)
    val EYE_LEFT = V3(-1.07f, 0.45f, 1.85f)
    val EYE_RIGHT = V3(1.07f, 0.45f, 1.85f)
    val EYE_SEATED = V3(0f, 0.20f, 2.14f)
    val EYES = listOf(EYE_FRONT, EYE_LEFT, EYE_RIGHT, EYE_SEATED)

    fun deg(d: Float) = (d * PI / 180.0).toFloat()

    /** Tree-local (design) -> volume (actual metres) for a turntable yaw (degrees). */
    fun treeToVolume(p: D3, yawDeg: Float): V3 {
        val a = deg(yawDeg).toDouble()
        val x = p.x * cos(a) + p.z * sin(a)
        val z = -p.x * sin(a) + p.z * cos(a)
        return V3((x * K).toFloat(), ((p.y + SOIL_Y) * K).toFloat(), (z * K).toFloat())
    }

    /** The four corners of the season sign (actual metres): bottom-left, bottom-right, top-left, top-right. */
    fun signCorners(): List<V3> {
        val t = deg(SIGN_TILT_DEG)
        val dy = SIGN_H / 2 * cos(t); val dz = SIGN_H / 2 * sin(t)
        val bl = V3(-SIGN_W / 2, SIGN_C.y - dy, SIGN_C.z + dz); val br = V3(SIGN_W / 2, SIGN_C.y - dy, SIGN_C.z + dz)
        val tl = V3(-SIGN_W / 2, SIGN_C.y + dy, SIGN_C.z - dz); val tr = V3(SIGN_W / 2, SIGN_C.y + dy, SIGN_C.z - dz)
        return listOf(bl, br, tl, tr)
    }

    /** An axis-aligned box (actual metres). */
    data class Box(val lo: V3, val hi: V3) {
        fun contains(p: V3) = p.x >= lo.x && p.x <= hi.x && p.y >= lo.y && p.y <= hi.y && p.z >= lo.z && p.z <= hi.z

        /** Does the segment a -> b pass through the box (slab test)? */
        fun hits(a: V3, b: V3): Boolean {
            var t0 = 0f; var t1 = 1f
            val d = b - a
            for (axis in 0..2) {
                val o = when (axis) { 0 -> a.x; 1 -> a.y; else -> a.z }
                val v = when (axis) { 0 -> d.x; 1 -> d.y; else -> d.z }
                val l = when (axis) { 0 -> lo.x; 1 -> lo.y; else -> lo.z }
                val h = when (axis) { 0 -> hi.x; 1 -> hi.y; else -> hi.z }
                if (kotlin.math.abs(v) < 1e-9f) { if (o < l || o > h) return false; continue }
                var ta = (l - o) / v; var tb = (h - o) / v
                if (ta > tb) { val s = ta; ta = tb; tb = s }
                t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
                if (t0 > t1) return false
            }
            return true
        }
    }

    private fun box(lo: V3, hi: V3) = Box(lo * K, hi * K)

    /** Every part's box (actual metres) for D35: tray, stone, can, stand / pot (all yaws), the canopy envelope cylinder as a box. */
    fun partBoxes(): Map<String, Box> = mapOf(
        "tray" to box(V3(-TRAY_W / 2, 0f, -TRAY_D / 2), V3(TRAY_W / 2, TRAY_H, TRAY_D / 2)),
        "stone" to box(V3(-0.375f, TRAY_H, -0.045f), V3(-0.305f, TRAY_H + 0.08f, 0.005f)),
        "can" to box(V3(0.295f, TRAY_H, 0.02f), V3(0.385f, TRAY_H + 0.09f, 0.08f)),
        "stand" to box(V3(-0.158f, TRAY_H, -0.158f), V3(0.158f, STAND_TOP, 0.158f)),
        "pot" to box(V3(-0.14f, STAND_TOP, -0.14f), V3(0.14f, POT_TOP, 0.14f)),
        // main canopy zone (y >= 0.20 volume design) and the low cascade zone
        "canopy" to box(V3(-0.28f, SOIL_Y, -0.28f), V3(0.28f, SOIL_Y + Tree.ENV_TOP.toFloat(), 0.28f)),
        "canopyLow" to box(V3(-0.28f, SOIL_Y + Tree.ENV_BOTTOM.toFloat(), -0.28f), V3(0.28f, SOIL_Y, 0.28f)).let { b ->
            // the low zone only holds pads 0.17 .. 0.28 off the axis (cascade); its inner part is the pot / stand
            b
        },
    )

    /** Subject (whole pot: tray .. canopy) box, actual metres (D54 subjectFill). */
    fun subjectBox(): Box = box(V3(-TRAY_W / 2, 0f, -0.28f), V3(TRAY_W / 2, SOIL_Y + Tree.ENV_TOP.toFloat(), 0.28f))

    /** Horizontal visual angle (degrees) of a width [w] seen from [eye] at the volume centre depth. */
    fun visualAngle(w: Float, eye: V3): Float = (2 * atan((w / 2) / eye.z) * 180 / PI).toFloat()

    /** Angle (degrees) a sphere of radius [r] at [p] subtends from [eye]. */
    fun angleOf(r: Float, p: V3, eye: V3): Float = (2 * atan(r / (p - eye).length) * 180 / PI).toFloat()

    @Suppress("unused") private fun unused() = sqrt(2.0)
}

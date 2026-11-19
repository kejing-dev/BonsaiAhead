package io.embodit.bonsai.domain

import kotlin.math.PI
import kotlin.math.atan

/**
 * Fixed geometry of design v3.0 spatialLayout, actual metres (no design units any more). Volume origin = floor centre,
 * +Y up, +Z towards the user.
 */
object Layout {
    const val VOL_W = 1.3f
    const val VOL_H = 0.85f
    const val VOL_D = 0.7f

    /** Stone slab (v2.1 Tripo tray-B recalibrated). */
    const val SLAB_W = 1.24f
    const val SLAB_H = 0.03f
    const val SLAB_D = 0.50f

    /** The two pots: x of each pot centre (left = left alone, right = trimmed), z, the slab top. */
    val POT_X = floatArrayOf(-0.32f, 0.32f)
    const val POT_Z = -0.02f
    const val POT_Y = SLAB_H

    /** Tree envelope per pot (ShootPath.ENV_*): radius and top above the slab. */
    const val ENV_R = 0.30f
    const val ENV_TOP_Y = 0.66f

    /** 3D time bar: rail, bead, tick hit boxes. */
    const val RAIL_Y = 0.045f
    const val RAIL_Z = 0.29f
    const val RAIL_HALF = 0.52f
    const val RAIL_R = 0.008f
    const val BEAD_R = 0.035f
    const val BEAD_HIT_R = 0.04f
    val TICK_HIT = V3(0.20f, 0.06f, 0.06f)

    /** Pot tags (AttachmentPanel 0.20 x 0.06 m, tilted back 30°). */
    val TAG_C = floatArrayOf(-0.32f, 0.32f)
    const val TAG_Y = 0.05f
    const val TAG_Z = 0.215f
    const val TAG_W = 0.20f
    const val TAG_H = 0.06f
    const val TAG_TILT_DEG = 35f

    /** Tick label strip under the rail (AttachmentPanel 1.04 x 0.05 m). */
    const val TICKS_Y = 0.02f
    const val TICKS_Z = 0.315f
    const val TICKS_W = 1.04f
    const val TICKS_H = 0.05f

    /** Tip label (AttachmentPanel 0.16 x 0.05 m) 4 cm outward of the tapped shoot tip. */
    const val TIP_W = 0.16f
    const val TIP_H = 0.05f

    /** Eyes (design d35Check): front, ±30°, seated. */
    val EYES = listOf(V3(0f, 0.6f, 2.1f), V3(1.05f, 0.6f, 1.8f), V3(-1.05f, 0.6f, 1.8f), V3(0f, 0.35f, 2.1f))
    const val EYE_DIST = 2.1f

    /** Degrees subtended by [size] at [dist]. */
    fun angleDeg(size: Float, dist: Float = EYE_DIST): Float = (2 * atan(size / 2 / dist) * 180 / PI).toFloat()
    fun deg(d: Float) = (d * PI / 180).toFloat()

    /** Volume box and the box of one pot's envelope (pot-local origin at the slab top). */
    val VOLUME_LO = V3(-VOL_W / 2, 0f, -VOL_D / 2)
    val VOLUME_HI = V3(VOL_W / 2, VOL_H, VOL_D / 2)
}

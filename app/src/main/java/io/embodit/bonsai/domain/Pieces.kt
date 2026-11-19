package io.embodit.bonsai.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural meshes of the tree and the small parts (true round sections, smooth normals: D47 / B12 "近看不能像低多边形").
 * Pure, unit-tested; the scene turns them into SDK meshes.
 */
object Pieces {
    private const val TAU = (2 * PI).toFloat()

    /** Taper ratios of the shared branch meshes (design entities3d.shoots: 1.0 / 0.85 / 0.7 / 0.55 / 0.4). */
    val TAPERS = floatArrayOf(1.0f, 0.85f, 0.7f, 0.55f, 0.4f)

    fun taperIndex(ratio: Float): Int {
        var best = 0
        for (i in TAPERS.indices) if (kotlin.math.abs(TAPERS[i] - ratio) < kotlin.math.abs(TAPERS[best] - ratio)) best = i
        return best
    }

    /**
     * A unit branch segment along +Y (0..1): radius 1 at the bottom, [taper] at the top, 12 sides, with a half-sphere cap
     * at each end (the caps hide the joints, design "两端带半球帽"). The scene scales it (r, length, r).
     */
    fun segment(taper: Float, sides: Int = 12): MeshData {
        val b = MeshBuilder()
        val prof = ArrayList<Pair<Float, Float>>() // (radius, y)
        // bottom cap (flattened hemisphere: the y scale is the segment length, so the cap stays small)
        for (i in 0..3) { val a = (PI / 2 * i / 4).toFloat(); prof += sin(a) to (-0.08f * cos(a)) }
        for (i in 0..6) { val t = i / 6f; prof += (1f + (taper - 1f) * t) to t }
        for (i in 1..4) { val a = (PI / 2 * i / 4).toFloat(); prof += (taper * cos(a)) to (1f + 0.08f * sin(a)) }
        val m = prof.size
        for (i in 0 until m) {
            val (r, y) = prof[i]
            val a = prof[maxOf(0, i - 1)]; val c = prof[minOf(m - 1, i + 1)]
            val dr = c.first - a.first; val dy = c.second - a.second
            for (k in 0..sides) {
                val ph = TAU * k / sides
                val s = sin(ph); val co = cos(ph)
                b.v(V3(r * s, y, r * co), V3(dy * s, -dr, dy * co), k.toFloat() / sides, y)
            }
        }
        val row = sides + 1
        for (i in 0 until m - 1) for (k in 0 until sides) {
            val a = i * row + k
            b.quad(a, a + 1, a + row + 1, a + row)
        }
        return Shapes.fixWinding(b.build())
    }

    /**
     * The trunk: a Catmull-Rom tube through the trunk points whose radius runs from [r0] to [r1], 16 sides (design
     * entities3d.trunk: "截面 16 边、平滑法线、半径沿长度收细"), with a flared root and a rounded top.
     */
    fun trunk(pts: List<V3>, r0: Float, r1: Float, along: Int = 40, around: Int = 16): MeshData {
        val b = MeshBuilder()
        fun cr(t: Float): V3 {
            val n = pts.size - 1
            val f = (t * n).coerceIn(0f, n - 1e-4f)
            val i = f.toInt(); val u = f - i
            val p0 = pts[maxOf(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(n, i + 2)]
            val u2 = u * u; val u3 = u2 * u
            return (p1 * 2f + (p2 - p0) * u + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * u2 + (p3 - p0 + p1 * 3f - p2 * 3f) * u3) * 0.5f
        }
        var ref = V3(0f, 0f, 1f)
        for (i in 0..along) {
            val t = i.toFloat() / along
            val c = cr(t)
            val tan = (cr(minOf(1f, t + 0.01f)) - cr(maxOf(0f, t - 0.01f))).norm()
            var nx = ref.cross(tan).norm()
            if (nx.length < 0.5f) nx = V3(1f, 0f, 0f)
            val ny = tan.cross(nx).norm()
            ref = ny
            // flare at the root (nebari), slight bulges along the trunk
            val flare = 1f + 0.55f * maxOf(0f, 1f - t * 6f) * maxOf(0f, 1f - t * 6f)
            val r = (r0 + (r1 - r0) * t) * flare * (1f + 0.05f * sin(t * 23f))
            for (k in 0..around) {
                val ph = TAU * k / around
                val n = nx * cos(ph) + ny * sin(ph)
                b.v(c + n * r, n, k.toFloat() / around, t * 4f)
            }
        }
        val row = around + 1
        for (i in 0 until along) for (k in 0 until around) {
            val a = i * row + k
            b.quad(a, a + 1, a + row + 1, a + row)
        }
        // rounded top cap
        val top = cr(1f); val tan = (cr(1f) - cr(0.98f)).norm()
        val tip = b.v(top + tan * (r1 * 0.6f), tan, 0.5f, 4f)
        val last = along * row
        for (k in 0 until around) b.tri(last + k, last + k + 1, tip)
        return Shapes.fixWinding(b.build())
    }

    /**
     * A cloud pad: a unit sphere (radius 1, the scene scales x r, y 0.55 r, z r) with low-frequency lumps so it reads as a
     * clump of foliage (design entities3d.pads: "扁椭球 + 低频噪声位移，24 × 12 段，平滑"). [variant] picks the noise.
     */
    fun pad(variant: Int, seg: Int = 24, rings: Int = 12): MeshData {
        val rnd = Random(31 + variant * 17)
        val lumps = (0 until 7).map { V3(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 1.2f - 0.3f, rnd.nextFloat() * 2 - 1).norm() to (0.10f + rnd.nextFloat() * 0.10f) }
        fun radius(n: V3): Float {
            var r = 1f
            for ((d, a) in lumps) { val c = n.dot(d); if (c > 0) r += a * c * c * c * c }
            // a flatter underside
            if (n.y < 0) r *= 1f + 0.25f * n.y
            return r / 1.12f
        }
        val b = MeshBuilder()
        val grid = Array(rings + 1) { arrayOfNulls<V3>(seg + 1) }
        for (j in 0..rings) {
            val th = PI.toFloat() * j / rings
            for (k in 0..seg) {
                val ph = TAU * k / seg
                val n = V3(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
                grid[j][k] = n * radius(n)
            }
        }
        // normals from the neighbours (smooth)
        for (j in 0..rings) for (k in 0..seg) {
            val p = grid[j][k]!!
            val pu = grid[minOf(rings, j + 1)][k]!! - grid[maxOf(0, j - 1)][k]!!
            val pv = grid[j][(k + 1) % seg]!! - grid[j][(k - 1 + seg) % seg]!!
            var n = pu.cross(pv).norm()
            if (n.dot(p) < 0) n = n * -1f
            if (j == 0 || j == rings || n.length < 0.5f) n = p.norm()
            b.v(p, n, k.toFloat() / seg, j.toFloat() / rings)
        }
        val row = seg + 1
        for (j in 0 until rings) for (k in 0 until seg) {
            val a = j * row + k
            b.quad(a, a + row, a + row + 1, a + 1)
        }
        return Shapes.fixWinding(b.build())
    }

    /** A flat disc of radius [r] facing +Y (cut caps, moss), [seg] segments. */
    fun disc(r: Float, seg: Int = 24, y: Float = 0f): MeshData {
        val b = MeshBuilder()
        val c = b.v(V3(0f, y, 0f), V3.UP, 0.5f, 0.5f)
        for (k in 0..seg) { val ph = TAU * k / seg; b.v(V3(r * sin(ph), y, r * cos(ph)), V3.UP, 0.5f + 0.5f * sin(ph), 0.5f + 0.5f * cos(ph)) }
        for (k in 0 until seg) b.tri(c, c + 1 + k, c + 2 + k)
        return Shapes.fixWinding(b.build())
    }

    /** The moss on the soil: an elliptical cushion (rx, rz) with 12 small bumps around the edge (design entities3d.pot). */
    fun moss(rx: Float, rz: Float, h: Float, seg: Int = 48): MeshData {
        val b = MeshBuilder()
        val rings = 6
        for (j in 0..rings) {
            val t = j.toFloat() / rings
            for (k in 0..seg) {
                val ph = TAU * k / seg
                val bump = 1f + 0.06f * sin(ph * 12f)
                val rr = sin(t * PI.toFloat() / 2)
                val x = rx * rr * sin(ph) * bump; val z = rz * rr * cos(ph) * bump
                val y = h * cos(t * PI.toFloat() / 2) * (0.7f + 0.3f * sin(ph * 5f + t * 3f))
                b.v(V3(x, y, z), V3(sin(ph) * rr * 0.5f, 1f, cos(ph) * rr * 0.5f), 0.5f + 0.5f * x / rx, 0.5f + 0.5f * z / rz)
            }
        }
        val row = seg + 1
        for (j in 0 until rings) for (k in 0 until seg) { val a = j * row + k; b.quad(a, a + row, a + row + 1, a + 1) }
        return Shapes.fixWinding(b.build())
    }

    /** A full torus in the XZ plane (ring radius [big], tube [small]): the yellow cut ring, the outline's equator line. */
    fun ring(big: Float, small: Float, ring: Int = 48, tube: Int = 12): MeshData =
        Shapes.torusArc(V3.ZERO, V3(1f, 0f, 0f), V3(0f, 0f, -1f), big, small, 0f, TAU, ring, tube)

    /** The 3D turn button body: a short wooden disc (lathe, radius [r], thickness [t] along +Z, rounded rim). */
    fun button(r: Float, t: Float): MeshData {
        val prof = listOf(0f to 0f, r * 0.96f to 0f, r to t * 0.25f, r to t * 0.75f, r * 0.96f to t, 0.0f to t)
        val m = Shapes.lathe(V3.ZERO, Shapes.smoothProfile(prof, 3), 48)
        // lathe turns about Y: lay it on its back so the face looks along +Z
        val q = Q4.axis(V3(1f, 0f, 0f), (PI / 2).toFloat())
        val pos = FloatArray(m.pos.size); val nor = FloatArray(m.nor.size)
        for (i in 0 until m.vertexCount) {
            val p = q.rotate(V3(m.pos[3 * i], m.pos[3 * i + 1], m.pos[3 * i + 2])); val n = q.rotate(V3(m.nor[3 * i], m.nor[3 * i + 1], m.nor[3 * i + 2]))
            pos[3 * i] = p.x; pos[3 * i + 1] = p.y; pos[3 * i + 2] = p.z; nor[3 * i] = n.x; nor[3 * i + 1] = n.y; nor[3 * i + 2] = n.z
        }
        return Shapes.fixWinding(MeshData(pos, nor, m.uv, m.idx))
    }

    /** A quad [w] x [h] in the XY plane facing +Z (button faces, drift cards). */
    fun quad(w: Float, h: Float): MeshData {
        val b = MeshBuilder()
        val a = b.v(V3(-w / 2, -h / 2, 0f), V3(0f, 0f, 1f), 0f, 1f)
        val c = b.v(V3(w / 2, -h / 2, 0f), V3(0f, 0f, 1f), 1f, 1f)
        val d = b.v(V3(w / 2, h / 2, 0f), V3(0f, 0f, 1f), 1f, 0f)
        val e = b.v(V3(-w / 2, h / 2, 0f), V3(0f, 0f, 1f), 0f, 0f)
        b.quad(a, c, d, e)
        return Shapes.fixWinding(b.build())
    }
}

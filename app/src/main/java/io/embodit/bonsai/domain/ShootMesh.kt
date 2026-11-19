package io.embodit.bonsai.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * New-growth pieces (design entities3d shoots / dashed / cutCaps), all CPU meshes in metres:
 * - a stem node: a 6-sided tapered tube from y 0 to y [len] (candles: thicker, 8 sides);
 * - a leaf cluster: an organic polygon cluster at the node tip, flat-shaded irregular leaves (needle bursts for the pine,
 *   scale bumps for the juniper, small leaves for the others). Every leaf picks one shade band of the banded leaf texture
 *   through u (light / shadow / hue noise, design "有疏密和光影"); v runs base -> tip;
 * - dashed rings round each foliage layer (short flat ribbons, no boxes);
 * - the cut cap disc.
 */
object ShootMesh {
    private const val TAU = (2 * PI).toFloat()

    fun stem(len: Float, candle: Boolean): MeshData {
        val r0 = if (candle) 0.0045f else 0.0022f; val r1 = if (candle) 0.0035f else 0.0015f
        return Shapes.lathe(V3.ZERO, listOf(r0 to 0f, r0 to 0.0001f, r1 to len, 0f to len + 0.0005f), seg = if (candle) 8 else 6)
    }

    /** Leaf size (cluster radius) per species, metres. */
    fun clusterSize(s: Species): Float = when (s.file) { "ficus" -> 0.022f; "pine" -> 0.018f; "juniper" -> 0.013f; else -> 0.019f }

    fun cluster(s: Species, variant: Int, y: Float): MeshData {
        val rnd = Random(77 + variant * 13 + s.id.length)
        val size = clusterSize(s)
        val b = MeshBuilder()
        val n = when (s.shape) { ShootShape.CANDLE -> 24; ShootShape.SPRIG -> 12; ShootShape.LEAFY -> 8 }
        for (k in 0 until n) {
            val th = rnd.nextFloat() * TAU; val ph = (rnd.nextFloat() * 0.9f + 0.1f) * (PI / 2).toFloat()
            val dir = V3(cos(th) * cos(ph), sin(ph), sin(th) * cos(ph))
            val side = dir.cross(V3(0f, 1f, 0.01f)).norm()
            val band = (rnd.nextInt(8) + 0.5f) / 8f
            val base = V3(0f, y, 0f) + dir * (size * 0.15f * rnd.nextFloat())
            when (s.shape) {
                ShootShape.SPRIG -> { // scale bumps: a small octahedron per sprig, flat faces
                    val c = V3(0f, y, 0f) + dir * (size * 0.55f); val r = size * (0.28f + rnd.nextFloat() * 0.12f)
                    val p = listOf(V3(r, 0f, 0f), V3(-r, 0f, 0f), V3(0f, r * 0.8f, 0f), V3(0f, -r * 0.6f, 0f), V3(0f, 0f, r), V3(0f, 0f, -r)).map { it + c }
                    for ((i0, i1, i2) in listOf(Triple(0, 2, 4), Triple(4, 2, 1), Triple(1, 2, 5), Triple(5, 2, 0), Triple(4, 3, 0), Triple(1, 3, 4), Triple(5, 3, 1), Triple(0, 3, 5))) flat(b, p[i0], p[i1], p[i2], band)
                }
                ShootShape.CANDLE -> { // needle: a long thin triangle
                    val len = size * (1.0f + rnd.nextFloat() * 0.4f); val w = size * 0.06f
                    val tip = base + dir * len
                    flat(b, base + side * w, tip, base - side * w, band, twoSided = true)
                }
                ShootShape.LEAFY -> { // leaf: an irregular 4-point polygon, slightly cupped
                    val len = size * (0.8f + rnd.nextFloat() * 0.5f); val w = size * (0.32f + rnd.nextFloat() * 0.15f)
                    val tip = base + dir * len; val mid = base + dir * (len * 0.45f)
                    val l = mid + side * w + V3(0f, w * 0.3f * (rnd.nextFloat() - 0.3f), 0f)
                    val r = mid - side * w + V3(0f, w * 0.3f * (rnd.nextFloat() - 0.3f), 0f)
                    flat(b, base, l, tip, band, twoSided = true); flat(b, base, tip, r, band * 0.95f, twoSided = true)
                }
            }
        }
        return Shapes.fixWinding(b.build())
    }

    private fun flat(b: MeshBuilder, p0: V3, p1: V3, p2: V3, band: Float, twoSided: Boolean = false) {
        val nrm = (p1 - p0).cross(p2 - p0).norm().let { if (it.y < 0 && twoSided) it * -1f else it }
        val a = b.v(p0, nrm, band, 0f); val c = b.v(p1, nrm, band, 0.5f); val d = b.v(p2, nrm, band, 1f)
        b.tri(a, c, d)
        if (twoSided) { val a2 = b.v(p0, nrm * -1f, band, 0f); val c2 = b.v(p1, nrm * -1f, band, 0.5f); val d2 = b.v(p2, nrm * -1f, band, 1f); b.tri(a2, d2, c2) }
    }

    /** Dashed rings (design dashed): 1.2 cm dashes, 0.8 cm gaps, 2.5 mm wide flat ribbons, one mesh per tree. */
    fun dashes(rings: List<Ring>): MeshData {
        val b = MeshBuilder()
        for (rg in rings) {
            val n = maxOf(16, (TAU * rg.r / 0.02f).toInt())
            for (i in 0 until n) {
                val a0 = i * TAU / n; val a1 = a0 + 0.012f / rg.r
                fun at(a: Float, dy: Float, dr: Float) = V3(rg.x + cos(a) * (rg.r + dr), rg.y + dy, rg.z + sin(a) * (rg.r + dr))
                // top face (faces up) and outer face (faces out): reads from above and from the side
                val up = V3.UP
                b.quad(b.v(at(a0, 0.00125f, -0.00125f), up), b.v(at(a0, 0.00125f, 0.00125f), up), b.v(at(a1, 0.00125f, 0.00125f), up), b.v(at(a1, 0.00125f, -0.00125f), up))
                val o0 = V3(cos(a0), 0f, sin(a0)); val o1 = V3(cos(a1), 0f, sin(a1))
                b.quad(b.v(at(a0, -0.00125f, 0.00125f), o0), b.v(at(a1, -0.00125f, 0.00125f), o1), b.v(at(a1, 0.00125f, 0.00125f), o1), b.v(at(a0, 0.00125f, 0.00125f), o0))
            }
        }
        return Shapes.fixWinding(b.build())
    }

    fun cap(): MeshData = Shapes.lathe(V3.ZERO, listOf(0f to -0.00075f, 0.003f to -0.00075f, 0.003f to 0.00075f, 0f to 0.00075f), seg = 12)
}

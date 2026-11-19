package io.embodit.bonsai.domain

/** A point / half-axes triple in design units (tree-local: origin = soil centre, +Y up, +Z toward the user). */
data class D3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: D3) = D3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: D3) = D3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = D3(x * k, y * k, z * k)
    val length: Double get() = Math.sqrt(x * x + y * y + z * z)
    fun norm(): D3 { val l = length; return if (l < 1e-12) this else D3(x / l, y / l, z / l) }
    fun dist(o: D3) = (this - o).length
    fun hDist(o: D3) = Math.hypot(x - o.x, z - o.z)
    fun toV3() = V3(x.toFloat(), y.toFloat(), z.toFloat())
    override fun toString() = "(%.3f,%.3f,%.3f)".format(x, y, z)

    companion object { val ZERO = D3(0.0, 0.0, 0.0) }
}

/** One target ellipsoid of a form's outline (design content.forms[].target). */
data class Lobe(val c: D3, val r: D3)

enum class Season { SUMMER, AUTUMN, WINTER, SPRING;
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * The three tree forms (design content.forms): trunk polyline (5 points = 4 segments), target outline lobes, pad
 * colours per season. Numbers are the design package's design units (actual metres = x 1.25, [Layout.K]).
 */
enum class Form(
    val id: String,
    val trunk: List<D3>,
    val baseR: Double,
    val target: List<Lobe>,
    /** Summer / autumn / winter / spring pad colour (0xRRGGBB); winter null = the maple drops its leaves. */
    val palette: IntArray,
    val winterBare: Boolean,
    val snowTop: Boolean,
) {
    CHOKKAN(
        "chokkan",
        listOf(D3(0.0, 0.0, 0.0), D3(0.012, 0.06, 0.0), D3(-0.008, 0.12, 0.005), D3(0.006, 0.17, -0.004), D3(0.0, 0.21, 0.0)),
        0.018,
        listOf(Lobe(D3(0.0, 0.14, 0.0), D3(0.24, 0.045, 0.18)), Lobe(D3(0.0, 0.24, 0.0), D3(0.18, 0.04, 0.14)), Lobe(D3(0.0, 0.33, 0.0), D3(0.10, 0.035, 0.09))),
        intArrayOf(0x3E6B35, 0x4F6B3A, 0x3E5A35, 0x7FA65A), winterBare = false, snowTop = true,
    ),
    SHAKAN(
        "shakan",
        listOf(D3(0.0, 0.0, 0.0), D3(0.03, 0.055, 0.0), D3(0.065, 0.11, 0.004), D3(0.10, 0.16, -0.004), D3(0.115, 0.21, 0.0)),
        0.016,
        listOf(Lobe(D3(0.06, 0.22, 0.0), D3(0.18, 0.05, 0.15)), Lobe(D3(-0.12, 0.13, 0.02), D3(0.11, 0.04, 0.09)), Lobe(D3(0.12, 0.31, 0.0), D3(0.09, 0.035, 0.08))),
        intArrayOf(0x4E7F3A, 0xC8452F, 0x6A5440, 0x9BC46A), winterBare = true, snowTop = false,
    ),
    KENGAI(
        "kengai",
        listOf(D3(0.0, 0.0, 0.0), D3(-0.01, 0.06, 0.0), D3(-0.07, 0.075, 0.01), D3(-0.13, 0.04, 0.02), D3(-0.17, -0.01, 0.03)),
        0.016,
        listOf(
            Lobe(D3(0.0, 0.08, 0.0), D3(0.09, 0.035, 0.08)), Lobe(D3(-0.20, -0.01, 0.03), D3(0.05, 0.025, 0.05)),
            Lobe(D3(-0.23, -0.06, 0.04), D3(0.045, 0.025, 0.045)), Lobe(D3(-0.24, -0.095, 0.04), D3(0.04, 0.022, 0.04)),
        ),
        intArrayOf(0x5A7F45, 0x6B7A45, 0x55684A, 0x8DB06A), winterBare = false, snowTop = true,
    );

    fun color(season: Season): Int = palette[season.ordinal]

    fun inTarget(p: D3, grow: Double = 1.0): Boolean = target.any { (c, r) ->
        val dx = (p.x - c.x) / (r.x * grow); val dy = (p.y - c.y) / (r.y * grow); val dz = (p.z - c.z) / (r.z * grow)
        dx * dx + dy * dy + dz * dz <= 1.0
    }

    companion object {
        fun of(id: String?): Form = entries.firstOrNull { it.id == id } ?: SHAKAN
    }
}

/** Name words for a finished tree (design content.names): front + back, [换一个] picks another pair. */
object Names {
    val ZH_FRONT = listOf("晴", "远", "听松", "半山", "云起", "望", "秋水", "清", "归", "石边", "临风", "小")
    val ZH_BACK = listOf("一角", "崖上", "雨后", "千里", "人家", "初雪", "横斜", "晚照", "此身", "长青", "一片云", "春深")
    val EN_FRONT = listOf("Quiet", "Far", "Little", "Windward", "Morning", "Old", "Cliffside", "Late", "Small", "Steady", "Leaning", "First")
    val EN_BACK = listOf("Pine", "Ridge", "Cloud", "Rain", "Snow", "Light", "Hill", "Shade", "Wind", "Step", "Bough", "Ledge")

    /** A name index pair packed in one int (front * 100 + back); the text follows the language at display time. */
    fun pick(rnd: kotlin.random.Random, avoid: Int = -1): Int {
        var v: Int
        do { v = rnd.nextInt(ZH_FRONT.size) * 100 + rnd.nextInt(ZH_BACK.size) } while (v == avoid)
        return v
    }

    fun zh(code: Int) = ZH_FRONT[(code / 100) % ZH_FRONT.size] + ZH_BACK[(code % 100) % ZH_BACK.size]
    fun en(code: Int) = EN_FRONT[(code / 100) % EN_FRONT.size] + " " + EN_BACK[(code % 100) % EN_BACK.size]
}

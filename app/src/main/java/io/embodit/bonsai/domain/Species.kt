package io.embodit.bonsai.domain

/** The leaf state of a month (design content.species[].leafDrop / season). */
enum class LeafState { GREEN, YELLOW, HALF, BARE }

/** Which of the four baked foliage textures to show (tools/prepare_trees.py writes leaf_<key>.glb). */
enum class LeafVariant(val key: String) { SPRING("sp"), SUMMER("su"), AUTUMN("au"), WINTER("wi") }

enum class ShootShape { CANDLE, SPRIG, LEAFY }

/**
 * One species (design content.species[], v3.0). All growth numbers are the design's: cm per month of an untrimmed shoot at
 * vigour 1, the months trimmed (on the 15th), what fraction stays after a trim and the yearly cap.
 */
data class Species(
    val id: String,
    /** Asset folder under assets/trees/. */
    val file: String,
    val evergreen: Boolean,
    val indoor: Boolean,
    val cmPerMonth: FloatArray,
    val shape: ShootShape,
    val internodeCm: Float,
    val pruneMonths: Set<Int>,
    val keepFraction: Float,
    val yearlyMaxCm: Float,
    /** Month (1..12) -> leaf state. */
    val leaves: Map<Int, LeafState>,
    /** Leaf tint per month as 0xRRGGBB (design season.tint; 0 = bare). */
    val tint: IntArray,
    /** Height of the calibrated tree, metres (pot bottom to crown top). */
    val height: Float,
) {
    fun leafState(month: Int): LeafState = leaves[month] ?: LeafState.GREEN

    /** Untrimmed length (cm) beyond which a shoot counts as "to trim" (design rules.highlight). */
    val trimAtCm: Float get() = if (shape == ShootShape.CANDLE) 2.5f else 5f

    /** Months -> the baked texture. A month whose leaves are gone has none (null). */
    fun variant(month: Int): LeafVariant? = when {
        leafState(month) == LeafState.BARE -> null
        month in 3..5 -> LeafVariant.SPRING
        month in 6..8 -> LeafVariant.SUMMER
        month in 9..11 -> LeafVariant.AUTUMN
        else -> if (leafState(month) == LeafState.GREEN && !hasWinterTex) LeafVariant.AUTUMN else LeafVariant.WINTER
    }

    /** Elm has no winter texture (bare); every other species has all four. */
    val hasWinterTex: Boolean get() = id != "yushu"

    override fun equals(other: Any?) = other is Species && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        private fun months(vararg pairs: Pair<IntRange, LeafState>): Map<Int, LeafState> = buildMap { pairs.forEach { (r, s) -> r.forEach { put(it, s) } } }

        val PINE = Species(
            id = "wuzhensong", file = "pine", evergreen = true, indoor = false,
            cmPerMonth = floatArrayOf(0f, 0f, 0.5f, 3f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
            shape = ShootShape.CANDLE, internodeCm = 1.0f, pruneMonths = setOf(6), keepFraction = 0.33f, yearlyMaxCm = 7.5f,
            leaves = emptyMap(),
            tint = intArrayOf(0x5f7a63, 0x5f7a63, 0x6a8a66, 0x7fa36a, 0x8fb070, 0x6f9468, 0x668c63, 0x668c63, 0x668c63, 0x6a8a63, 0x6c8660, 0x617b60),
            height = 0.40f,
        )
        val JUNIPER = Species(
            id = "zhenbai", file = "juniper", evergreen = true, indoor = false,
            cmPerMonth = floatArrayOf(0f, 0f, 0.5f, 2f, 2.5f, 2f, 1f, 1f, 1.5f, 0.5f, 0f, 0f),
            shape = ShootShape.SPRIG, internodeCm = 1.2f, pruneMonths = setOf(5, 7, 9), keepFraction = 0.4f, yearlyMaxCm = 11f,
            leaves = emptyMap(),
            tint = intArrayOf(0x6b6a4c, 0x6b6a4c, 0x6f7c4c, 0x7d9a52, 0x86a656, 0x6f9449, 0x6a8f47, 0x6a8f47, 0x6a8f47, 0x6f8a49, 0x6f7f4c, 0x6b6f4c),
            height = 0.40f,
        )
        val ELM = Species(
            id = "yushu", file = "elm", evergreen = false, indoor = false,
            cmPerMonth = floatArrayOf(0f, 0f, 1f, 4f, 4f, 3f, 2f, 2f, 2f, 1f, 0f, 0f),
            shape = ShootShape.LEAFY, internodeCm = 1.6f, pruneMonths = setOf(5, 7, 9), keepFraction = 0.25f, yearlyMaxCm = 18f,
            leaves = months(10..11 to LeafState.YELLOW, 12..12 to LeafState.BARE, 1..2 to LeafState.BARE),
            tint = intArrayOf(0, 0, 0xa6c46a, 0x86ad55, 0x6f9a48, 0x5f8a40, 0x5a843e, 0x5a843e, 0x5f863f, 0x7f8c45, 0x9a8a44, 0),
            height = 0.40f,
        )
        val QUEMEI = Species(
            id = "quemei", file = "quemei", evergreen = false, indoor = false,
            cmPerMonth = floatArrayOf(0f, 0f, 1f, 3f, 3.5f, 3f, 2.5f, 2.5f, 2f, 1f, 0f, 0f),
            shape = ShootShape.LEAFY, internodeCm = 1.3f, pruneMonths = setOf(5, 7, 9), keepFraction = 0.2f, yearlyMaxCm = 17f,
            leaves = months(11..11 to LeafState.YELLOW, 12..12 to LeafState.HALF, 1..2 to LeafState.HALF),
            tint = intArrayOf(0x7c6a3e, 0x7c6a3e, 0x9cbf5e, 0x86b555, 0x74a84a, 0x679c44, 0x629842, 0x629842, 0x669a44, 0x6d9444, 0x7f8040, 0x7c6a3e),
            height = 0.40f,
        )
        val FICUS = Species(
            id = "renshenrong", file = "ficus", evergreen = true, indoor = true,
            cmPerMonth = floatArrayOf(0.5f, 0.5f, 1f, 1.5f, 2f, 2f, 2f, 2f, 2f, 1.5f, 1f, 0.5f),
            shape = ShootShape.LEAFY, internodeCm = 2.2f, pruneMonths = setOf(4, 6, 8, 10), keepFraction = 0.35f, yearlyMaxCm = 16f,
            leaves = emptyMap(),
            tint = intArrayOf(0x3f6b3a, 0x3f6b3a, 0x46743c, 0x4f7d40, 0x4f7d40, 0x4a7a3e, 0x4a7a3e, 0x4a7a3e, 0x4a7a3e, 0x46743c, 0x43703b, 0x3f6b3a),
            height = 0.34f,
        )
        /** Order of the pick page (design content.species). */
        val ALL = listOf(PINE, JUNIPER, ELM, QUEMEI, FICUS)
        /** First launch (design defaultSpecies). */
        val DEFAULT = ELM
        fun byId(id: String?): Species = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}

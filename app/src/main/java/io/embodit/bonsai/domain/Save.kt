package io.embodit.bonsai.domain

/** An immutable copy of one node for the scene, the shelf and the thumbnails. */
data class NodeV(val id: Int, val parent: Int, val pos: D3, val kind: Kind, val isLong: Boolean, val padR: Double, val r: Double, val born: Int)

fun Tree.view(): List<NodeV> = nodes.filter { it.alive }.map { NodeV(it.id, it.parent, it.pos, it.kind, it.isLong, it.padR, it.r, it.born) }

/** One finished tree on the shelf (read-only snapshot, design persistence.shelf). */
data class ShelfItem(val id: Long, val form: Form, val name: Int, val year: Int, val score: Int, val dateMs: Long, val nodes: List<NodeV>)

/** The current pot (design persistence.current) besides the tree itself. */
data class PotMeta(
    val look: Season = Season.SUMMER,
    val guideStep: Int = 0,
    val doneShown: Boolean = false,
    val name: Int = 0,
    val shelvedId: Long = 0L,
    val startLongs: List<Int> = emptyList(),
)

data class SavedPot(val tree: Tree, val meta: PotMeta)

/**
 * Plain text codec for files/current.txt and files/shelf.txt (pure Kotlin so the JVM tests can read it back; org.json
 * is a stub in unit tests). One "key=value" header per line, then one line per node / cut site.
 */
object SaveCodec {
    private fun d(v: Double) = v.toString()

    private fun node(n: Node) = "n ${n.id} ${n.parent} ${d(n.pos.x)} ${d(n.pos.y)} ${d(n.pos.z)} ${n.kind.ordinal} ${n.born} ${n.line} ${if (n.isLong) 1 else 0} ${if (n.alive) 1 else 0} ${d(n.padR)} ${d(n.r)} ${n.sinceFork}"

    private fun parseNode(p: List<String>) = Node(
        id = p[1].toInt(), parent = p[2].toInt(), pos = D3(p[3].toDouble(), p[4].toDouble(), p[5].toDouble()), kind = Kind.entries[p[6].toInt()],
        born = p[7].toInt(), line = p[8].toInt(), isLong = p[9] == "1", alive = p[10] == "1", padR = p[11].toDouble(), r = p[12].toDouble(), sinceFork = p[13].toInt(),
    )

    fun encode(s: SavedPot): String {
        val t = s.tree; val m = s.meta
        val b = StringBuilder()
        b.append("v=1\nform=${t.form.id}\nseed=${t.seed}\nrng=${t.rngState}\nyear=${t.year}\nweakNext=${t.weakNext}\nweakNow=${t.weakNow}\n")
        b.append("cuts=${t.cuts}\nyearStartVol=${d(t.yearStartVol)}\ncutVol=${d(t.cutVolThisYear)}\n")
        b.append("look=${m.look.name}\nguide=${m.guideStep}\ndoneShown=${m.doneShown}\nname=${m.name}\nshelved=${m.shelvedId}\nlongs=${m.startLongs.joinToString(",")}\n")
        for (n in t.nodes) b.append(node(n)).append('\n')
        for (c in t.cutSites) b.append("c ${c.nodeId} ${c.year} ${if (c.budded) 1 else 0}\n")
        return b.toString()
    }

    fun decode(text: String): SavedPot? = runCatching {
        val kv = HashMap<String, String>()
        val nodes = ArrayList<Node>()
        val sites = ArrayList<CutSite>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("n ")) { nodes += parseNode(line.split(' ')); continue }
            if (line.startsWith("c ")) { val p = line.split(' '); sites += CutSite(p[1].toInt(), p[2].toInt(), p[3] == "1"); continue }
            val i = line.indexOf('=')
            if (i > 0) kv[line.substring(0, i)] = line.substring(i + 1)
        }
        require(kv["v"] == "1" && nodes.isNotEmpty())
        nodes.forEachIndexed { i, n -> require(n.id == i) }
        val tree = Tree.restore(
            Form.of(kv["form"]), kv["seed"]!!.toLong(), kv["rng"]!!.toLong(), kv["year"]!!.toInt(), kv["weakNext"] == "true", kv["weakNow"] == "true",
            kv["cuts"]!!.toInt(), kv["yearStartVol"]!!.toDouble(), kv["cutVol"]!!.toDouble(), nodes, sites,
        )
        val meta = PotMeta(
            look = runCatching { Season.valueOf(kv["look"]!!) }.getOrDefault(Season.SUMMER),
            guideStep = kv["guide"]?.toIntOrNull() ?: 0,
            doneShown = kv["doneShown"] == "true",
            name = kv["name"]?.toIntOrNull() ?: 0,
            shelvedId = kv["shelved"]?.toLongOrNull() ?: 0L,
            startLongs = kv["longs"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
        )
        SavedPot(tree, meta)
    }.getOrNull()

    fun encodeShelf(items: List<ShelfItem>): String {
        val b = StringBuilder("v=1\n")
        for (it in items) {
            b.append("item ${it.id} ${it.form.id} ${it.name} ${it.year} ${it.score} ${it.dateMs} ${it.nodes.size}\n")
            for (n in it.nodes) b.append("v ${n.id} ${n.parent} ${d(n.pos.x)} ${d(n.pos.y)} ${d(n.pos.z)} ${n.kind.ordinal} ${if (n.isLong) 1 else 0} ${d(n.padR)} ${d(n.r)} ${n.born}\n")
        }
        return b.toString()
    }

    fun decodeShelf(text: String): List<ShelfItem>? = runCatching {
        val out = ArrayList<ShelfItem>()
        var head: List<String>? = null
        var nodes = ArrayList<NodeV>()
        fun flush() { head?.let { h -> out += ShelfItem(h[1].toLong(), Form.of(h[2]), h[3].toInt(), h[4].toInt(), h[5].toInt(), h[6].toLong(), nodes) } }
        val lines = text.lines()
        require(lines.firstOrNull() == "v=1")
        for (line in lines.drop(1)) {
            if (line.startsWith("item ")) { flush(); head = line.split(' '); nodes = ArrayList() }
            else if (line.startsWith("v ")) {
                val p = line.split(' ')
                nodes += NodeV(p[1].toInt(), p[2].toInt(), D3(p[3].toDouble(), p[4].toDouble(), p[5].toDouble()), Kind.entries[p[6].toInt()], p[7] == "1", p[8].toDouble(), p[9].toDouble(), p[10].toInt())
            }
        }
        flush()
        out
    }.getOrNull()
}

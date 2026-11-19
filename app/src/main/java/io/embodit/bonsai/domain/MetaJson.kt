package io.embodit.bonsai.domain

/** Minimal JSON reader for assets/trees/<file>/meta.json (no org.json: the JVM unit tests read the same files). */
object MetaJson {
    fun parse(text: String): Any? = P(text).value()

    @Suppress("UNCHECKED_CAST")
    fun treeMeta(text: String): TreeMeta {
        val m = parse(text) as Map<String, Any?>
        fun v3(l: Any?) = (l as List<Number>).let { V3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
        val anchors = (m["anchors"] as List<Map<String, Any?>>).map { Anchor(v3(it["p"]), v3(it["d"]).norm()) }
        val rings = (m["rings"] as List<Map<String, Number>>).map { Ring(it["x"]!!.toFloat(), it["z"]!!.toFloat(), it["y"]!!.toFloat(), it["r"]!!.toFloat()) }
        return TreeMeta(v3(m["leaf"]), anchors, rings, (m["maxY"] as Number).toFloat())
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws(); if (s[i++] == '}') return m }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); ws(); if (s[i++] == ']') return l }
        }
        fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') { if (s[i] == '\\') { i++; b.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else b.append(s[i]); i++ }
            i++; return b.toString()
        }
        fun num(): Double { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; return s.substring(st, i).toDouble() }
    }
}

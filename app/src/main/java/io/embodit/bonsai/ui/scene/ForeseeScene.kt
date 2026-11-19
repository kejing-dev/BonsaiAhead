package io.embodit.bonsai.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GroundShadowComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Vector3
import io.embodit.bonsai.domain.ForeseeState
import io.embodit.bonsai.domain.Growth
import io.embodit.bonsai.domain.LeafState
import io.embodit.bonsai.domain.LeafVariant
import io.embodit.bonsai.domain.Layout
import io.embodit.bonsai.domain.ModelCalib
import io.embodit.bonsai.domain.Q4
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.ShootMesh
import io.embodit.bonsai.domain.ShootPath
import io.embodit.bonsai.domain.ShootShape
import io.embodit.bonsai.domain.Shapes
import io.embodit.bonsai.domain.Side
import io.embodit.bonsai.domain.Species
import io.embodit.bonsai.domain.TreeMeta
import io.embodit.bonsai.domain.V3
import io.embodit.bonsai.platform.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * Every entity of the volume (design v3.0 entities3d). Compose hands state over through [apply]; per-frame motion (the
 * yaw ease) runs in [onFrame] from the ECS system.
 *
 * ```
 * root (volume centre)
 * └ floor (volume floor, x the user's uniform resize)
 *   ├ slab (Tripo tray, 1.24 x 0.03 x 0.50)          ├ rail · bead · tick:0..3 (holders)
 *   ├ pot:0 (x -0.33)  └ turn (yaw) ─ wood · leaf_* · shoots (24 x 8 nodes: stem + leaves) · dashes · caps · shoot:0:i holders
 *   ├ pot:1 (x +0.33)  └ turn (yaw) ─ … (same pool, trimmed side)
 *   └ attachments: tag:0 · tag:1 · ticks · tip
 * ```
 *
 * D59: every shared mesh / material / shape / texture is made global the moment it is created ([kept]) and lives until
 * [dispose]; switching species swaps the ModelComponent of the pooled entities, nothing shared is closed in between.
 */
internal class ForeseeScene(private val loadModel: suspend (String) -> Entity, private val metaOf: (Species) -> TreeMeta?) {
    val root = Entity().apply { setName("root") }
    private val floor = Entity().apply { setName("floor") }

    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.kept(): T { runCatching { toGlobal() }; synchronized(all) { all += this }; return this }

    private class Node(val stem: ModelEntity, val leaf: ModelEntity, var yellow: Boolean? = null)
    private class Shoot(val nodes: List<Node>, val cap: ModelEntity, val holder: Entity, var hl: Boolean = false)
    private class Pot(val side: Side, val base: Entity, val turn: Entity, val shoots: List<Shoot>, var wood: Entity? = null, val leaves: HashMap<LeafVariant, Entity> = HashMap(), var dash: ModelEntity? = null)

    /** Everything one species needs, made once (cached for the session, D59). */
    private class SpKit(
        val species: Species, val meta: TreeMeta, val wood: Entity, val leaves: Map<LeafVariant, Entity>,
        val stem: MeshResource, val clusters: List<MeshResource>, val dash: MeshResource,
        val leafMat: PhysicallyBasedMaterial, val stemMat: Material,
        val paths: List<List<ShootPath.Node>>,
    )

    private val kits = HashMap<String, SpKit>()
    private val pots = ArrayList<Pot>()
    private lateinit var physics: PhysicsMaterialResource
    private lateinit var capMesh: MeshResource
    private lateinit var hiLeaf: Material
    private lateinit var hiStem: Material
    private lateinit var capMat: Material
    private lateinit var bandTex: TextureResource
    private lateinit var bead: Entity
    private lateinit var fallback: List<Entity>
    private var shootShape: ShapeResource? = null

    private var state = ForeseeState()
    private var settings = Settings()
    private var shownKey = ""
    private var kit: SpKit? = null
    private var loading: String? = null
    private var yawShown = 0f
    private var yawFrom = 0f
    private var yawTarget = 0f
    private var yawStart = 0L
    private var halfY = -1f
    private var userScale = 1f
    var ready = false; private set

    init { root.addChild(floor) }

    /** The volume's half height from the SpatialView (metres); the floor is at -half; a uniform resize scales the content. */
    fun setRegion(h: Float) {
        if (abs(h - halfY) < 1e-4f) return
        halfY = h
        userScale = (h / (Layout.VOL_H / 2)).coerceIn(0.2f, 5f)
        floor.at(V3(0f, -h, 0f)); floor.scale(userScale)
        Log.i(TAG, "SCENE region halfY=$h scale=$userScale")
    }

    val scaleNow: Float get() = userScale

    private fun holder(e: Entity, shape: ShapeResource, hover: Boolean = true) {
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = physics))
        e.components.set(InteractableComponent())
        if (hover) e.components.set(HoverEffectComponent())
    }

    /** Stage 1 (S00, < 0.5 s): shared pieces, the time bar, two pot silhouettes as a stand-in, the light. */
    suspend fun installBasics() {
        val t = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            physics = PhysicsMaterialResource().kept()
            capMesh = Kit.mesh(ShootMesh.cap(), "cap").kept()
            bandTex = TextureResource.create(Tex.bands()).kept()
            hiLeaf = Kit.pbr(0xFFD400, 0.6f, glow = 0.35f, tex = bandTex).kept()
            hiStem = Kit.pbr(0xFFD400, 0.6f, glow = 0.35f).kept()
            capMat = Kit.pbr(0xE8D3A8, 0.7f, glow = 0.2f).kept()
            shootShape = ShapeResource.createSphere(SHOOT_HIT_R).kept()
        }
        val wood = Kit.pbr(0x7A5233, 0.7f).kept()
        val rail = withContext(Dispatchers.IO) { Kit.mesh(Shapes.tube(listOf(V3(-Layout.RAIL_HALF, 0f, 0f), V3(0f, 0f, 0f), V3(Layout.RAIL_HALF, 0f, 0f)), Layout.RAIL_R, along = 8, around = 12), "rail").kept() }
        val beadMesh = withContext(Dispatchers.IO) { Kit.mesh(Shapes.sphere(V3.ZERO, Layout.BEAD_R, 32, 16), "bead").kept() }
        val tickMesh = withContext(Dispatchers.IO) { Kit.mesh(Shapes.lathe(V3.ZERO, listOf(0f to -0.002f, 0.012f to -0.002f, 0.012f to 0.002f, 0f to 0.002f), 24), "tick").kept() }
        floor.addChild(ModelEntity(rail, wood).apply { setName("railMesh"); at(V3(0f, Layout.RAIL_Y, Layout.RAIL_Z)) })
        val beadShape = ShapeResource.createSphere(Layout.BEAD_HIT_R).kept()
        // gate:allow geometry invisible tick hit boxes along the rail (design tapTargets: tap a stop)
        val tickShape = ShapeResource.createBox(Vector3(Layout.TICK_HIT.x, Layout.TICK_HIT.y, Layout.TICK_HIT.z)).kept()
        Growth.TICK_X.forEachIndexed { k, x ->
            val h = Entity().apply { setName("tick:$k"); at(V3(x, Layout.RAIL_Y - 0.01f, Layout.RAIL_Z + 0.01f)); holder(this, tickShape) }
            h.addChild(ModelEntity(tickMesh, Kit.pbr(0xE8D3A8, 0.6f, glow = 0.2f).kept()).apply { setName("tickMark:$k"); at(V3(0f, 0.01f, -0.01f), Q4.axis(V3(0f, 0f, 1f), Layout.deg(90f))) })
            floor.addChild(h)
        }
        bead = Entity().apply { setName("bead"); at(V3(Growth.TICK_X[0], Layout.RAIL_Y, Layout.RAIL_Z)); holder(this, beadShape) }
        bead.addChild(ModelEntity(beadMesh, Kit.pbr(0xA5573A, 0.45f, glow = 0.12f).kept()).apply { setName("beadMesh") })
        floor.addChild(bead)
        // pots: base (x) -> turn (yaw); the shoot pool is built when the first species arrives
        // gate:allow geometry invisible pot collider (tap a pot = both turn 90°)
        val potShape = ShapeResource.createBox(Vector3(0.34f, 0.14f, 0.30f)).offsetByTranslation(Vector3(0f, 0.07f, 0f)).kept()
        val silhouette = withContext(Dispatchers.IO) { Kit.mesh(Shapes.lathe(V3.ZERO, Shapes.smoothProfile(listOf(0f to 0f, 0.13f to 0f, 0.15f to 0.06f, 0.14f to 0.07f, 0f to 0.07f)), 48), "potStandIn").kept() }
        val standInMat = Kit.pbr(0x9A5A3C, 0.8f).kept()
        val stand = ArrayList<Entity>()
        for (side in Side.entries) {
            val base = Entity().apply { setName("potBase:${side.ordinal}"); at(V3(Layout.POT_X[side.ordinal], Layout.POT_Y, Layout.POT_Z)) }
            val hit = Entity().apply { setName("pot"); holder(this, potShape, hover = false) }
            val turn = Entity().apply { setName("turn:${side.ordinal}") }
            base.addChild(hit); base.addChild(turn); floor.addChild(base)
            stand += ModelEntity(silhouette, standInMat).apply { setName("standIn:${side.ordinal}") }.also { turn.addChild(it) }
            pots += Pot(side, base, turn, emptyList())
        }
        fallback = stand
        runCatching {
            val light = Entity().apply { setName("keyLight"); components.set(DirectionalLightComponent(rgb4(0xFFF1DC), 1.0f, true)) }
            light.at(V3(-0.4f, 1.2f, 0.6f), Q4.axis(V3(0f, 1f, 0f), Layout.deg(-30f)) * Q4.axis(V3(1f, 0f, 0f), Layout.deg(-45f)))
            floor.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable (N1): $it") }
        Log.i(TAG, "SCENE basics in ${SystemClock.elapsedRealtime() - t} ms")
    }

    /** Stage 2: the Tripo slab; the first species kit follows from [apply]. */
    suspend fun installSlab() {
        val tray = loadModel("models/tray.glb")
        val cal = ModelCalib.TRAY
        val node = Entity().apply { setName("slab") }
        val inner = Entity()
        // v2.1 calibration fitted the tray to 0.78 x 0.03 x 0.40 design units; v3 stretches it to 1.24 x 0.03 x 0.50 m
        inner.scale(cal.scale.x * Layout.SLAB_W / 0.78f, cal.scale.y * Layout.SLAB_H / 0.03f, cal.scale.z * Layout.SLAB_D / 0.40f)
        val c = tray.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }
        c.at(V3(-cal.pivot.x, -cal.pivot.y, -cal.pivot.z))
        inner.addChild(c); node.addChild(inner)
        runCatching { c.components.set(GroundShadowComponent(true, true)) }
        floor.addChild(node)
        Log.i(TAG, "SCENE slab")
    }

    /** Build (once) everything of one species: Tripo wood + 4 leaf variants, the shoot meshes, materials, node paths. */
    suspend fun kitFor(s: Species): Boolean {
        if (kits.containsKey(s.id)) return true
        val meta = metaOf(s) ?: return false
        val t = SystemClock.elapsedRealtime()
        val wood = loadModel("trees/${s.file}/wood.glb")
        val leaves = HashMap<LeafVariant, Entity>()
        for (v in LeafVariant.entries) runCatching { leaves[v] = loadModel("trees/${s.file}/leaf_${v.key}.glb") }
        val built = withContext(Dispatchers.IO) {
            val nodeLen = ShootPath.nodeLen(s)
            val stem = Kit.mesh(ShootMesh.stem(nodeLen, s.shape == ShootShape.CANDLE), "stem:${s.id}").kept()
            val clusters = (0 until 6).map { Kit.mesh(ShootMesh.cluster(s, it, nodeLen), "cluster:${s.id}:$it").kept() }
            val dash = Kit.mesh(ShootMesh.dashes(meta.rings), "dash:${s.id}").kept()
            val paths = meta.anchors.mapIndexed { i, a -> ShootPath.nodes(s, a, i) }
            Triple(stem, clusters, dash) to paths
        }
        val leafMat = Kit.pbr(0xFFFFFF, 0.85f, glow = 0.08f, tex = bandTex).kept()
        val stemMat: Material = if (s.shape == ShootShape.CANDLE) Kit.pbr(0xD6CF9A, 0.7f, glow = 0.1f).kept() else Kit.pbr(0x6B4A2E, 0.8f).kept()
        kits[s.id] = SpKit(s, meta, wood, leaves, built.first.first, built.first.second, built.first.third, leafMat, stemMat, built.second)
        Log.i(TAG, "KIT ${s.id} in ${SystemClock.elapsedRealtime() - t} ms leaves=${leaves.keys}")
        return true
    }

    /** The shoot pool of one pot (24 shoots x 8 nodes x stem + leaves), built once and reused for every species. */
    private fun buildPool(p: Pot, k: SpKit): List<Shoot> = List(MAX_SHOOTS) { i ->
        val nodes = List(ShootPath.MAX_NODES) { n ->
            val stem = ModelEntity(k.stem, k.stemMat).apply { setName("stem:${p.side.ordinal}:$i:$n"); enabled = false }
            val leaf = ModelEntity(k.clusters[(i + n) % 6], k.leafMat).apply { setName("leaf:${p.side.ordinal}:$i:$n"); enabled = false }
            p.turn.addChild(stem); p.turn.addChild(leaf)
            Node(stem, leaf)
        }
        val cap = ModelEntity(capMesh, capMat).apply { setName("cap:${p.side.ordinal}:$i"); enabled = false }.also { p.turn.addChild(it) }
        val h = Entity().apply { setName("shoot:${p.side.ordinal}:$i"); enabled = false }
        if (p.side == Side.LEFT_ALONE) shootShape?.let { holder(h, it) }
        p.turn.addChild(h)
        Shoot(nodes, cap, h)
    }

    /** Swap the pots over to [k] (D57: the same frame the kit is ready). */
    private fun show(k: SpKit) {
        for ((idx, p) in pots.withIndex()) {
            p.wood?.let { p.turn.removeChild(it) }
            p.leaves.values.forEach { p.turn.removeChild(it) }; p.leaves.clear()
            p.dash?.let { p.turn.removeChild(it) }
            val w = k.wood.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true; it.setName("wood:$idx") }
            runCatching { w.components.set(GroundShadowComponent(true, true)) }
            p.turn.addChild(w); p.wood = w
            for ((v, e) in k.leaves) {
                val c = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = false; it.setName("leaf_${v.key}:$idx") }
                p.turn.addChild(c); p.leaves[v] = c
            }
            p.dash = ModelEntity(k.dash, dashMat()).apply { setName("dash:$idx") }.also { p.turn.addChild(it) }
            val pool = if (p.shoots.isEmpty()) buildPool(p, k).also { pots[idx] = Pot(p.side, p.base, p.turn, it, p.wood, p.leaves, p.dash) } else p.shoots
            pool.forEachIndexed { i, sh ->
                val path = k.paths.getOrNull(i)
                sh.nodes.forEachIndexed { n, nd ->
                    nd.stem.components.set(ModelComponent(k.stem, k.stemMat)); nd.leaf.components.set(ModelComponent(k.clusters[(i + n) % 6], k.leafMat)); nd.yellow = false
                    if (path != null) { val pn = path[n]; nd.stem.at(pn.base, pn.q); nd.leaf.at(pn.base, pn.q * Q4.axis(V3.UP, n * 2.4f)) }
                }
            }
        }
        fallback.forEach { it.enabled = false }
        kit = k
        shownKey = ""
        Log.i(TAG, "SHOW ${k.species.id}")
    }

    private var dashMaterial: Material? = null
    private fun dashMat(): Material = dashMaterial ?: Kit.unlit(0xFFFFFF, 0.85f).kept().also { dashMaterial = it }

    /** Called from Compose with the newest state; the scene catches up (kit loading is the caller's coroutine). */
    fun apply(st: ForeseeState, s: Settings) {
        state = st; settings = s
        if (st.yawDeg != yawTarget) { yawFrom = yawShown; yawTarget = st.yawDeg; yawStart = SystemClock.uptimeMillis() }
        val k = kit
        if (k == null || k.species != st.species) { kits[st.species.id]?.let { show(it) } ?: return }
        refresh()
    }

    /** Species the scene still has to load (the caller runs [kitFor] then [apply] again). */
    fun missingKit(): Species? = state.species.takeIf { !kits.containsKey(it.id) }

    private fun refresh() {
        val k = kit ?: return
        val st = state
        val key = "${k.species.id}|${st.day}|${st.t0}|${settings.dashed}"
        bead.at(V3(Growth.dayToX(st.day), Layout.RAIL_Y, Layout.RAIL_Z))
        if (key == shownKey) return
        shownKey = key
        val g = Growth(k.species, st.t0, k.meta.anchors, k.meta.maxY)
        val month = g.month(st.day)
        val leafState = g.leafState(st.day)
        val variant = g.variant(st.day)
        val tint = k.species.tint[month - 1].takeIf { it != 0 } ?: k.species.tint[6]
        val ref = k.species.tint[6]
        val col = V3(k.meta.leaf.x * ch(tint, 16) / ch(ref, 16), k.meta.leaf.y * ch(tint, 8) / ch(ref, 8), k.meta.leaf.z * ch(tint, 0) / ch(ref, 0))
        k.leafMat.setBaseColor(com.pico.spatial.core.math.Color4(col.x.coerceIn(0f, 1f), col.y.coerceIn(0f, 1f), col.z.coerceIn(0f, 1f), 1f))
        val candle = k.species.shape == ShootShape.CANDLE
        for (p in pots) {
            p.dash?.enabled = settings.dashed
            for ((v, e) in p.leaves) e.enabled = v == variant
            p.shoots.forEachIndexed { i, sh ->
                if (i >= k.paths.size) { sh.nodes.forEach { it.stem.enabled = false; it.leaf.enabled = false }; sh.holder.enabled = false; sh.cap.enabled = false; return@forEachIndexed }
                val d = g.shoot(p.side, i, st.day)
                val vis = ShootPath.visible(k.species, d.lengthCm)
                val keepN = maxOf(1, (k.species.keepFraction * vis + 0.5f).toInt())
                val needlesOpen = !candle || month !in 3..5 || p.side == Side.TRIMMED
                sh.hl = d.highlight
                sh.nodes.forEachIndexed { n, nd ->
                    val on = n < vis
                    val yellow = d.highlight && n >= keepN
                    nd.stem.enabled = on
                    nd.leaf.enabled = on && leafState != LeafState.BARE && !(leafState == LeafState.HALF && (i + n) % 2 == 1) && needlesOpen
                    if (on && nd.yellow != yellow) {
                        nd.yellow = yellow
                        nd.stem.components.set(ModelComponent(k.stem, if (yellow) hiStem else k.stemMat))
                        nd.leaf.components.set(ModelComponent(k.clusters[(i + n) % 6], if (yellow) hiLeaf else k.leafMat))
                    }
                }
                val path = k.paths[i]
                val tip = path[(vis - 1).coerceIn(0, path.size - 1)]
                sh.holder.enabled = d.highlight && vis > 0
                if (sh.holder.enabled) sh.holder.at(tip.base + tip.q.rotate(V3(0f, ShootPath.nodeLen(k.species), 0f)))
                val recent = d.lastCut >= 0 && st.day - d.lastCut <= Growth.CUT_SHOW_DAYS
                sh.cap.enabled = recent && vis > 0
                if (recent && vis > 0) sh.cap.at(path[(vis).coerceIn(0, path.size - 1)].base, path[(vis).coerceIn(0, path.size - 1)].q)
            }
        }
        Log.i(TAG, "REFRESH ${k.species.id} day=${st.day} month=$month leaves=$leafState variant=$variant toTrim=${g.toTrim(st.day)}")
    }

    private fun ch(rgb: Int, s: Int) = ((rgb shr s) and 255).coerceAtLeast(1) / 255f

    /** Tip of a shoot of the left pot in floor coordinates (for the tip label), or null. */
    fun shootTip(i: Int): V3? {
        val k = kit ?: return null
        val path = k.paths.getOrNull(i) ?: return null
        val g = Growth(k.species, state.t0, k.meta.anchors, k.meta.maxY)
        val vis = ShootPath.visible(k.species, g.leftAlone(i, state.day)).coerceAtLeast(1)
        val tipLocal = path[vis - 1].let { it.base + it.q.rotate(V3(0f, ShootPath.nodeLen(k.species), 0f)) }
        val yaw = Q4.axis(V3.UP, Layout.deg(yawShown))
        val out = V3(tipLocal.x, 0f, tipLocal.z).let { if (it.length < 1e-3f) V3(0f, 0f, 1f) else it.norm() }
        val p = yaw.rotate(tipLocal + out * 0.04f + V3(0f, 0.03f, 0f))
        return V3(Layout.POT_X[0] + p.x, Layout.POT_Y + p.y, Layout.POT_Z + p.z)
    }

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (pots.isEmpty()) return
        val t = Tween.progress(yawStart, SystemClock.uptimeMillis(), YAW_MS)
        val y = yawFrom + (yawTarget - yawFrom) * Tween.ease(t)
        if (abs(y - yawShown) > 1e-3f || t < 1f) {
            yawShown = y
            val q = Q4.axis(V3.UP, Layout.deg(y))
            pots.forEach { it.turn.at(V3.ZERO, q) }
        }
    }

    /** Attachment panels: tags in front of each pot, the tick strip, the tip label (actual metres, D35 / design). */
    fun attach(name: String, e: Entity?) {
        e ?: return
        when (name) {
            "tag:0", "tag:1" -> e.at(V3(Layout.TAG_C[name.last() - '0'], Layout.TAG_Y, Layout.TAG_Z), Q4.axis(V3(1f, 0f, 0f), -Layout.deg(Layout.TAG_TILT_DEG)))
            "ticks" -> e.at(V3(0f, Layout.TICKS_Y, Layout.TICKS_Z), Q4.axis(V3(1f, 0f, 0f), -Layout.deg(40f)))
            "tip" -> { e.enabled = false }
        }
        floor.addChild(e)
        attachments[name] = e
    }

    private val attachments = HashMap<String, Entity>()

    fun showTip(anchor: Int?) {
        val e = attachments["tip"] ?: return
        val p = anchor?.let { shootTip(it) }
        e.enabled = p != null
        p?.let { e.at(it) }
    }

    fun evidence(): String = "species=${kit?.species?.id} kits=${kits.keys} pots=${pots.size} shoots=${pots.firstOrNull()?.shoots?.size} yaw=$yawShown scale=$userScale resources=${all.size} entities≈${pots.sumOf { it.shoots.size * (2 * ShootPath.MAX_NODES + 2) }}"

    fun markReady() { ready = true }

    fun dispose() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        const val MAX_SHOOTS = 24
        const val YAW_MS = 500L
        /** Yellow shoot collider radius (design tapTargets: 2° at 2.1 m). */
        const val SHOOT_HIT_R = 0.037f
    }
}

/** Pure animation curves on the wall clock (D55). */
object Tween {
    fun progress(startMs: Long, nowMs: Long, durMs: Long): Float = if (durMs <= 0) 1f else ((nowMs - startMs).toFloat() / durMs).coerceIn(0f, 1f)
    fun ease(t: Float) = t * t * (3 - 2 * t)
}

/** The leaf band texture: 8 vertical shade bands (light / shadow / slight hue noise), darker towards the leaf base (v = 0). */
internal object Tex {
    fun bands(): Bitmap {
        val w = 64; val h = 32
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(5)
        val p = Paint()
        for (b in 0 until 8) {
            val k = 0.72f + 0.4f * b / 7f
            val hue = (rnd.nextFloat() - 0.5f) * 0.08f
            for (y in 0 until h) {
                val g = k * (0.82f + 0.18f * y / (h - 1f))
                fun v(x: Float) = (x * 255).toInt().coerceIn(0, 255)
                p.color = android.graphics.Color.rgb(v(g * (1 + hue)), v(g), v(g * (1 - hue)))
                c.drawRect(b * 8f, y.toFloat(), b * 8f + 8f, y + 1f, p)
            }
        }
        return bmp
    }
}

package io.embodit.bonsai.ui.scene

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
import io.embodit.bonsai.domain.D3
import io.embodit.bonsai.domain.Form
import io.embodit.bonsai.domain.Garden
import io.embodit.bonsai.domain.GardenState
import io.embodit.bonsai.domain.Kind
import io.embodit.bonsai.domain.Layout
import io.embodit.bonsai.domain.ModelCal
import io.embodit.bonsai.domain.ModelCalib
import io.embodit.bonsai.domain.NodeV
import io.embodit.bonsai.domain.Pieces
import io.embodit.bonsai.domain.Q4
import io.embodit.bonsai.domain.Season
import io.embodit.bonsai.domain.Shapes
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.V3
import io.embodit.bonsai.platform.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure animation curves on the wall clock (D55: a 1.0 s growth stays 1.0 s at 10 fps). */
object Tween {
    fun progress(startMs: Long, nowMs: Long, durMs: Long): Float = if (durMs <= 0) 1f else ((nowMs - startMs).toFloat() / durMs).coerceIn(0f, 1f)
    fun ease(t: Float) = t * t * (3 - 2 * t)
    fun window(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
}

/**
 * Every entity of the pot volume. Compose hands over [GardenState] + [Settings] through [apply]; per-frame motion runs in
 * [onFrame] from the ECS system (never in Compose).
 *
 * ```
 * root (volume centre)
 * └ floor (volume floor, x the user's uniform resize)
 *   ├ content (x 1.25, design units): tray, stone, can (+ drops), fallen, turn (yaw) ─ stand, pot, moss, tree, outline
 *   ├ turnL / turnR (actual metres)
 *   └ sign (AttachmentPanel, actual metres)
 * ```
 */
internal class BonsaiScene {
    val root = Entity().apply { setName("root") }
    private val floor = Entity().apply { setName("floor") }
    private val content = Entity().apply { setName("content") }
    private val turn = Entity().apply { setName("turntable") }
    private val tree = Entity().apply { setName("tree") }
    private val outline = Entity().apply { setName("outline") }
    private val fallen = Entity().apply { setName("fallen") }

    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.kept(): T { synchronized(all) { all += this }; return this }

    private lateinit var segMesh: List<MeshResource>
    private lateinit var padMesh: List<MeshResource>
    private lateinit var ringMesh: MeshResource
    private lateinit var discMesh: MeshResource
    private lateinit var shellMesh: MeshResource
    private lateinit var bark: PhysicallyBasedMaterial
    private lateinit var barkLong: PhysicallyBasedMaterial
    private lateinit var cap: PhysicallyBasedMaterial
    private val padMats = HashMap<Form, PhysicallyBasedMaterial>()
    private lateinit var padLong: PhysicallyBasedMaterial
    private lateinit var red: Material
    private lateinit var yellow: Material
    private lateinit var shell: Material
    private lateinit var shellLine: Material
    private lateinit var drop: Material
    private lateinit var physics: PhysicsMaterialResource
    private lateinit var padShapes: List<Pair<Float, ShapeResource>>
    private lateinit var segShapes: List<Pair<Float, ShapeResource>>
    private lateinit var ringShape: ShapeResource
    private var trunkMesh: MeshResource? = null
    private var trunkForm: Form? = null
    private var trunkEnt: Entity? = null

    private val segs = HashMap<Int, Entity>()
    private val pads = HashMap<Int, Entity>()
    private val padModel = HashMap<Int, ModelEntity>()
    private val caps = ArrayList<Entity>()
    private val overlays = ArrayList<Entity>()
    private var ring: Entity? = null
    private var can: Entity? = null
    private val drops = ArrayList<Entity>()
    private val buttons = HashMap<String, Entity>()
    private var built = false
    var ready = false; private set
    private var propsBuilt = false

    private var state = GardenState()
    private var settings = Settings()
    private var shownVersion = -1
    private var shownViewKey: Long = -1
    private var pending: List<NodeV> = emptyList()
    private var pendingIdx = 0
    private var nodeMap: Map<Int, NodeV> = emptyMap()
    private var curForm = Form.SHAKAN
    private var yawShown = 0f
    private var yawFrom = 0f
    private var fallKey = 0L
    private var fallDetached: List<Entity> = emptyList()
    private var fallPivot = V3.ZERO
    private var fallLand = V3.ZERO
    private var waterKey = 0L
    private var halfY = -1f
    private var userScale = 1f
    private val pressedAt = HashMap<String, Long>()
    private val t0 = SystemClock.uptimeMillis()

    init {
        root.addChild(floor); floor.addChild(content); content.addChild(turn); turn.addChild(tree); turn.addChild(outline); content.addChild(fallen)
        tree.at(V3(0f, Layout.SOIL_Y, 0f)); outline.at(V3(0f, Layout.SOIL_Y, 0f))
        content.scale(Layout.K)
    }

    /** The volume's half height from the SpatialView (metres); the floor is at -half; a uniform resize scales the content. */
    fun setRegion(h: Float) {
        if (abs(h - halfY) < 1e-4f) return
        halfY = h
        userScale = (h / (Layout.VOL_H / 2)).coerceIn(0.2f, 5f)
        floor.at(V3(0f, -h, 0f)); floor.scale(userScale)
        Log.i(TAG, "SCENE region halfY=$h scale=$userScale")
    }

    val scaleNow: Float get() = userScale

    private fun model(mesh: MeshResource, mat: Material, name: String) = ModelEntity(mesh, mat).apply { setName(name) }

    private fun holder(e: Entity, shape: ShapeResource) {
        e.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = physics))
        e.components.set(InteractableComponent())
        e.components.set(HoverEffectComponent())
    }

    /** Stage 1 (S00, < 0.5 s): shared meshes / materials, all created concurrently off the main thread. */
    suspend fun installBasics() {
        val t = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        coroutineScope {
            val barkTex = async(io) { TextureResource.create(Tex.bark()).kept() }
            val needleTex = async(io) { TextureResource.create(Tex.foliage(3, true)).kept() }
            val leafTex = async(io) { TextureResource.create(Tex.foliage(5, false)).kept() }
            val seg = Pieces.TAPERS.map { tp -> async(io) { Kit.mesh(Pieces.segment(tp), "seg$tp").kept() } }
            val pad = (0 until 6).map { v -> async(io) { Kit.mesh(Pieces.pad(v), "pad$v").kept() } }
            val rng = async(io) { Kit.mesh(Pieces.ring(1f, 0.12f), "ring").kept() }
            val dsc = async(io) { Kit.mesh(Pieces.disc(1f, 16), "disc").kept() }
            val shl = async(io) { Kit.mesh(Shapes.sphere(V3.ZERO, 1f, 32, 16), "shell").kept() }
            val phys = async(io) { PhysicsMaterialResource().kept() }
            val ps = listOf(0.03f, 0.04f, 0.05f, 0.06f, 0.07f, 0.08f).map { r -> async(io) { r to ShapeResource.createSphere(r).kept() } }
            // gate:allow geometry invisible branch colliders (boxes along each twig, never drawn; design tapTargets)
            val ss = listOf(0.015f, 0.025f, 0.035f, 0.045f).map { l -> async(io) { l to ShapeResource.createBox(Vector3(Layout.SEG_HIT_MIN * 2, l, Layout.SEG_HIT_MIN * 2)).offsetByTranslation(Vector3(0f, l / 2, 0f)).kept() } }
            val rs = async(io) { ShapeResource.createSphere(Layout.RING_HIT).kept() }
            segMesh = seg.awaitAll(); padMesh = pad.awaitAll(); ringMesh = rng.await(); discMesh = dsc.await(); shellMesh = shl.await()
            physics = phys.await(); padShapes = ps.awaitAll(); segShapes = ss.awaitAll(); ringShape = rs.await()
            bark = Kit.pbr(0xFFFFFF, 0.9f, tex = barkTex.await()).kept()
            barkLong = Kit.pbr(0xB5D27A, 0.8f, glow = 0.15f).kept()
            cap = Kit.pbr(0xE8D3A8, 0.7f, glow = 0.2f).kept()
            for (f in Form.entries) padMats[f] = Kit.pbr(f.color(Season.SUMMER), 0.92f, glow = 0.18f, tex = if (f == Form.SHAKAN) leafTex.await() else needleTex.await()).kept()
            padLong = Kit.pbr(0xB5D27A, 0.85f, glow = 0.2f, tex = leafTex.await()).kept()
            red = Kit.clear(0xE5534B, 0.5f).kept()
            yellow = Kit.pbr(0xF2C14E, 0.5f, glow = 0.5f).kept()
            shell = Kit.clear(0x9FD3D6, 0.12f).kept()
            shellLine = Kit.clear(0x9FD3D6, 0.6f).kept()
            drop = Kit.clear(0xDCEBFA, 0.7f).kept()
        }
        runCatching {
            val light = Entity().apply { setName("keyLight"); components.set(DirectionalLightComponent(rgb4(0xFFF1DC), 1.0f, true)) }
            light.at(V3(-0.4f, 1.2f, 0.6f), Q4.axis(V3(0f, 1f, 0f), Layout.deg(-30f)) * Q4.axis(V3(1f, 0f, 0f), Layout.deg(-45f)))
            floor.addChild(light)
            Log.i(TAG, "LIGHT directional added (N1)")
        }.onFailure { Log.w(TAG, "LIGHT unavailable (N1): $it") }
        ring = Entity().apply { setName("ring"); enabled = false }.also { e ->
            holder(e, ringShape)
            e.addChild(model(ringMesh, yellow, "ringMesh"))
            tree.addChild(e)
        }
        built = true
        Log.i(TAG, "SCENE basics in ${SystemClock.elapsedRealtime() - t} ms")
        apply(state, settings)
    }

    private fun calibrated(cal: ModelCal, tpl: Entity): Entity {
        val node = Entity().apply { setName("cal:${cal.id}") }
        val inner = Entity()
        inner.scale(cal.scale.x, cal.scale.y, cal.scale.z)
        val c = tpl.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }
        c.at(V3(-cal.pivot.x, -cal.pivot.y, -cal.pivot.z))
        inner.addChild(c)
        if (cal.yaw != 0f) node.at(V3.ZERO, Q4.axis(V3.UP, cal.yaw))
        node.addChild(inner)
        runCatching { c.components.set(GroundShadowComponent(true, true)) }
        return node
    }

    /** Stage 2: the Tripo props (calibrated, D25), their colliders, the 3D turn buttons and the can's drops. */
    suspend fun installProps(load: suspend (String) -> Entity) {
        val t = SystemClock.elapsedRealtime()
        val tray = load("tray"); val stand = load("stand"); val pot = load("pot"); val stone = load("stone"); val canT = load("can")
        val io = Dispatchers.IO
        val shapes = coroutineScope {
            listOf(
                // gate:allow geometry invisible colliders of the tray / turntable / can (the visible props are Tripo models)
                async(io) { ShapeResource.createBox(Vector3(Layout.TRAY_W, Layout.TRAY_H, Layout.TRAY_D)).offsetByTranslation(Vector3(0f, Layout.TRAY_H / 2, 0f)).kept() },
                // gate:allow geometry invisible turntable collider (pot + stand)
                async(io) { ShapeResource.createBox(Vector3(Layout.TURNTABLE_BOX.x, Layout.TURNTABLE_BOX.y, Layout.TURNTABLE_BOX.z)).offsetByTranslation(Vector3(0f, Layout.TRAY_H + Layout.TURNTABLE_BOX.y / 2, 0f)).kept() },
                // gate:allow geometry invisible can collider
                async(io) { ShapeResource.createBox(Vector3(Layout.CAN_BOX.x, Layout.CAN_BOX.y, Layout.CAN_BOX.z)).offsetByTranslation(Vector3(0f, Layout.CAN_BOX.y / 2, 0f)).kept() },
                // gate:allow geometry invisible collider of the 3D turn buttons
                async(io) { ShapeResource.createBox(Vector3(Layout.BTN_HIT, Layout.BTN_HIT, 0.04f)).kept() },
            ).awaitAll()
        }
        val moss = Dispatchers.IO.let { kotlinx.coroutines.withContext(it) { Kit.mesh(Pieces.moss(0.115f, 0.08f, 0.012f), "moss").kept() to TextureResource.create(Tex.moss()).kept() } }
        val btnBody = kotlinx.coroutines.withContext(io) { Kit.mesh(Pieces.button(Layout.BTN_R, Layout.BTN_T), "button").kept() }
        val btnFace = kotlinx.coroutines.withContext(io) { Kit.mesh(Pieces.quad(Layout.BTN_R * 1.7f, Layout.BTN_R * 1.7f), "buttonFace").kept() }
        val faces = kotlinx.coroutines.withContext(io) { listOf(-1, 1).associateWith { TextureResource.create(Tex.buttonFace(it)).kept() } }

        content.addChild(Entity().apply { setName("tray"); holder(this, shapes[0]); addChild(calibrated(ModelCalib.TRAY, tray)) })
        content.addChild(Entity().apply { setName("stone"); at(Layout.STONE_AT); addChild(calibrated(ModelCalib.STONE, stone)) })
        val tt = Entity().apply { setName("pot"); holder(this, shapes[1]) }
        tt.addChild(Entity().apply { at(V3(0f, Layout.TRAY_H, 0f)); addChild(calibrated(ModelCalib.STAND, stand)) })
        tt.addChild(Entity().apply { at(V3(0f, Layout.STAND_TOP, 0f)); addChild(calibrated(ModelCalib.POT, pot)) })
        tt.addChild(model(moss.first, Kit.pbr(0xFFFFFF, 0.95f, tex = moss.second).kept(), "moss").also { it.at(V3(0f, Layout.MOSS_Y, 0f)) })
        turn.addChild(tt)
        can = Entity().apply { setName("can"); at(Layout.CAN_AT); holder(this, shapes[2]); addChild(calibrated(ModelCalib.CAN, canT)) }
        content.addChild(can!!)
        repeat(8) { i -> drops += model(shellMesh, drop, "drop$i").also { it.enabled = false; it.scale(0.004f, 0.007f, 0.004f); content.addChild(it) } }
        for (d in listOf(-1, 1)) {
            val name = if (d < 0) "turnL" else "turnR"
            val b = Entity().apply { setName(name); at(V3(d * Layout.BTN_X, Layout.BTN_Y, Layout.BTN_Z), Q4.axis(V3(1f, 0f, 0f), -Layout.deg(Layout.BTN_TILT_DEG))); holder(this, shapes[3]) }
            val body = Entity().apply { setName("${name}Body") }
            body.addChild(model(btnBody, Kit.pbr(0xC9A57A, 0.6f, glow = 0.15f).kept(), "${name}Disc"))
            body.addChild(model(btnFace, Kit.pbr(0xFFFFFF, 0.6f, glow = 0.2f, tex = faces[d]).kept(), "${name}Face").also { it.at(V3(0f, 0f, Layout.BTN_T + 0.0008f)) })
            b.addChild(body)
            floor.addChild(b)
            buttons[name] = b
        }
        propsBuilt = true
        ready = true
        Log.i(TAG, "SCENE props in ${SystemClock.elapsedRealtime() - t} ms")
    }

    fun attachSign(sign: Entity?) {
        sign ?: return
        sign.at(Layout.SIGN_C, Q4.axis(V3(1f, 0f, 0f), -Layout.deg(Layout.SIGN_TILT_DEG)))
        floor.addChild(sign)
    }

    // ------------------------------------------------------------------ state

    fun apply(st: GardenState, s: Settings) {
        val prev = state
        state = st; settings = s
        if (!built) return
        val viewing = st.viewing
        val view = viewing?.nodes ?: st.view
        val key = viewing?.id ?: st.version.toLong()
        val form = viewing?.form ?: st.form
        if (key != shownViewKey || (viewing == null) != (prev.viewing == null)) {
            // a new cut: detach the falling part first (it animates from where it hangs)
            st.fall?.let { f -> if (f.startMs != fallKey && viewing == null) startFall(f) }
            shownViewKey = key
            if (form != curForm || trunkForm != form) rebuildTrunk(form)
            curForm = form
            nodeMap = view.associateBy { it.id }
            val alive = nodeMap.keys
            segs.keys.filter { it !in alive }.forEach { id -> segs.remove(id)?.removeFromParent() }
            pads.keys.filter { it !in alive || (nodeMap[it]?.padR ?: 0.0) <= 0 }.forEach { id -> pads.remove(id)?.removeFromParent(); padModel.remove(id) }
            pending = view.filter { it.kind != Kind.TRUNK }
            pendingIdx = 0
            buildCaps(viewing == null)
            buildOutline(form)
        }
        if (st.growth?.startMs != waterKey && st.growth != null) { waterKey = st.growth.startMs }
        // selection overlay
        overlays.forEach { it.removeFromParent() }; overlays.clear()
        val sel = st.selection
        ring?.enabled = false
        if (sel != null && viewing == null) {
            for (id in sel.ids) {
                val n = nodeMap[id] ?: continue
                padModel[id]?.let { pm ->
                    val o = model(pm.components[ModelComponent::class.java]!!.mesh!!, red, "sel:$id")
                    o.at(n.pos.toV3()); val r = n.padR.toFloat() * 1.06f; o.scale(r, r * 0.55f, r)
                    tree.addChild(o); overlays += o
                }
                segs[id]?.let { se ->
                    val o = model(segMesh[0], red, "selseg:$id")
                    o.transform().setPosition(se.transform().position); o.transform().setQuaternion(se.transform().quaternion)
                    val sc = se.transform().scaleVector; o.transform().setScaleVector(Vector3(sc.x * 1.5f, sc.y, sc.z * 1.5f))
                    tree.addChild(o); overlays += o
                }
            }
            nodeMap[sel.start]?.let { st0 ->
                val par = nodeMap[st0.parent]
                ring?.let { r ->
                    val from = par?.pos ?: st0.pos
                    val dir = (st0.pos.toV3() - from.toV3()).norm()
                    r.at(from.toV3() + dir * 0.004f, Q4.fromTo(V3.UP, dir))
                    val rad = max(0.006f, radiusOf(st0) * 2.2f)
                    r.children().firstOrNull()?.scale(rad, rad, rad)
                    r.enabled = true
                }
            }
        }
        colorPads()
    }

    private fun Entity.children(): List<Entity> = getChildren().toList()

    private fun radiusOf(n: NodeV): Float {
        var count = 0
        for (m in nodeMap.values) { var p = m.parent; var depth = 0; while (p >= 0 && depth < 60) { if (p == n.id) { count++; break }; p = nodeMap[p]?.parent ?: -1; depth++ } }
        return when (n.kind) {
            Kind.PRIMARY -> min(0.009f, 0.0045f + 0.0005f * sqrt(count.toFloat()))
            else -> min(0.007f, (if (n.isLong) 0.0018f else 0.0022f) + 0.0006f * sqrt(count.toFloat()))
        }
    }

    private fun rebuildTrunk(form: Form) {
        trunkEnt?.removeFromParent(); trunkEnt = null
        trunkForm = form
        val mesh = Kit.mesh(Pieces.trunk(form.trunk.map { it.toV3() }, form.baseR.toFloat(), form.baseR.toFloat() * 0.45f), "trunk:${form.id}").kept()
        trunkMesh = mesh
        val e = Entity().apply { setName("trunk") }
        e.addChild(model(mesh, bark, "trunkMesh"))
        // trunk colliders: one sphere per trunk point (tap = "keep the trunk")
        for ((i, p) in form.trunk.withIndex()) if (i > 0) e.addChild(Entity().apply { setName("trunk"); at(p.toV3()); holder(this, padShapes[0].second) })
        runCatching { e.components.set(GroundShadowComponent(true, false)) }
        tree.addChild(e)
        trunkEnt = e
    }

    private fun buildOutline(form: Form) {
        outline.getChildren().toList().forEach { it.removeFromParent() }
        for ((i, lobe) in form.target.withIndex()) {
            outline.addChild(model(shellMesh, shell, "outline$i").also { it.at(lobe.c.toV3()); it.scale(lobe.r.x.toFloat(), lobe.r.y.toFloat(), lobe.r.z.toFloat()) })
            outline.addChild(model(ringMesh, shellLine, "outlineRing$i").also { it.at(lobe.c.toV3()); it.scale(lobe.r.x.toFloat(), 0.02f, lobe.r.z.toFloat()) })
        }
    }

    private fun buildCaps(mine: Boolean) {
        caps.forEach { it.removeFromParent() }; caps.clear()
        if (!mine) return
        for ((id, n) in nodeMap) {
            if (n.kind == Kind.TRUNK) continue
            if (id !in capSites) continue
            caps += model(discMesh, cap, "cap$id").also { it.at(n.pos.toV3()); it.scale(0.004f, 1f, 0.004f); tree.addChild(it) }
            if (caps.size >= 120) break
        }
    }

    /** Cut sites (node ids) shown as small light-wood discs; set by the screen from the garden's tree. */
    var capSites: Set<Int> = emptySet()

    private fun buildPending(maxPerFrame: Int) {
        var made = 0
        while (pendingIdx < pending.size && made < maxPerFrame) {
            val n = pending[pendingIdx++]
            val par = nodeMap[n.parent] ?: continue
            if (n.id !in segs) {
                val a = par.pos.toV3(); val b = n.pos.toV3()
                val d = b - a; val len = d.length
                if (len > 1e-4f) {
                    val r = radiusOf(n)
                    val rp = if (par.kind == Kind.TRUNK) r * 1.3f else radiusOf(par)
                    val e = Entity().apply { setName("seg:${n.id}"); at(a, Q4.fromTo(V3.UP, d.norm())) }
                    val m = model(segMesh[Pieces.taperIndex(r / max(rp, r))], if (n.isLong) barkLong else bark, "segMesh:${n.id}")
                    m.scale(max(rp, r), len, max(rp, r))
                    e.addChild(m)
                    val shp = segShapes.minBy { abs(it.first - len) }.second
                    holder(e, shp)
                    tree.addChild(e); segs[n.id] = e; made++
                }
            }
            if (n.padR > 0 && n.id !in pads) {
                val h = Entity().apply { setName("pad:${n.id}"); at(n.pos.toV3()) }
                val r = n.padR.toFloat()
                val shp = padShapes.firstOrNull { it.first >= max(r, Layout.PAD_HIT_MIN) }?.second ?: padShapes.last().second
                holder(h, shp)
                val pm = model(padMesh[n.id % padMesh.size], if (n.isLong) padLong else padMats[curForm]!!, "padMesh:${n.id}")
                pm.scale(r, r * 0.55f, r)
                runCatching { pm.components.set(GroundShadowComponent(true, false)) }
                h.addChild(pm)
                tree.addChild(h); pads[n.id] = h; padModel[n.id] = pm; made++
            } else if (n.padR > 0) {
                val r = n.padR.toFloat(); padModel[n.id]?.scale(r, r * 0.55f, r)
            }
        }
    }

    private var lastLook: Season? = null
    private var lastRest: Boolean? = null

    private fun colorPads() {
        val st = state
        val look = if (st.viewing != null) Season.SUMMER else st.look
        val rest = st.resting && st.viewing == null
        if (look == lastLook && rest == lastRest) return
        lastLook = look; lastRest = rest
        for (f in Form.entries) {
            var c = f.color(look)
            if (look == Season.WINTER && f.snowTop) c = mix(c, 0xF4F6F8, 0.35f)
            if (rest) c = mix(c, 0xC9C27A, 0.4f)
            padMats[f]?.let { it.setBaseColor(rgb4(c)); it.setEmissiveColor(rgb4(c, 0.18f)) }
        }
        val bare = look == Season.WINTER && curForm.winterBare
        pads.values.forEach { it.enabled = !bare }
    }

    // ------------------------------------------------------------------ fall

    private fun startFall(f: io.embodit.bonsai.domain.Fall) {
        fallKey = f.startMs
        fallDetached.forEach { it.removeFromParent() }
        val ents = ArrayList<Entity>()
        for (n in f.nodes) { segs.remove(n.id)?.let { ents += it }; pads.remove(n.id)?.let { ents += it }; padModel.remove(n.id) }
        val site = nodeMap[f.siteId]?.pos ?: f.nodes.firstOrNull()?.pos ?: D3.ZERO
        // pivot at the cut site in content coordinates (turntable yaw applied)
        val a = Layout.deg(yawShown)
        fun toContent(p: D3): V3 { val q = Q4.axis(V3.UP, a).rotate(p.toV3()); return V3(q.x, q.y + Layout.SOIL_Y, q.z) }
        fallPivot = toContent(site)
        fallen.getChildren().toList().forEach { it.removeFromParent() }
        val holder = Entity().apply { setName("fallPiece") }
        for (e in ents) {
            val p = e.transform().position
            e.components.remove(CollisionComponent::class.java); e.components.remove(InteractableComponent::class.java)
            e.removeFromParent()
            val world = toContent(D3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()))
            e.at(world - fallPivot, Q4.axis(V3.UP, a) * Q4(e.transform().quaternion.w, e.transform().quaternion.x, e.transform().quaternion.y, e.transform().quaternion.z))
            holder.addChild(e)
        }
        fallen.addChild(holder)
        fallDetached = listOf(holder)
        fallLand = V3(fallPivot.x.coerceIn(-Layout.FALL_X, Layout.FALL_X), Layout.TRAY_H + 0.01f, (fallPivot.z + 0.12f).coerceIn(-Layout.FALL_Z, Layout.FALL_Z))
        if (abs(fallLand.x) < 0.2f) fallLand = V3(if (fallPivot.x >= 0) 0.24f else -0.24f, fallLand.y, fallLand.z)
        holder.at(fallPivot)
        Log.i(TAG, "FALL pieces=${ents.size} from=$fallPivot to=$fallLand")
    }

    // ------------------------------------------------------------------ per frame

    fun pressed(name: String) { pressedAt[name] = SystemClock.uptimeMillis() }

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        buildPending(16)
        val st = state
        val now = st.freezeMs?.let { f -> (st.growth?.startMs ?: st.fall?.startMs ?: SystemClock.uptimeMillis()) + f } ?: SystemClock.uptimeMillis()
        // turntable yaw (0.4 s ease toward the target; held drag = direct)
        val target = st.yawDeg
        if (st.yawAnimMs == 0L) yawShown = target
        else {
            val t = Tween.ease(Tween.progress(st.yawAnimMs, now, 400))
            if (t >= 1f) yawFrom = target
            yawShown = yawFrom + (target - yawFrom) * t
            if (t >= 1f) yawShown = target
        }
        var spin = 0f
        if (st.spinStartMs > 0) { val t = Tween.progress(st.spinStartMs, now, 3000); if (t < 1f) spin = 360f * Tween.ease(t) }
        turn.at(V3.ZERO, Q4.axis(V3.UP, Layout.deg(yawShown + spin)))
        outline.enabled = settings.outline && st.viewing == null
        // growth tween (1.0 s): new parts grow in, old pads grow from their old size
        val g = st.growth
        if (g != null && st.viewing == null) {
            val t = Tween.progress(g.startMs, now, Garden.GROW_MS)
            for (id in g.newIds) {
                val n = nodeMap[id] ?: continue
                segs[id]?.children()?.firstOrNull()?.let { m ->
                    val k = Tween.ease(Tween.window(t, 0f, 0.6f)).coerceAtLeast(0.01f)
                    val sc = m.transform().scaleVector
                    val par = nodeMap[n.parent]
                    val len = if (par != null) (n.pos.toV3() - par.pos.toV3()).length else sc.y
                    m.transform().setScaleVector(Vector3(sc.x, len * k, sc.z))
                }
                padModel[id]?.let { pm -> val r = n.padR.toFloat() * Tween.ease(Tween.window(t, 0.2f, 1f)).coerceAtLeast(0.01f); pm.scale(r, r * 0.55f, r) }
            }
            for ((id, before) in g.padBefore) {
                if (id in g.newIds) continue
                val n = nodeMap[id] ?: continue
                val pm = padModel[id] ?: continue
                val r = (before + (n.padR - before) * Tween.ease(t)).toFloat()
                pm.scale(r, r * 0.55f, r)
            }
            // spring green first, summer by the end (design seasons.grow)
            if (!g.weak && t < 1f) {
                val c = mix(curForm.color(Season.SPRING), curForm.color(Season.SUMMER), Tween.ease(Tween.window(t, 0.3f, 1f)))
                padMats[curForm]?.setBaseColor(rgb4(c)); lastLook = null
            } else if (lastLook == null) colorPads()
            // the can tips 35° and eight drops fall into the pot
            val tilt = if (t < 0.4f) Tween.ease(t / 0.4f) else 1f - Tween.ease(Tween.window(t, 0.6f, 1f))
            can?.at(Layout.CAN_AT, Q4.axis(V3(0f, 0f, 1f), Layout.deg(35f) * tilt))
            drops.forEachIndexed { i, d ->
                val td = Tween.window(t, 0.15f + i * 0.03f, 0.75f + i * 0.03f)
                d.enabled = td > 0f && td < 1f
                val from = Layout.CAN_AT + V3(-0.06f, 0.07f, 0f); val to = V3(0.05f * sin(i * 1.7f), Layout.POT_TOP, 0.04f * sin(i * 2.3f))
                val p = from.lerp(to, td) + V3(0f, -0.05f * td * td, 0f)
                d.at(p)
            }
        }
        // the fall: 0.6 s arc to the sand, 1.2 s rest, 0.4 s shrink
        val f = st.fall
        val piece = fallDetached.firstOrNull()
        if (f != null && piece != null && f.startMs == fallKey) {
            val el = now - f.startMs
            val t = (el / 600f).coerceIn(0f, 1f)
            val p = fallPivot.lerp(fallLand, t) + V3(0f, 0.06f * sin(t * PI.toFloat()), 0f)
            val s = if (el > 1800) (1f - (el - 1800) / 400f).coerceIn(0f, 1f) else 1f
            piece.at(p, Q4.axis(V3(0f, 0f, 1f), 1.4f * t) * Q4.axis(V3(1f, 0f, 0f), 0.6f * t))
            piece.scale(s)
            if (el > 2200) { piece.removeFromParent(); fallDetached = emptyList() }
        }
        // breeze: pads sway a few millimetres (design: 云片轻摆，设置里可关)
        if (settings.motion) {
            val tt = (now - t0) / 1000f
            for ((id, h) in pads) { val n = nodeMap[id] ?: continue; val ph = id * 0.7f; h.at(n.pos.toV3() + V3(0.0015f * sin(tt * 1.3f + ph), 0f, 0.001f * sin(tt * 0.9f + ph * 1.3f))) }
        }
        // guide breathing: step 1 the long shoots, 2 the can, 3 the buttons (1.2 s period)
        val breathe = 1f + 0.06f * sin((now - t0) / 1200f * 2 * PI.toFloat())
        can?.children()?.firstOrNull()?.scale(if (st.guideStep == 2) breathe else 1f)
        for ((name, b) in buttons) {
            val body = b.children().firstOrNull() ?: continue
            val pressedMs = pressedAt[name] ?: 0L
            val push = if (SystemClock.uptimeMillis() - pressedMs < 160) -0.004f else 0f
            body.at(V3(0f, 0f, push))
            body.scale(if (st.guideStep == 3) breathe else 1f)
        }
        if (st.guideStep == 1) for ((id, pm) in padModel) { val n = nodeMap[id] ?: continue; if (n.isLong) { val r = n.padR.toFloat() * breathe; pm.scale(r, r * 0.55f, r) } }
    }

    fun evidence(): String = "segs=${segs.size} pads=${pads.size} caps=${caps.size} overlays=${overlays.size} pending=${pending.size - pendingIdx} yaw=$yawShown scale=$userScale props=$propsBuilt"

    fun dispose() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }
}

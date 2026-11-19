package io.embodit.bonsai.ui.scene

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.bonsai.R
import io.embodit.bonsai.domain.ForeseeState
import io.embodit.bonsai.domain.Growth
import io.embodit.bonsai.domain.Side
import io.embodit.bonsai.platform.Graph
import io.embodit.bonsai.platform.TAG
import io.embodit.bonsai.ui.Texts
import io.embodit.bonsai.ui.scene.input.BoardInputConfig
import io.embodit.bonsai.ui.scene.input.BoardInputListener
import io.embodit.bonsai.ui.scene.input.BoardInputLog
import io.embodit.bonsai.ui.scene.input.InputSource
import io.embodit.bonsai.ui.scene.input.Offset3M
import io.embodit.bonsai.ui.scene.input.TargetResolver
import io.embodit.bonsai.ui.scene.input.boardInput
import io.embodit.bonsai.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class PotFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { runCatching { SceneHolder.scene?.onFrame(context.deltaTime) }.onFailure { Log.e(TAG, "FRAME", it) } }
}

internal object SceneHolder {
    @Volatile var scene: ForeseeScene? = null
}

/**
 * The volume (default container): two pots of the same tree on a stone slab, the 3D time bar and the tags. Opens the
 * control window beside it, builds the scene in stages (S00 stand-ins and the time bar first, the Tripo slab and the
 * species kit next) and routes every pinch / trigger / drag through the factory board-input template (D50: hand ray,
 * controller ray, eye-hand alike) into [io.embodit.bonsai.domain.Foresee].
 */
@Composable
fun VolumeScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.foresee.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val loadMutex = remember { Mutex() }
    var contentRef by remember { mutableStateOf<((Entity) -> Unit)?>(null) }
    val cache = remember { HashMap<String, Entity>() }
    val scene = remember {
        ForeseeScene(
            loadModel = { path ->
                cache[path] ?: withContext(NonCancellable + Dispatchers.Main) { Entity.loadSuspend("asset://$path") }.also { it.enabled = false; contentRef?.invoke(it); cache[path] = it }
            },
            metaOf = { graph.meta(it) },
        )
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var kitTick by remember { mutableStateOf(0) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }
    LaunchedEffect(scene) {
        val t0 = SystemClock.elapsedRealtime()
        while (!scene.ready && SystemClock.elapsedRealtime() - t0 < 15_000) delay(30)
        graph.sceneReady.value = true
        Log.i(TAG, "SCENE ready=${scene.ready} after ${SystemClock.elapsedRealtime() - t0} ms")
        for (s in listOf(1000L, 2000L, 2000L)) { delay(s); Log.i(TAG, "DIAG panel=${graph.window.panelState} ${graph.foresee.dump()} ${scene.evidence()}") }
    }
    // species kits load on demand (picked species first), one at a time
    LaunchedEffect(st.species, contentRef) {
        if (contentRef == null) return@LaunchedEffect
        loadMutex.withLock { runCatching { scene.kitFor(st.species) }.onFailure { Log.e(TAG, "KIT ${st.species.id} failed", it) } }
        kitTick++
        scene.markReady()
    }
    // the tip label fades after 2.5 s (design S06)
    LaunchedEffect(st.tip) {
        val tip = st.tip ?: return@LaunchedEffect
        delay((tip.untilMs - SystemClock.uptimeMillis()).coerceAtLeast(0))
        if (graph.foresee.state.value.tip == tip) graph.foresee.clearTip()
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.foresee.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(scene) {
        SceneHolder.scene = scene
        registerSystem<PotFrameSystem>()
        onDispose {
            unregisterSystem<PotFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(PotTargets, remember(graph, scene) { PotListener(graph, scene) }, BoardInputConfig(dragStartM = 0.015f, logTag = TAG)),
        attachments = {
            AttachmentPanel(TAG0) { Localized(graph) { PotTag(it, Side.LEFT_ALONE, graph) } }
            AttachmentPanel(TAG1) { Localized(graph) { PotTag(it, Side.TRIMMED, graph) } }
            AttachmentPanel(TICKS) { Localized(graph) { TickStrip(it, graph) } }
            AttachmentPanel(TIP) { Localized(graph) { TipLabel(it) } }
        },
        initial = { content, attachments ->
            contentRef = { e -> content.addEntity(e) }
            content.addEntity(scene.root)
            runCatching { scene.installBasics() }.onFailure { Log.e(TAG, "BASICS failed", it) }
            for (n in listOf(TAG0, TAG1, TICKS, TIP)) scene.attach(n, attachments.entity(n))
            val prefs = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            graph.scope.launch { runCatching { loadMutex.withLock { scene.installSlab() } }.onFailure { Log.e(TAG, "SLAB failed", it) } }
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            @Suppress("UNUSED_EXPRESSION") kitTick
            scene.apply(st, settings)
            scene.showTip(st.tip?.anchor)
        },
    )
}

private const val TAG0 = "tag:0"
private const val TAG1 = "tag:1"
private const val TICKS = "ticks"
private const val TIP = "tip"

/** Attachment panels are their own compositions: they read state + language themselves (附着面板拿不到外层 CompositionLocal). */
@Composable
private fun Localized(graph: Graph, content: @Composable (ForeseeState) -> Unit) {
    val base = LocalContext.current
    val st by graph.foresee.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) { CompositionLocalProvider(LocalContext provides base.localized(s.language)) { content(st) } }
}

private val WOOD = Color(0xF04A3524)
private val ON_WOOD = Color(0xFFF3E7CF)
private val ON_WOOD_DIM = Color(0xFFE2D2B0)

/** 250 x 75 dp ≈ 0.20 x 0.06 m wooden tag in front of each pot (design panels.potTags). */
@Composable
private fun PotTag(st: ForeseeState, side: Side, graph: Graph) {
    val ctx = LocalContext.current
    val g = graph.foresee.growth(st)
    val (l1, l2) = Texts.tag(ctx, st, side, g)
    Column(Modifier.size(250.dp, 75.dp).clip(RoundedCornerShape(12.dp)).background(WOOD).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(l1, color = ON_WOOD, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW tag$side/1 \"$l1\"") })
        if (l2.isNotEmpty()) Text(l2, color = ON_WOOD_DIM, fontSize = 17.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW tag$side/2 \"$l2\"") })
    }
}

/** 1300 x 62 dp ≈ 1.04 x 0.05 m strip under the rail: the four stop names, the current one bold (design panels.timeTicks). */
@Composable
private fun TickStrip(st: ForeseeState, graph: Graph) {
    val ctx = LocalContext.current
    Box(Modifier.size(1300.dp, 62.dp), contentAlignment = Alignment.Center) {
        if (!graph.window.panelOpen && graph.window.panelState != "never") {
            Button(onClick = { graph.window.open("ticks") }, modifier = Modifier.size(360.dp, 56.dp)) {
                // design-style: inherited-content-color Button
                Text(ctx.getString(R.string.open_panel), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Growth.STOPS.forEachIndexed { k, d ->
                val on = st.day == d
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(Texts.stop(ctx, k), color = if (on) Color(0xFF2B2418) else Color(0xFF4A4134), fontSize = if (on) 26.sp else 22.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xE6F2E6C8) else Color(0xB3EFE8DA)).padding(horizontal = 14.dp, vertical = 2.dp),
                        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW ticks/$k") })
                }
            }
        }
    }
}

/** 200 x 62 dp ≈ 0.16 x 0.05 m label beside a tapped yellow shoot (design S06). */
@Composable
private fun TipLabel(st: ForeseeState) {
    val ctx = LocalContext.current
    val tip = st.tip ?: return
    val txt = Texts.tip(ctx, st.species, tip.month)
    Box(Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF7FFFDF3)).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text("✂ $txt", color = Color(0xFF2B2418), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = TextAlign.Center,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW tip \"$txt\"") })
    }
}

/** Hit entity name -> the holder name: bead, tick:k, pot, shoot:0:i (top-level: nothing to capture before it exists). */
internal fun potTarget(name: String): String? = when {
    name == "bead" || name == "pot" -> name
    name.startsWith("tick:") -> name.takeIf { it.substring(5).toIntOrNull() != null }
    name.startsWith("shoot:0:") -> name.takeIf { it.substring(8).toIntOrNull() != null }
    else -> null
}

/**
 * The live resolver of the volume. v0.2.0 first build declared it `object PotTargets : TargetResolver<String> by
 * TargetResolver.byName(PotTargets::targetOf)`: the delegate is built while the object itself is still being built, so the
 * bound reference captured a null receiver and the first real 3D tap crashed (headset 2026-11-19, NPE in targetOf). The
 * delegate now wraps a top-level function, and the replay test calls the very function the delegate holds.
 */
internal object PotTargets : TargetResolver<String> {
    /** The exact function the live resolver uses (InputReplayTest calls it). */
    val nameToTarget: (String) -> String? = ::potTarget
    private val delegate = TargetResolver.byName(nameToTarget)
    override fun resolve(entity: Entity): String? = delegate.resolve(entity)
    fun targetOf(name: String): String? = nameToTarget(name)

    /** The logged hit path (child < parent < …) -> holder, the same walk the template does on a real hit. */
    fun resolvePath(path: List<String>): String? = io.embodit.bonsai.ui.scene.input.resolvePath(path, nameToTarget)
}

/** Holder name -> the one Foresee call (the debug broadcast uses the same). */
internal fun routeTap(graph: Graph, name: String?, src: String) {
    val f = graph.foresee
    when {
        name == null -> Unit
        name == "pot" -> f.turnStep(src)
        name == "bead" -> Unit
        name.startsWith("tick:") -> f.goStop(name.substring(5).toInt(), src)
        name.startsWith("shoot:0:") -> f.tapShoot(name.substring(8).toInt(), src)
    }
}

/** Drag on the bead scrubs the time (per-event deltas summed by the template, D53); drag on a pot turns both. */
internal class PotListener(private val graph: Graph, private val scene: ForeseeScene) : BoardInputListener<String> {
    private var mode: String? = null
    private var startX = 0f
    private var startYaw = 0f

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        routeTap(graph, target, "3D ${source.label}")
    }

    // gate:allow shared-drag 木珠拖时间、捏盆转，都是锦上添花；主路径是控制窗滑条 + 点刻度 + 点盆转 90°（门 B 已同意 Shared Space）
    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        when {
            target == "bead" -> { mode = "bead"; startX = Growth.dayToX(graph.foresee.state.value.day) }
            target == "pot" -> { mode = "pot"; startYaw = graph.foresee.state.value.yawDeg }
            else -> { routeTap(graph, target, "3D ${source.label}"); mode = null }
        }
        return true
    }

    override fun onDragMove(totalM: Offset3M) {
        when (mode) {
            "bead" -> graph.foresee.scrub(Growth.xToDay(startX + totalM.x / scene.scaleNow), "bead")
            "pot" -> graph.foresee.turnTo(startYaw + (totalM.x / scene.scaleNow * 720f).coerceIn(-720f, 720f))
        }
    }

    override fun onDragEnd() { end() }
    override fun onDragCancel() { end() }
    private fun end() {
        when (mode) { "bead" -> graph.foresee.release("bead"); "pot" -> graph.foresee.turnRelease() }
        mode = null
    }

    override fun onHover(target: String?, source: InputSource) {}
}

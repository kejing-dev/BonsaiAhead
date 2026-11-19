package io.embodit.bonsai.ui.scene

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.embodit.bonsai.domain.Garden
import io.embodit.bonsai.domain.GardenState
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
import io.embodit.bonsai.ui.theme.BonsaiColors
import io.embodit.bonsai.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class PotFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { runCatching { SceneHolder.scene?.onFrame(context.deltaTime) }.onFailure { Log.e(TAG, "FRAME", it) } }
}

internal object SceneHolder {
    @Volatile var scene: BonsaiScene? = null
}

private const val SIGN = "sign"

/**
 * The pot volume (default container). Opens the control window beside it, builds the scene in two stages (S00 shared
 * pieces and the tree first, the Tripo props next) and routes every pinch / trigger / drag on a holder to [Garden]
 * through the factory board-input template (D50: hand ray, controller ray, eye-hand alike).
 */
@Composable
fun VolumeScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.garden.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BonsaiScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

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
        for (s in listOf(1000L, 2000L, 2000L)) { delay(s); Log.i(TAG, "DIAG panel=${graph.window.panelState} ${graph.garden.dump()} ${scene.evidence()}") }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.garden.onBackground() }
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
    scene.capSites = remember(st.version) { graph.garden.tree.cutSites.map { it.nodeId }.toSet() }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(PotTargets, remember(graph, scene) { PotListener(graph, scene) }, BoardInputConfig(dragStartM = 0.02f, logTag = TAG)),
        attachments = { AttachmentPanel(SIGN) { SignRoot(graph) } },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            runCatching { scene.installBasics() }.onFailure { Log.e(TAG, "BASICS failed", it) }
            scene.attachSign(attachments.entity(SIGN))
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            // the Tripo props load in their own coroutine: initial returns at once so update() (region, tree) runs (S00)
            graph.scope.launch {
                val cache = HashMap<String, Entity>()
                runCatching {
                    scene.installProps { n ->
                        cache.getOrPut(n) { withContext(NonCancellable + Dispatchers.Main) { Entity.loadSuspend("asset://models/$n.glb") }.also { it.enabled = false; content.addEntity(it) } }
                    }
                }.onFailure { Log.e(TAG, "PROPS failed", it) }
            }
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(st, settings)
        },
    )
}

/** The season sign is its own composition: it reads state + language itself (附着面板拿不到外层 CompositionLocal). */
@Composable
private fun SignRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.garden.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { Sign(st, graph) }
    }
}

/** 375 x 100 dp ≈ 0.30 x 0.08 m: only the short keys of design signStrings; the same source as the window (D38). */
@Composable
private fun Sign(st: GardenState, graph: Graph) {
    val ctx = LocalContext.current
    Box(Modifier.size(375.dp, 100.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2F6F1E6)), contentAlignment = Alignment.Center) {
        if (graph.window.panelState == "ON_DESTROY") {
            Button(onClick = { graph.window.open("sign") }, modifier = Modifier.size(340.dp, 84.dp)) {
                // design-style: inherited-content-color Button
                Text(ctx.getString(R.string.sign_open_panel), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            val text = Texts.sign(ctx, st, SystemClock.uptimeMillis())
            Text(text, color = BonsaiColors.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
                onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW sign \"$text\"") })
        }
    }
}

/** Hit entity (or an ancestor) -> the holder name: pad:<id>, seg:<id>, trunk, ring, pot, tray, can, turnL, turnR. */
internal object PotTargets : TargetResolver<String> by TargetResolver.byName(PotTargets::targetOf) {
    private val FIXED = setOf("trunk", "ring", "pot", "tray", "can", "turnL", "turnR")
    fun targetOf(name: String): String? = when {
        name in FIXED -> name
        name.startsWith("pad:") || name.startsWith("seg:") -> name.takeIf { it.substring(4).toIntOrNull() != null }
        else -> null
    }

    fun resolvePath(path: List<String>): String? = io.embodit.bonsai.ui.scene.input.resolvePath(path, ::targetOf)
}

/** Holder name -> the one garden call (the debug broadcast and the window use the same calls). */
internal fun routeTap(garden: Garden, name: String?, openPanel: () -> Unit = {}) {
    when {
        name == null -> Unit
        name.startsWith("pad:") || name.startsWith("seg:") -> garden.tapNode(name.substring(4).toInt())
        name == "trunk" -> garden.tapNode(0.coerceAtLeast(garden.tree.nodes.indexOfFirst { it.kind == io.embodit.bonsai.domain.Kind.TRUNK && it.parent >= 0 }))
        name == "ring" -> garden.tapRing()
        name == "pot" || name == "tray" -> garden.tapBase(name)
        name == "can" -> garden.water("3D can")
        name == "turnL" -> garden.turnStep(-1)
        name == "turnR" -> garden.turnStep(1)
        name == "sign" -> openPanel()
    }
}

/** Drag on the pot / stand turns it (design turnInput: 720°/m horizontal, per-event deltas summed by the template). */
internal class PotListener(private val graph: Graph, private val scene: BonsaiScene) : BoardInputListener<String> {
    private var dragging = false
    private var startYaw = 0f
    private var holdJob: kotlinx.coroutines.Job? = null

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        if (target == "turnL" || target == "turnR") scene.pressed(target)
        routeTap(graph.garden, target) { graph.window.open("sign") }
    }

    /** ◀ ▶ held: after 0.5 s another 45° every 0.4 s (design turnButtons). */
    override fun onPress(target: String?, source: InputSource) {
        if (target != "turnL" && target != "turnR") return
        holdJob?.cancel()
        holdJob = graph.scope.launchHold(if (target == "turnL") -1 else 1)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchHold(d: Int) = launch {
        delay(500)
        while (true) { graph.garden.turnStep(d); delay(400) }
    }

    override fun onRelease() { holdJob?.cancel(); holdJob = null }

    // gate:allow shared-drag 盆景桌面件，拖动为辅，主路径 3D 转盆钮
    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        if (target == "turnL" || target == "turnR") return true
        if (target != "pot") { routeTap(graph.garden, target); return true }
        startYaw = graph.garden.state.value.yawDeg
        dragging = true
        return true
    }

    override fun onDragMove(totalM: Offset3M) {
        if (!dragging) return
        val deg = (totalM.x / scene.scaleNow * 720f).coerceIn(-720f, 720f)
        graph.garden.turnDrag(startYaw + deg)
    }

    /** D50 hover: the system HoverEffect lights the pad / branch / button; the app only logs (hint, never a state change). */
    override fun onHover(target: String?, source: InputSource) {}

    override fun onDragEnd() { if (dragging) graph.garden.turnRelease(); dragging = false }
    override fun onDragCancel() { if (dragging) graph.garden.turnRelease(); dragging = false }
}

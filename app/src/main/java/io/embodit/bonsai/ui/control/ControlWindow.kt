package io.embodit.bonsai.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.bonsai.BuildConfig
import io.embodit.bonsai.R
import io.embodit.bonsai.domain.ForeseeState
import io.embodit.bonsai.domain.Growth
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.LeafState
import io.embodit.bonsai.domain.Page
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.Species
import io.embodit.bonsai.platform.BackKeys
import io.embodit.bonsai.platform.Graph
import io.embodit.bonsai.platform.TAG
import io.embodit.bonsai.ui.Texts
import kotlin.system.exitProcess

/**
 * The control window (Planar 520 x 600 dp beside the volume): three pages switched by the native SpatialUI TabBar (D8):
 * 预见 / 挑一盆 / 设置 (+ 怎么操作). No page scrolls; every page has <= 7 clickable things including the three tabs (design
 * controlCount; mute / reopen / exit are the B31 exceptions). Controller keys never press a button (D60): buttons cannot
 * take focus and the window eats A / X / Y / ENTER; BACK / B go to the one handler (Graph.onBack).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.foresee.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name); if (e.name == "ON_RESUME") BackKeys.sweep("control") }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(st.page, st.howTo, st.confirmExit) { Log.i(TAG, "PAGE ${st.page} howTo=${st.howTo} confirm=${st.confirmExit}") }

    Box(Modifier.fillMaxSize().eatPadKeys("control").padding(horizontal = 28.dp, vertical = 24.dp)) {
        when (st.page) {
            Page.FORESEE -> ForeseePage(st, settings, graph)
            Page.PICK -> PickPage(st, graph)
            Page.SETTINGS -> if (st.howTo) HowToPage(graph) else SettingsPage(st, settings, graph)
        }
    }
    val focus = LocalFocusManager.current
    NativeTabs(
        listOf(
            Triple(Page.FORESEE, R.drawable.ic_tab_tend, stringResource(R.string.tab_foresee)),
            Triple(Page.PICK, R.drawable.ic_tab_shelf, stringResource(R.string.tab_pick)),
            Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
        ),
        st.page,
    ) { graph.foresee.page(it); focus.clearFocus(force = true); BackKeys.sweep("tab") }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, content = content)
}

// ------------------------------------------------------------------ 预见

@Composable
private fun ForeseePage(st: ForeseeState, settings: Settings, graph: Graph) {
    val ctx = LocalContext.current
    val f = graph.foresee
    val g = f.growth(st)
    PageColumn {
        Column {
            Txt(Texts.title(ctx, st), "foresee/title", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            val bare = g?.leafState(st.day) == LeafState.BARE
            Txt(Texts.dateLine(ctx, g, st.day) + if (bare) "  ·  " + stringResource(R.string.bare_normal) else "", "foresee/date",
                style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        TimeSlider(st.day, onScrub = { f.scrub(it, "window") }, onRelease = { f.release("window") })
        if (st.guideStep > 0) GuideCard(st, graph) else Legend()
        Txt(Texts.thisMonth(ctx, st, g), "foresee/month", style = PicoTheme.typography.bodyLarge, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ToggleButton(stringResource(if (settings.dashed) R.string.btn_dashed_on else R.string.btn_dashed_off), "foresee/dashed", settings.dashed, onClick = { f.toggleDashed() }, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_unmute), "foresee/mute", !settings.sound,
                onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.width(150.dp))
        }
    }
}

/** 0..365 days, 64 dp tall; the four stop names sit under the bar on its axis (B31 补); release snaps within 6 days. */
@Composable
private fun TimeSlider(day: Int, onScrub: (Int) -> Unit, onRelease: () -> Unit) {
    val ctx = LocalContext.current
    var pos by remember { mutableFloatStateOf(day.toFloat()) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(day) { if (!dragging) pos = day.toFloat() }
    val scrub = rememberUpdatedState(onScrub); val rel = rememberUpdatedState(onRelease)
    Column(Modifier.fillMaxWidth()) {
        // the four stops are evenly spaced on the slider like on the 3D bar: slider value = bar position 0..1
        Slider(
            value = pos01(pos),
            onValueChange = { v -> dragging = true; val d = from01(v); pos = d.toFloat(); scrub.value(d) },
            onValueChangeFinished = { dragging = false; rel.value() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            valueRange = 0f..1f,
        )
        Row(Modifier.fillMaxWidth()) {
            for (k in 0..3) {
                val on = day == Growth.STOPS[k]
                Txt(Texts.stop(ctx, k), "foresee/stop$k", style = PicoTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                    align = when (k) { 0 -> TextAlign.Start; 3 -> TextAlign.End; else -> TextAlign.Center }, modifier = Modifier.weight(if (k == 0 || k == 3) 0.5f else 1f))
            }
        }
    }
}

/** Slider position 0..1 <-> day, with the stops evenly spaced (design spatialLayout.timeBar). */
internal fun pos01(day: Float): Float = (Growth.dayToX(day.toInt()) - Growth.TICK_X[0]) / (Growth.TICK_X[3] - Growth.TICK_X[0])
internal fun from01(v: Float): Int = Growth.xToDay(Growth.TICK_X[0] + v.coerceIn(0f, 1f) * (Growth.TICK_X[3] - Growth.TICK_X[0]))

/** The dashed-line legend: a small drawing (a leaf clump, the dashed ring, a yellow shoot poking out) + two lines (design ②). */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillSecondary).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(76.dp, 64.dp)) {
            val w = size.width; val h = size.height
            drawOval(Color(0xFF5C7D43), Offset(w * 0.12f, h * 0.30f), Size(w * 0.58f, h * 0.42f))
            drawOval(Color(0xFF3C3C3C), Offset(w * 0.04f, h * 0.20f), Size(w * 0.74f, h * 0.62f), style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
            drawLine(Color(0xFF6B4A2E), Offset(w * 0.62f, h * 0.40f), Offset(w * 0.90f, h * 0.12f), strokeWidth = 4f)
            drawCircle(Color(0xFFFFD400), radius = h * 0.10f, center = Offset(w * 0.90f, h * 0.12f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.legend_dashed), "foresee/legend1", style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
            Txt(stringResource(R.string.legend_yellow), "foresee/legend2", style = PicoTheme.typography.labelLarge, maxLines = 2)
        }
    }
}

/** First-run guide in place of the legend (design S01g): step 1 drag the time, step 2 dashed line + yellow + [知道了]. */
@Composable
private fun GuideCard(st: ForeseeState, graph: Graph) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(2.dp, Color(0xFFE5A93B), RoundedCornerShape(20.dp)).background(Color(0x1FE5A93B)).padding(14.dp)) {
        Txt(stringResource(R.string.guide_step, st.guideStep), "guide/step", style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(if (st.guideStep == 1) R.string.guide_1 else R.string.guide_2), "guide/text", style = PicoTheme.typography.bodyLarge, maxLines = 3, modifier = Modifier.weight(1f))
            if (st.guideStep == 2) { Spacer(Modifier.width(10.dp)); SecondaryButton(stringResource(R.string.btn_got_it), "guide/ok", onClick = { graph.foresee.finishGuide() }, modifier = Modifier.width(130.dp)) }
        }
    }
}

// ------------------------------------------------------------------ 挑一盆

@Composable
private fun PickPage(st: ForeseeState, graph: Graph) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(stringResource(R.string.pick_hint), "pick/hint", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        for (row in Species.ALL.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (sp in row) SpeciesCard(sp, sp == st.species, Modifier.weight(1f)) { graph.foresee.pick(sp, "window") }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpeciesCard(sp: Species, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier.height(150.dp).clip(RoundedCornerShape(22.dp))
            .background(PicoTheme.colorScheme.fillSecondary)
            .border(if (on) 3.dp else 0.dp, if (on) PicoTheme.colorScheme.interaction else Color.Transparent, RoundedCornerShape(22.dp))
            .then(NoFocus).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(thumb(sp)), contentDescription = Texts.species(ctx, sp), contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp, 120.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(Texts.species(ctx, sp) + if (on) "  ✓" else "", "pick/${sp.id}/name", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2)
            Txt(Texts.slogan(ctx, sp), "pick/${sp.id}/slogan", style = PicoTheme.typography.labelMedium, maxLines = 3)
        }
    }
}

private fun thumb(sp: Species) = when (sp.file) { "pine" -> R.drawable.thumb_pine; "juniper" -> R.drawable.thumb_juniper; "elm" -> R.drawable.thumb_elm; "quemei" -> R.drawable.thumb_quemei; else -> R.drawable.thumb_ficus }

// ------------------------------------------------------------------ 设置

@Composable
private fun SettingsPage(st: ForeseeState, settings: Settings, graph: Graph) {
    val ctx = LocalContext.current
    PageColumn {
        val lang = stringResource(when (settings.language) { Language.SYSTEM -> R.string.lang_system; Language.ZH -> R.string.lang_zh; Language.EN -> R.string.lang_en })
        SecondaryButton(lang, "settings/language", onClick = { graph.settings.update { it.copy(language = it.language.next()) } }, modifier = Modifier.fillMaxWidth(), tall = true)
        OptionRow(stringResource(R.string.volume), "settings/volume", labelWidth = 96.dp, rowHeight = 56.dp) {
            VolumeSlider(settings.volume, onChange = { v -> graph.settings.update { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.sound), "settings/sound", settings.sound) { on -> graph.settings.update { it.copy(sound = on) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.how_to), "settings/how-to", onClick = { graph.foresee.howTo(true) }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.reopen_panel), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.weight(1f), tall = true)
        }
        if (st.confirmExit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt(stringResource(R.string.confirm_exit), "settings/confirm-exit", style = PicoTheme.typography.labelLarge, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_exit), "settings/exit-yes", onClick = { exitApp(ctx, graph) }, danger = true, modifier = Modifier.width(110.dp), tall = true)
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-no", onClick = { graph.foresee.confirmExit(false) }, modifier = Modifier.width(110.dp), tall = true)
            }
        } else {
            SecondaryButton(stringResource(R.string.exit_app), "settings/exit", onClick = { graph.foresee.confirmExit(true) }, danger = true, modifier = Modifier.fillMaxWidth(), tall = true)
        }
        Txt(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun HowToPage(graph: Graph) {
    PageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.how_to), "how-to/title", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_back), "how-to/back", onClick = { graph.foresee.howTo(false) }, modifier = Modifier.width(120.dp), tall = true)
        }
        for ((t, s) in listOf(R.string.how_to_t_time to R.string.how_to_time, R.string.how_to_t_tip to R.string.how_to_tip, R.string.how_to_t_turn to R.string.how_to_turn, R.string.how_to_t_pick to R.string.how_to_pick)) {
            Column(Modifier.fillMaxWidth()) {
                Txt(stringResource(t), "how-to/t", style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Txt(stringResource(s), "how-to/row", style = PicoTheme.typography.bodyMedium, maxLines = 3)
            }
        }
    }
}

// ------------------------------------------------------------------ exit, tabs

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.foresee.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language; items never keep focus (D60). */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>, current: Page, onPick: (Page) -> Unit) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = current == page,
                onClick = { onPick(page) },
                mainContent = {
                    Column(Modifier.eatPadKeys("tab:${page.name}").then(NoFocus), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        val fg = LocalContentColor.current
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = fg, maxLines = 1)
                    }
                },
            )
        }
    }
}

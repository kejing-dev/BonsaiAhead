package io.embodit.bonsai.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.bonsai.BuildConfig
import io.embodit.bonsai.R
import io.embodit.bonsai.domain.Confirm
import io.embodit.bonsai.domain.Form
import io.embodit.bonsai.domain.Garden
import io.embodit.bonsai.domain.GardenState
import io.embodit.bonsai.domain.Kind
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.NodeV
import io.embodit.bonsai.domain.Page
import io.embodit.bonsai.domain.Season
import io.embodit.bonsai.domain.Settings
import io.embodit.bonsai.domain.Tree
import io.embodit.bonsai.domain.view
import io.embodit.bonsai.platform.Graph
import io.embodit.bonsai.platform.TAG
import io.embodit.bonsai.ui.Texts
import io.embodit.bonsai.ui.theme.BonsaiColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar 520 x 600 dp beside the pot): three pages switched by the native SpatialUI TabBar (D8):
 * 盆景 / 架子 / 设置. Confirmations open in place. No page scrolls; every page has <= 7 clickable things including the three
 * tabs (design controlCount; mute / reopen / quit are the B31 exceptions).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val st by graph.garden.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // a clock tick for the 1.5 s notes and the +n / -n badge
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(250); now = SystemClock.uptimeMillis() } }
    LaunchedEffect(st.page, st.picking, st.howTo, st.confirm, st.viewing, st.doneCard) { Log.i(TAG, "PAGE ${st.page} pick=${st.picking} howTo=${st.howTo} confirm=${st.confirm} viewing=${st.viewing != null} done=${st.doneCard}") }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
        when {
            st.picking -> PickPage(st, graph)
            st.page == Page.TEND -> if (st.doneCard && st.viewing == null) DonePage(st, graph) else TendPage(st, settings, graph, now)
            st.page == Page.SHELF -> ShelfPage(st, graph)
            else -> if (st.howTo) HowToPage(graph) else SettingsPage(st, settings, graph)
        }
    }
    if (!st.picking) {
        NativeTabs(
            listOf(
                Triple(Page.TEND, R.drawable.ic_tab_tend, stringResource(R.string.tab_tend)),
                Triple(Page.SHELF, R.drawable.ic_tab_shelf, stringResource(R.string.tab_shelf)),
                Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
            ),
            st.page,
        ) { graph.garden.page(it) }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

@Composable
private fun IconPrimary(text: String, icon: Int, tag: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    val fg = PicoTheme.colorScheme.labelPrimaryLight
    Button(
        onClick = onClick, modifier = modifier.height(80.dp), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 80.dp, minWidth = 160.dp), shape = RoundedCornerShape(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = fg),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = fg)
    }
}

// ------------------------------------------------------------------ Tend (S03 / S03g / S04 / S07 / S11)

@Composable
private fun TendPage(st: GardenState, settings: Settings, graph: Graph, now: Long) {
    val g = graph.garden
    val ctx = LocalContext.current
    val viewing = st.viewing
    PageColumn {
        // title row: "第 2 年 · 夏" + form name, mute on the right (always shown, D15)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (viewing != null) {
                    Txt(stringResource(R.string.viewing, Texts.name(ctx, viewing.name)), "tend/viewing", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                } else {
                    Txt(stringResource(R.string.title_year, st.year, Texts.season(ctx, st.look)), "tend/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Txt(Texts.form(ctx, st.form), "tend/form", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_unmute), "tend/mute", on = !settings.sound,
                onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.width(150.dp).height(56.dp))
        }
        if (viewing != null) {
            Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.Center) { Thumb(viewing.nodes, viewing.form, Modifier.size(220.dp, 160.dp)) }
            Txt(stringResource(R.string.st_viewing), "tend/status", style = PicoTheme.typography.bodyLarge, maxLines = 2)
            if (st.confirm == Confirm.DELETE) {
                Txt(stringResource(R.string.confirm_delete), "tend/confirm-delete", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(stringResource(R.string.btn_delete_yes), "tend/delete-yes", onClick = { g.deleteViewed() }, danger = true, tall = true, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.btn_never_mind), "tend/delete-no", onClick = { g.ask(null) }, tall = true, modifier = Modifier.weight(1f))
                }
            } else {
                PrimaryButton(stringResource(R.string.btn_back_mine), "tend/back-mine", onClick = { g.backToMine() }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToggleButton(stringResource(if (settings.outline) R.string.btn_outline_on else R.string.btn_outline_off), "tend/outline", on = settings.outline, onClick = { g.toggleOutline() }, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.btn_delete), "tend/delete", onClick = { g.ask(Confirm.DELETE) }, tall = true, modifier = Modifier.weight(1f))
                }
            }
            return@PageColumn
        }
        // score: big number + ring to the goal + the last change
        Row(Modifier.fillMaxWidth().height(132.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(st.score, st.threshold, Modifier.size(124.dp).semantics { contentDescription = ctx.getString(R.string.a11y_score, st.score, st.threshold) })
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.like), "tend/like", style = PicoTheme.typography.titleMedium)
                Txt(stringResource(R.string.like_goal, st.threshold), "tend/goal", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
                if (st.scoreDelta != 0 && now - st.deltaAt < Garden.DELTA_MS) {
                    Txt((if (st.scoreDelta > 0) "+" else "−") + kotlin.math.abs(st.scoreDelta), "tend/delta", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (st.scoreDelta > 0) BonsaiColors.PrimaryDeep else BonsaiColors.ErrorText)
                }
            }
        }
        // status (<= 2 lines) + small print, and the guide's [跳过] / [知道了] at the end of the row
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(Texts.status(ctx, st, now), "tend/status", style = PicoTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                Texts.smallPrint(ctx, st, now)?.let { Txt(it, "tend/small", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2) }
            }
            if (st.guideStep in 1..3 && st.selection == null) {
                Spacer(Modifier.width(12.dp))
                SecondaryButton(stringResource(if (st.guideStep == 3) R.string.btn_got_it else R.string.btn_skip), "tend/skip", onClick = { g.skipGuide() }, modifier = Modifier.width(116.dp))
            }
        }
        if (st.selection != null) {
            IconPrimary(stringResource(R.string.btn_snip), R.drawable.ic_cut, "tend/snip", onClick = { g.confirmCut("window") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.btn_in), "tend/in", onClick = { g.stepIn() }, tall = true, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_out), "tend/out", onClick = { g.stepOut() }, tall = true, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_cancel), "tend/cancel", onClick = { g.cancelSelection() }, tall = true, modifier = Modifier.weight(1f))
            }
        } else {
            IconPrimary(stringResource(R.string.btn_water), R.drawable.ic_water, "tend/water", onClick = { g.water("window") }, enabled = now >= st.busyUntil)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(if (st.undoBlocked) R.string.btn_undo_watered else R.string.btn_undo), "tend/undo", onClick = { g.undo() }, enabled = st.canUndo, tall = true, modifier = Modifier.weight(1f))
                ToggleButton(stringResource(if (settings.outline) R.string.btn_outline_on else R.string.btn_outline_off), "tend/outline", on = settings.outline, onClick = { g.toggleOutline() }, modifier = Modifier.weight(1f))
                if (st.guideStep == 0) SecondaryButton(stringResource(R.string.btn_look, Texts.season(ctx, st.look)), "tend/look", onClick = { g.cycleLook() }, tall = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int, goal: Int, modifier: Modifier) {
    val track = PicoTheme.colorScheme.fillSecondary
    val prim = PicoTheme.colorScheme.interaction
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 12.dp.toPx()
            val s = Size(size.width - w, size.height - w)
            val o = Offset(w / 2, w / 2)
            drawArc(track, -90f, 360f, false, o, s, style = Stroke(w))
            drawArc(prim, -90f, 360f * score / 100f, false, o, s, style = Stroke(w))
            // the goal tick
            val a = Math.toRadians(-90.0 + 360.0 * goal / 100.0)
            val r0 = size.width / 2 - w * 1.4f; val r1 = size.width / 2 + 0f
            val c = Offset(size.width / 2, size.height / 2)
            drawLine(BonsaiColors.TerraDeep, c + Offset((r0 * kotlin.math.cos(a)).toFloat(), (r0 * kotlin.math.sin(a)).toFloat()), c + Offset((r1 * kotlin.math.cos(a)).toFloat(), (r1 * kotlin.math.sin(a)).toFloat()), strokeWidth = 4.dp.toPx())
        }
        Text("$score", style = PicoTheme.typography.displaySmall.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold), color = ink, maxLines = 1,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW tend/score") })
    }
}

// ------------------------------------------------------------------ Done (S09)

@Composable
private fun DonePage(st: GardenState, graph: Graph) {
    val g = graph.garden
    val ctx = LocalContext.current
    PageColumn {
        Txt(stringResource(R.string.done_title), "done/title", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = BonsaiColors.TerraDeep)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(Texts.name(ctx, st.name), "done/name", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_rename), "done/rename", onClick = { g.rename() }, modifier = Modifier.width(150.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(stringResource(R.string.done_like), "${st.score}")
            Stat(stringResource(R.string.done_years), "${st.year}")
            Stat(stringResource(R.string.done_cuts), "${st.totalCuts}")
        }
        PrimaryButton(stringResource(R.string.btn_shelve), "done/shelve", onClick = { g.putOnShelf() }, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.btn_keep), "done/keep", onClick = { g.keepGrowing() }, tall = true, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.small_bud), "done/small", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(value, "done/stat", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Txt(label, "done/stat-label", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
    }
}

// ------------------------------------------------------------------ Shelf (S10 / S13 change)

@Composable
private fun ShelfPage(st: GardenState, graph: Graph) {
    val g = graph.garden
    val ctx = LocalContext.current
    val own = g.pendingOwn(st)
    val total = g.shelfCards(st)
    PageColumn {
        if (st.confirm == Confirm.CHANGE) {
            Spacer(Modifier.height(40.dp))
            Txt(stringResource(if (st.shelved) R.string.confirm_change_saved else R.string.confirm_change_unsaved), "shelf/confirm", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(stringResource(R.string.btn_change_yes), "shelf/change-yes", onClick = { g.openPick() }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_never_mind), "shelf/change-no", onClick = { g.ask(null) }, tall = true, modifier = Modifier.weight(1f).height(80.dp))
            }
            Spacer(Modifier.height(40.dp))
            return@PageColumn
        }
        if (total == 0) {
            Spacer(Modifier.height(60.dp))
            Txt(stringResource(R.string.shelf_empty), "shelf/empty", style = PicoTheme.typography.titleMedium, maxLines = 3, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            PrimaryButton(stringResource(R.string.btn_change), "shelf/change", onClick = { g.askChange() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(60.dp))
            return@PageColumn
        }
        val idx = st.shelfIndex.coerceIn(0, total - 1)
        val isOwn = own && idx == 0
        val item = if (isOwn) null else st.shelf.getOrNull(idx - if (own) 1 else 0)
        val nodes: List<NodeV> = item?.nodes ?: g.tree.view()
        val form = item?.form ?: st.form
        val highlight = item != null && item.id == st.shelfHighlight
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BonsaiColors.CardFace)
                .border(if (highlight) 3.dp else 0.dp, if (highlight) BonsaiColors.Terra else Color.Transparent, RoundedCornerShape(20.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Thumb(nodes, form, Modifier.size(260.dp, 200.dp))
            Txt(if (isOwn) stringResource(R.string.shelf_yours) else Texts.name(ctx, item!!.name), "shelf/name", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = BonsaiColors.Ink, maxLines = 2, align = TextAlign.Center)
            Txt(stringResource(R.string.shelf_line, item?.year ?: st.year, item?.score ?: st.score) + (item?.let { " · " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it.dateMs)) } ?: ""),
                "shelf/line", style = PicoTheme.typography.bodyMedium, color = BonsaiColors.InkSecondary, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_prev), "shelf/prev", onClick = { g.shelfStep(-1) }, enabled = total > 1, tall = true, modifier = Modifier.weight(1f))
            Txt(stringResource(R.string.shelf_count, idx + 1, total), "shelf/count", style = PicoTheme.typography.labelLarge, align = TextAlign.Center, modifier = Modifier.width(96.dp))
            SecondaryButton(stringResource(R.string.btn_next), "shelf/next", onClick = { g.shelfStep(1) }, enabled = total > 1, tall = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (isOwn) PrimaryButton(stringResource(R.string.btn_shelve), "shelf/shelve", onClick = { g.putOnShelf() }, modifier = Modifier.weight(1f))
            else PrimaryButton(stringResource(R.string.btn_view), "shelf/view", onClick = { g.view(idx - if (own) 1 else 0) }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_change), "shelf/change", onClick = { g.askChange() }, tall = true, modifier = Modifier.weight(1f).height(80.dp))
        }
    }
}

// ------------------------------------------------------------------ Pick (S02)

@Composable
private fun PickPage(st: GardenState, graph: Graph) {
    val g = graph.garden
    val ctx = LocalContext.current
    val samples = remember { Form.entries.associateWith { Tree.create(it, 2026).view() } }
    PageColumn {
        Txt(stringResource(R.string.pick_title), "pick/title", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        for (f in Form.entries) {
            val sel = st.pickForm == f
            Row(
                Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (sel) BonsaiColors.PrimaryContainer else BonsaiColors.CardFace)
                    .border(if (sel) 3.dp else 0.dp, if (sel) BonsaiColors.PrimaryDeep else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable { g.pickForm(f) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(samples[f]!!, f, Modifier.size(110.dp, 88.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(Texts.form(ctx, f), "pick/form", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = BonsaiColors.Ink)
                    Txt(stringResource(slogan(f)), "pick/slogan", style = PicoTheme.typography.bodyMedium, color = BonsaiColors.InkSecondary, maxLines = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(stringResource(R.string.btn_start), "pick/start", onClick = { g.startPot() }, modifier = Modifier.weight(1.4f))
            SecondaryButton(stringResource(R.string.btn_never_mind), "pick/cancel", onClick = { g.cancelPick() }, tall = true, modifier = Modifier.weight(1f).height(80.dp))
        }
    }
}

private fun slogan(f: Form) = when (f) { Form.CHOKKAN -> R.string.slogan_chokkan; Form.SHAKAN -> R.string.slogan_shakan; Form.KENGAI -> R.string.slogan_kengai }

// ------------------------------------------------------------------ Settings (S12 / S12h / S13 exit)

@Composable
private fun SettingsPage(st: GardenState, settings: Settings, graph: Graph) {
    val g = graph.garden
    val ctx = LocalContext.current
    PageColumn {
        val langWord = stringResource(when (settings.language) { Language.SYSTEM -> R.string.lang_system; Language.ZH -> R.string.lang_zh; Language.EN -> R.string.lang_en })
        SecondaryButton(stringResource(R.string.set_language, langWord), "settings/language", onClick = { graph.settings.update { it.copy(language = it.language.next()) } }, modifier = Modifier.fillMaxWidth())
        OptionRow(stringResource(R.string.set_volume), "settings/volume", labelWidth = 96.dp, rowHeight = 56.dp) {
            VolumeSlider(settings.volume, onChange = { v -> graph.settings.update { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.set_sound), "settings/sound", settings.sound) { on -> graph.settings.update { it.copy(sound = on) } }
        SwitchRow(stringResource(R.string.set_motion), "settings/motion", settings.motion) { on -> graph.settings.update { it.copy(motion = on) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_how_to), "settings/how-to", onClick = { g.howTo(true) }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.reopen() }, modifier = Modifier.weight(1f))
        }
        if (st.confirm == Confirm.EXIT) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(stringResource(R.string.confirm_exit), "settings/confirm-exit", style = PicoTheme.typography.labelLarge, maxLines = 2, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_exit_yes), "settings/exit-yes", onClick = { exitApp(ctx, graph) }, danger = true, modifier = Modifier.width(110.dp))
                SecondaryButton(stringResource(R.string.btn_never_mind), "settings/exit-no", onClick = { g.ask(null) }, modifier = Modifier.width(110.dp))
            }
        } else {
            SecondaryButton(stringResource(R.string.btn_exit), "settings/exit", onClick = { g.ask(Confirm.EXIT) }, danger = true, modifier = Modifier.fillMaxWidth())
        }
        Txt(stringResource(R.string.about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun HowToPage(graph: Graph) {
    PageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.how_to_title), "how-to/title", style = PicoTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_back), "how-to/back", onClick = { graph.garden.howTo(false) }, modifier = Modifier.width(120.dp))
        }
        for ((icon, s) in listOf(R.drawable.ic_cut to R.string.how_to_cut, R.drawable.ic_water to R.string.how_to_water, R.drawable.ic_chev_right to R.string.how_to_turn,
            R.drawable.ic_cut to R.string.how_to_bigger, R.drawable.ic_undo to R.string.how_to_undo)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Spacer(Modifier.width(12.dp))
                Txt(stringResource(s), "how-to/row", style = PicoTheme.typography.bodyLarge, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------------ thumbnail (2D side view of the tree)

/** A side view of a tree (pot, trunk, branches, pads) drawn in 2D: the shelf cards and the pick cards. */
@Composable
internal fun Thumb(nodes: List<NodeV>, form: Form, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sc = minOf(w / 0.62f, h / 0.72f)
        fun px(x: Double) = (w / 2 + x * sc).toFloat()
        fun py(y: Double) = (h * 0.80f - y * sc).toFloat()
        // pot
        val pot = Path().apply { moveTo(px(-0.15), py(0.0)); lineTo(px(0.15), py(0.0)); lineTo(px(0.12), py(-0.07)); lineTo(px(-0.12), py(-0.07)); close() }
        drawPath(pot, Color(0xFF6E8C93))
        val map = nodes.associateBy { it.id }
        for (n in nodes.sortedBy { it.pos.z }) {
            val p = map[n.parent] ?: continue
            val wd = if (n.kind == Kind.TRUNK) (form.baseR * sc * 1.6).toFloat() else if (n.kind == Kind.PRIMARY) 3f else 1.6f
            drawLine(if (n.isLong) Color(0xFF9DB862) else Color(0xFF5A463A), Offset(px(p.pos.x), py(p.pos.y)), Offset(px(n.pos.x), py(n.pos.y)), strokeWidth = wd)
        }
        val leaf = Color(0xFF000000 or form.color(Season.SUMMER).toLong())
        for (n in nodes.filter { it.padR > 0 }.sortedBy { it.pos.z }) {
            val r = (n.padR * sc).toFloat()
            drawOval(if (n.isLong) Color(0xFFB5D27A) else leaf, Offset(px(n.pos.x) - r, py(n.pos.y) - r * 0.55f), Size(2 * r, 1.1f * r))
        }
    }
}

// ------------------------------------------------------------------ exit, tabs

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.garden.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>, current: Page, onPick: (Page) -> Unit) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = current == page,
                onClick = { onPick(page) },
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        // the word follows the item's content colour like the icon does (white on the selected capsule)
                        val fg = LocalContentColor.current
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = fg, maxLines = 1)
                    }
                },
            )
        }
    }
}

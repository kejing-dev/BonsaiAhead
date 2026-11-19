package io.embodit.bonsai.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.bonsai.domain.Confirm
import io.embodit.bonsai.domain.Form
import io.embodit.bonsai.domain.Kind
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.Page
import io.embodit.bonsai.domain.Season
import io.embodit.bonsai.ui.scene.SceneHolder
import io.embodit.bonsai.ui.scene.routeTap
import kotlinx.coroutines.launch

/**
 * QA / screenshot commands (debug builds only). Every input command goes through the same Garden calls as a real pinch or
 * button (D29), never a shortcut into the state.
 *   adb shell am broadcast -a io.embodit.bonsai.DEBUG -p io.embodit.bonsai --es cmd <cmd> --es a "<arg>"
 * Unknown / short arguments are logged and ignored (no exceptions).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val a = (intent.getStringExtra("a") ?: "").trim().trim('"')
        val g = Graph.from(context)
        val garden = g.garden
        Log.i(TAG, "DEBUG $cmd $a")
        runCatching {
            when (cmd) {
                "reset" -> garden.reset()
                "tap" -> routeTap(garden, if (a.toIntOrNull() != null) "pad:$a" else a) { g.window.open("debug") }
                // the long shoot / pad / branch an input would hit first: the highest live pad (or the guide's long shoots)
                "tappad" -> { val n = garden.tree.nodes.filter { it.alive && it.padR > 0 && (a != "long" || it.isLong) }.maxByOrNull { it.pos.y }; n?.let { garden.tapNode(it.id) } }
                "tapside" -> { val n = garden.tree.nodes.filter { it.alive && it.padR > 0 && !it.isLong }.maxByOrNull { it.pos.x }; n?.let { garden.tapNode(it.id) } }
                "taptrunk" -> garden.tapNode(garden.tree.nodes.first { it.kind == Kind.TRUNK && it.parent >= 0 }.id)
                "stepin" -> garden.stepIn()
                "stepout" -> garden.stepOut()
                "snip" -> garden.confirmCut("debug")
                "cancel" -> garden.cancelSelection()
                "water" -> g.scope.launch { repeat(a.toIntOrNull() ?: 1) { garden.water("debug"); kotlinx.coroutines.delay(1200) } }
                "undo" -> garden.undo()
                "look" -> garden.setLook(Season.valueOf(a.uppercase()))
                "lookcycle" -> garden.cycleLook()
                "form" -> garden.startPot(Form.of(a), a.substringAfter(' ', "").toLongOrNull() ?: 2026L)
                "pot" -> { val p = a.split(' '); garden.startPot(Form.of(p[0]), p.getOrNull(1)?.toLongOrNull() ?: 2026L) }
                "autoprune" -> g.scope.launch { garden.autoYears(a.toIntOrNull() ?: 1) }
                "randomcut" -> garden.randomCuts(a.toIntOrNull() ?: 3)
                "cut" -> garden.cutAbout(a.toDoubleOrNull() ?: 0.5)
                "turnbtn" -> routeTap(garden, if (a == "left") "turnL" else "turnR")
                "drag" -> { garden.turnDrag(a.toFloatOrNull() ?: 30f); garden.turnRelease() }
                "freeze" -> garden.freeze(a.toLongOrNull())
                "lang" -> g.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
                "sound" -> g.settings.update { it.copy(sound = a != "off") }
                "volume" -> g.settings.update { it.copy(volume = (a.toIntOrNull() ?: 2).coerceIn(0, 2)) }
                "motion" -> g.settings.update { it.copy(motion = a != "off") }
                "outline" -> g.settings.update { it.copy(outline = a != "off") }
                "page" -> garden.page(when (a) { "shelf" -> Page.SHELF; "settings" -> Page.SETTINGS; else -> Page.TEND })
                "howto" -> { garden.page(Page.SETTINGS); garden.howTo(a != "off") }
                "confirm" -> garden.ask(when (a) { "exit" -> Confirm.EXIT; "change" -> Confirm.CHANGE; "delete" -> Confirm.DELETE; else -> null })
                "pick" -> garden.openPick()
                "done" -> garden.forceDone()
                "shelve" -> garden.putOnShelf()
                "view" -> garden.view(a.toIntOrNull() ?: 0)
                "back" -> garden.backToMine()
                "guide" -> garden.guideTo(a.toIntOrNull() ?: 1)
                "closepanel" -> g.window.closeControlOnly()
                "openpanel" -> g.window.open("debug")
                "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", a != "off").apply()
                "clear" -> { g.store.clearAll(); g.settings.update { it.copy(guideDone = false) } }
                "dump" -> Log.i(TAG, "DUMP ${garden.dump()} ${SceneHolder.scene?.evidence()}")
                else -> Log.w(TAG, "DEBUG unknown $cmd")
            }
        }.onFailure { Log.w(TAG, "DEBUG $cmd failed: $it") }
    }
}

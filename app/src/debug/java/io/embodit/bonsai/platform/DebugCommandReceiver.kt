package io.embodit.bonsai.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.bonsai.domain.Growth
import io.embodit.bonsai.domain.Language
import io.embodit.bonsai.domain.Page
import io.embodit.bonsai.domain.Side
import io.embodit.bonsai.domain.Species
import io.embodit.bonsai.ui.scene.SceneHolder
import io.embodit.bonsai.ui.scene.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * QA / screenshot commands (debug builds only). Every input command goes through the same Foresee calls as a real pinch,
 * drag or button (D29), never a shortcut into the state.
 *   adb shell am broadcast -a io.embodit.bonsai.DEBUG -p io.embodit.bonsai --es cmd <cmd> --es a "<arg>"
 * Unknown / short arguments are logged and ignored (no exceptions). `reset` clears everything first (lessons: reset).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val a = (intent.getStringExtra("a") ?: "").trim().trim('"')
        val g = Graph.from(context)
        val f = g.foresee
        Log.i(TAG, "DEBUG $cmd $a")
        runCatching {
            when (cmd) {
                // fresh install: T0 2026-11-18 (QA fixed, stateModifiers), elm, day 0, guide on; `a` = another T0
                "reset" -> { f.reset(if (a.isNotEmpty()) LocalDate.parse(a) else LocalDate.of(2026, 11, 18)); f.page(Page.FORESEE) }
                "noguide" -> f.finishGuide()
                "guide" -> f.setGuide(a.toIntOrNull() ?: 1)
                "species" -> f.pick(Species.byId(a), "debug")
                "t0" -> f.setT0(LocalDate.parse(a))
                // the same scrub / release as the slider and the bead
                "day" -> { f.scrub(a.toInt(), "debug"); f.release("debug") }
                "scrub" -> f.scrub(a.toInt(), "debug")
                "release" -> f.release("debug")
                "stop" -> f.goStop(a.toInt(), "debug")
                "tap" -> routeTap(g, a, "debug")
                // tap the k-th yellow shoot of the left pot (default the first)
                "tapyellow" -> { val gr = f.growth(); val d = f.state.value.day; val list = gr?.vigour?.indices?.filter { gr.shoot(Side.LEFT_ALONE, it, d).highlight } ?: emptyList(); list.getOrNull(a.toIntOrNull() ?: 0)?.let { routeTap(g, "shoot:0:$it", "debug") } ?: Log.i(TAG, "DEBUG tapyellow: none") }
                "turn" -> routeTap(g, "pot", "debug")
                "yaw" -> f.setYaw(a.toFloat())
                "dashed" -> { val want = a != "off"; if (g.settings.value.dashed != want) f.toggleDashed() }
                "page" -> when (a) { "pick" -> f.page(Page.PICK); "settings" -> f.page(Page.SETTINGS); "howto" -> f.howTo(true); else -> f.page(Page.FORESEE) }
                "confirm" -> f.confirmExit(a != "off")
                "lang" -> g.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
                "sound" -> g.settings.update { it.copy(sound = a != "off") }
                "volume" -> g.settings.update { it.copy(volume = a.toInt().coerceIn(0, 2)) }
                "closepanel" -> g.window.closeControlOnly()
                "openpanel" -> g.window.open("debug")
                "reopen" -> g.window.reopen()
                "back" -> g.onBack("debug")
                "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", a == "on").apply()
                // D59: n times scrub 0 <-> 365 and pick every species, through the real calls
                "stress" -> g.scope.launch {
                    val n = a.toIntOrNull() ?: 20
                    repeat(n) { k ->
                        for (d in listOf(365, 182, 30, 0)) { f.scrub(d, "stress"); delay(60) }
                        f.release("stress")
                        f.pick(Species.ALL[k % Species.ALL.size], "stress"); delay(400)
                    }
                    Log.i(TAG, "STRESS done n=$n ${SceneHolder.scene?.evidence()}")
                }
                "dump" -> Log.i(TAG, "DUMP ${f.dump()} stops=${Growth.STOPS.toList()} ${SceneHolder.scene?.evidence()} panel=${g.window.panelState}")
                else -> Log.w(TAG, "DEBUG unknown $cmd")
            }
        }.onFailure { Log.w(TAG, "DEBUG $cmd failed: $it") }
    }
}

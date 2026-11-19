package io.embodit.bonsai.ui

import android.content.Context
import io.embodit.bonsai.R
import io.embodit.bonsai.domain.ForeseeState
import io.embodit.bonsai.domain.Growth
import io.embodit.bonsai.domain.LeafState
import io.embodit.bonsai.domain.Side
import io.embodit.bonsai.domain.Species

/** All words the volume and the window show, from string resources only (one source, D38; zh / en). */
object Texts {
    private fun res(ctx: Context, name: String): Int = ctx.resources.getIdentifier(name, "string", ctx.packageName)
    private fun str(ctx: Context, name: String): String = res(ctx, name).takeIf { it != 0 }?.let { ctx.getString(it) } ?: name

    fun species(ctx: Context, s: Species) = str(ctx, "sp_${s.id}")
    fun slogan(ctx: Context, s: Species) = str(ctx, "slogan_${s.id}")
    fun form(ctx: Context, s: Species) = str(ctx, "form_${s.id}")
    fun rule(ctx: Context, s: Species) = str(ctx, "rule_${s.id}")
    fun stop(ctx: Context, k: Int) = str(ctx, "stop_$k")
    fun month(ctx: Context, m: Int) = str(ctx, "month_$m")

    /** "榆树 · 1 个月后" / "Chinese elm · 1 month on". */
    fun title(ctx: Context, st: ForeseeState): String {
        val sp = species(ctx, st.species)
        val k = Growth.STOPS.indexOf(st.day)
        return when {
            st.day == 0 -> ctx.getString(R.string.title_today, sp)
            k > 0 -> ctx.getString(R.string.title_after, sp, stop(ctx, k))
            else -> ctx.getString(R.string.title_days, sp, st.day)
        }
    }

    /** "12 月 18 日 · 冬". */
    fun dateLine(ctx: Context, g: Growth?, day: Int): String {
        g ?: return ""
        val d = g.date(day)
        val season = when (d.monthValue) { in 3..5 -> 1; in 6..8 -> 2; in 9..11 -> 3; else -> 4 }
        return ctx.getString(R.string.date_line, ctx.getString(R.string.date_fmt, month(ctx, d.monthValue), d.dayOfMonth), str(ctx, "season_$season"))
    }

    /** "这个月：…" (design S01–S05 window line). */
    fun thisMonth(ctx: Context, st: ForeseeState, g: Growth?): String {
        g ?: return ""
        val m = g.month(st.day)
        val todo = when {
            m in st.species.pruneMonths -> ctx.getString(R.string.todo_trim, rule(ctx, st.species))
            g.leafState(st.day) == LeafState.BARE || (!st.species.indoor && m in listOf(12, 1, 2)) -> ctx.getString(R.string.todo_rest)
            st.species.indoor -> ctx.getString(R.string.todo_grow_in)
            else -> ctx.getString(R.string.todo_grow_out)
        }
        return ctx.getString(R.string.this_month, todo)
    }

    /** The two lines of a pot tag (design content.tone.tags). Day 0: "今天" on both. */
    fun tag(ctx: Context, st: ForeseeState, side: Side, g: Growth?): Pair<String, String> {
        if (st.day == 0) return ctx.getString(R.string.tag_today) to ""
        return when (side) {
            Side.LEFT_ALONE -> ctx.getString(R.string.tag_left) to (g?.toTrim(st.day)?.takeIf { it > 0 }?.let { ctx.getString(R.string.tag_count, it) } ?: "")
            Side.TRIMMED -> ctx.getString(R.string.tag_right) to (g?.recentCutMonth(st.day)?.let { ctx.getString(R.string.tag_cut, month(ctx, it)) } ?: "")
        }
    }

    fun tip(ctx: Context, s: Species, month: Int) = ctx.getString(R.string.tip, month(ctx, month), rule(ctx, s))
}

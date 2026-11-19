package io.embodit.bonsai.ui

import android.content.Context
import io.embodit.bonsai.R
import io.embodit.bonsai.domain.Form
import io.embodit.bonsai.domain.Garden
import io.embodit.bonsai.domain.GardenState
import io.embodit.bonsai.domain.Names
import io.embodit.bonsai.domain.Note
import io.embodit.bonsai.domain.Season

/** One status line source (D38): the window's status row and the season sign both read [statusKey] of the same state. */
enum class StatusKey { VIEWING, SHELF_FULL, TRUNK, GUIDE1, GUIDE2, GUIDE3, PREVIEW, WEAK, RESTING, NEAR_WEAK, CROWDED, AFTER_CUT, NOT_YET, IDLE }

object Texts {
    fun statusKey(st: GardenState, now: Long): StatusKey {
        val noteLive = st.note != null && now - st.noteAt < Garden.NOTE_MS
        return when {
            st.viewing != null -> StatusKey.VIEWING
            noteLive && st.note == Note.SHELF_FULL -> StatusKey.SHELF_FULL
            noteLive && st.note == Note.TRUNK -> StatusKey.TRUNK
            st.selection != null -> StatusKey.PREVIEW
            st.guideStep == 1 -> StatusKey.GUIDE1
            st.guideStep == 2 -> StatusKey.GUIDE2
            st.guideStep == 3 -> StatusKey.GUIDE3
            st.weakNext -> StatusKey.WEAK
            st.weakNow -> StatusKey.RESTING
            st.nearWeak -> StatusKey.NEAR_WEAK
            st.crowded -> StatusKey.CROWDED
            st.fall != null && now - st.fall.startMs < 4000 -> StatusKey.AFTER_CUT
            st.score >= st.threshold && st.year < 2 -> StatusKey.NOT_YET
            else -> StatusKey.IDLE
        }
    }

    fun status(ctx: Context, st: GardenState, now: Long): String = ctx.getString(when (statusKey(st, now)) {
        StatusKey.VIEWING -> R.string.st_viewing
        StatusKey.SHELF_FULL -> R.string.st_shelf_full
        StatusKey.TRUNK -> R.string.st_trunk
        StatusKey.GUIDE1 -> R.string.guide_1
        StatusKey.GUIDE2 -> R.string.guide_2
        StatusKey.GUIDE3 -> R.string.guide_3
        StatusKey.PREVIEW -> R.string.st_preview
        StatusKey.WEAK -> R.string.st_weak
        StatusKey.RESTING -> R.string.st_resting
        StatusKey.NEAR_WEAK -> R.string.st_near_weak
        StatusKey.CROWDED -> R.string.st_crowded
        StatusKey.AFTER_CUT -> R.string.st_after_cut
        StatusKey.NOT_YET -> R.string.st_not_yet
        StatusKey.IDLE -> R.string.st_idle
    })

    /** The short line under the status (rest / bud knowledge, design tone.smallPrint*). */
    fun smallPrint(ctx: Context, st: GardenState, now: Long): String? = when (statusKey(st, now)) {
        StatusKey.WEAK, StatusKey.RESTING, StatusKey.NEAR_WEAK -> ctx.getString(R.string.small_weak)
        StatusKey.AFTER_CUT -> ctx.getString(R.string.small_bud)
        StatusKey.GUIDE3 -> ctx.getString(R.string.guide_3_small)
        else -> null
    }

    /** Season sign: only the short keys (<= 18 characters in English, design signStrings). */
    fun sign(ctx: Context, st: GardenState, now: Long): String = when (statusKey(st, now)) {
        StatusKey.VIEWING -> ctx.getString(R.string.sign_viewing)
        StatusKey.TRUNK -> ctx.getString(R.string.sign_trunk)
        StatusKey.PREVIEW -> ctx.getString(R.string.sign_preview)
        StatusKey.WEAK, StatusKey.RESTING -> ctx.getString(R.string.sign_weak)
        else -> if (st.done) name(ctx, st.name, prefix = true) else ctx.getString(R.string.sign_status, st.year, st.score)
    }

    fun zh(ctx: Context) = ctx.resources.configuration.locales[0].language == "zh"

    fun name(ctx: Context, code: Int, prefix: Boolean = false): String =
        if (zh(ctx)) (if (prefix) "成景 · " else "") + Names.zh(code) else Names.en(code)

    fun form(ctx: Context, f: Form) = ctx.getString(when (f) { Form.CHOKKAN -> R.string.form_chokkan; Form.SHAKAN -> R.string.form_shakan; Form.KENGAI -> R.string.form_kengai })

    fun season(ctx: Context, s: Season) = ctx.getString(when (s) { Season.SUMMER -> R.string.season_summer; Season.AUTUMN -> R.string.season_autumn; Season.WINTER -> R.string.season_winter; Season.SPRING -> R.string.season_spring })
}

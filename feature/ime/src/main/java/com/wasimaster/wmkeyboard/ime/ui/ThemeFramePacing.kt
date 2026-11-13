package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.MotionBudget
import kotlinx.coroutines.delay

/**
 * The frame rate a theme's moving parts are drawn at right now, set by the
 * service from the phone's load (see `LoadGovernor`) and read by the frame
 * loops each frame. A plain volatile rather than snapshot state: the loops
 * read it between frames, and a change of budget must not recompose the
 * board.
 *
 * The settings app shares the process and never sets it, so its previews and
 * editor run at [MotionBudget.NORMAL], the same caps the keyboard has when
 * the phone is idle.
 */
object ThemeFramePacing {
    @Volatile
    var budget: MotionBudget = MotionBudget.NORMAL
}

/**
 * Waits out the rest of a capped frame before the next frame of a board
 * effect is asked for; returns at once when there is no cap.
 */
internal suspend fun paceBoardEffectFrame() {
    val gap = ThemeFramePacing.budget.boardEffectGapMs
    if (gap > 0L) delay(gap)
}

/** The same for the theme's colour flow (the FLOW and HUE_CYCLE animations). */
internal suspend fun paceThemeFlowFrame() {
    val gap = ThemeFramePacing.budget.themeFlowGapMs
    if (gap > 0L) delay(gap)
}

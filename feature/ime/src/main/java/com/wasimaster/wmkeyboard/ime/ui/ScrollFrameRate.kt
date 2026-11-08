package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView

/**
 * How long the request outlives the last scrolled frame. Long enough to ride
 * over the pause between two flicks, so a quick run of them costs one window
 * update at each end rather than one per flick.
 */
private const val HOLD_MS = 600L

/**
 * Asks the display for its high refresh rate while anything in [content]
 * scrolls (#847): the toolbox, the gallery, the emoji row, a tool's list.
 *
 * With adaptive motion smoothness a Galaxy S25+ dropped the keyboard to 60 Hz
 * for every one of those scrolls while apps scrolled at 120. Two earlier fixes
 * asked through the view-voting system Android 15 added, and neither could
 * work. That system counts a view's vote only in a frame where that view is
 * redrawn. `Modifier.preferredFrameRate` (ec02d0bab) lasted one frame, and the
 * rate set on the Compose root view (447d44824) was never read at all: a
 * scroll moves the list's own layers and redraws nothing above them, so the
 * root that carried the vote sat still. Below Android 15 nothing was asked.
 *
 * So the request now goes three ways, any of which is enough on its own:
 *
 * - The keyboard's window asks for the display's fastest mode at its current
 *   resolution through [WindowManager.LayoutParams.preferredDisplayModeId].
 *   The window manager reads that from any visible window, and the keyboard's
 *   sits above the app's, so it is the first preference found. Android 6 and
 *   up, independent of view voting and of how the frame is drawn. A user's
 *   cap (60 Hz chosen in settings, battery saver) outranks it, as it should.
 * - On Android 15 and up, the window's power-saving balance is switched off
 *   for the scroll, which stops the system lowering the rate for a window
 *   whose changes look small, and the root view votes High and is redrawn
 *   every scrolled frame so the vote is counted.
 * - Touch boost is left on for the window, which is the default, but stated
 *   so nothing turns it off unseen.
 *
 * Everything is withdrawn [HOLD_MS] after the last scrolled frame, so a still
 * keyboard leaves the display to idle down and costs the battery nothing.
 * Not verified on a device with an adaptive refresh rate.
 */
@Composable
internal fun ScrollFrameRateVote(content: @Composable () -> Unit) {
    val view = LocalView.current
    val vote = remember(view) { ScrollFrameRateConnection(view, imeWindow(view.context)) }
    DisposableEffect(vote) {
        vote.start()
        onDispose { vote.stop() }
    }
    Box(Modifier.nestedScroll(vote), propagateMinConstraints = true) { content() }
}

/** The keyboard's own window, found through the service behind [context]. */
private fun imeWindow(context: Context): Window? {
    var current: Context? = context
    while (current != null) {
        if (current is InputMethodService) return current.window?.window
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Every Compose scroll passes through its ancestors once a frame, drag and fling
 * alike, so one connection at the root hears them all and none is consumed.
 */
private class ScrollFrameRateConnection(
    private val view: View,
    private val window: Window?,
) : NestedScrollConnection {
    private var holding = false

    private val withdraw = Runnable { release() }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window?.frameRateBoostOnTouchEnabled = true
        }
    }

    fun stop() {
        view.removeCallbacks(withdraw)
        release()
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!holding) {
            holding = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
            updateWindow(fast = true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // A view votes only in a frame it is redrawn in, and a scroll
            // redraws the list's layers, not this root.
            view.invalidate()
        }
        view.removeCallbacks(withdraw)
        view.postDelayed(withdraw, HOLD_MS)
        return Offset.Zero
    }

    private fun release() {
        if (!holding) return
        holding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
        updateWindow(fast = false)
    }

    /**
     * Sets or clears the window's own request in one attribute update, so a
     * scroll costs one relayout at its start and one at its end.
     */
    private fun updateWindow(fast: Boolean) {
        val window = window ?: return
        runCatching {
            // A copy, so the comparisons below are against what the window has.
            val attrs = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
            val modeId = if (fast) fastestMode(window)?.modeId ?: 0 else 0
            var changed = attrs.preferredDisplayModeId != modeId
            attrs.preferredDisplayModeId = modeId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                attrs.isFrameRatePowerSavingsBalanced == fast
            ) {
                attrs.setFrameRatePowerSavingsBalanced(!fast)
                changed = true
            }
            if (changed) window.attributes = attrs
        }
    }

    /**
     * The display's fastest mode at the resolution it is showing now, or null
     * on a display with one rate, which gets no window update at all. Asked
     * for even when it is already the current mode: an adaptive panel can sit
     * in its 120 Hz mode and still draw a window at 60.
     */
    private fun fastestMode(window: Window): Display.Mode? {
        val display = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.display
            } else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            ) ?: return null
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return null
        val supportsFaster = display.supportedModes.any { it.refreshRate < fastest.refreshRate - 1f }
        return fastest.takeIf { supportsFaster }
    }
}

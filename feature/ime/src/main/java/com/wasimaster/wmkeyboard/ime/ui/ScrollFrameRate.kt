package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView

/** How long a vote outlives the last scrolled frame, across a dropped frame or two. */
private const val HOLD_MS = 200L

/**
 * Asks the display for its high refresh rate while anything in [content]
 * scrolls (#847): the toolbox, the gallery, the emoji row, a tool's list.
 *
 * A phone with an adaptive refresh rate raises it for the app being touched,
 * and on a Galaxy S25+ the keyboard's window was not counted as one. The
 * request is the view's own, not `Modifier.preferredFrameRate`: Compose counts
 * that vote only for a frame in which the modifier is placed, and clears it
 * after the draw, while a scroll moves a layer below it without placing it
 * again, so it lasted one frame. Compose leaves a rate set directly on the view
 * alone as long as nothing votes one through it.
 *
 * Below Android 15 there is nothing to ask.
 */
@Composable
internal fun ScrollFrameRateVote(content: @Composable () -> Unit) {
    val view = LocalView.current
    val vote = remember(view) { ScrollFrameRateConnection(view) }
    Box(Modifier.nestedScroll(vote), propagateMinConstraints = true) { content() }
}

/**
 * Every Compose scroll passes through its ancestors once a frame, drag and fling
 * alike, so one connection at the root hears them all and none is consumed.
 */
private class ScrollFrameRateConnection(private val view: View) : NestedScrollConnection {
    private val withdraw = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            view.removeCallbacks(withdraw)
            view.postDelayed(withdraw, HOLD_MS)
        }
        return Offset.Zero
    }
}

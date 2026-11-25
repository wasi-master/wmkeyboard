package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowInsetsCompat

/**
 * The navigation bar's insets as the keyboard window last dispatched them to
 * the keyboard's frame, kept apart from Compose's own
 * `WindowInsets.navigationBars` (issues #463, #468).
 *
 * Compose's insets listener stopped taking new insets after the keyboard was
 * switched away and back, and the bottom row then sat on the gesture handle
 * and the system's keyboard buttons. Holding back the insets animations that
 * leave it waiting (see [StableMeasureFrame]) was not enough on every phone.
 * So the keyboard's frame records the insets the view hierarchy is handed,
 * which no listener state can get stuck in front of, and asks for a fresh
 * dispatch when it is attached or the window comes back on screen.
 *
 * Only the dispatched insets, the same ones a View with fitsSystemWindows
 * pads by (HeliBoard's keyboard view): the window's raw `rootWindowInsets`
 * count a bar that something above the keyboard has already padded for, and
 * reading those gave a row-high gap under the space bar (#569).
 *
 * Plain pixels, like the platform's own insets.
 */
@Stable
class ImeNavigationBars {
    var left by mutableIntStateOf(0)
        private set
    var top by mutableIntStateOf(0)
        private set
    var right by mutableIntStateOf(0)
        private set
    var bottom by mutableIntStateOf(0)
        private set

    /**
     * The bottom of the system's mandatory gesture zone: where a swipe up goes
     * home whatever the window under it wants. Usually no taller than the
     * navigation bar, but on phones that hide the gesture line the navigation
     * bar inset is zero while the zone is still there (Telegram 11891).
     */
    var gesturesBottom by mutableIntStateOf(0)
        private set

    /**
     * True while the system's keyboard bar (hide chevron and switch button) is
     * there to be drawn in this window but has been hidden at the keyboard's
     * request (#582, #681). The automatic bottom padding is sized to clear
     * that bar, so it drops back to the small one while this is set.
     */
    var keyboardBarHidden by mutableStateOf(false)
        private set

    /**
     * Takes the navigation bar out of [insets].
     *
     * From Android 16 the system draws its keyboard bar inside the keyboard's
     * window, over the bottom of the navigation bar inset, and reports it as
     * a caption bar. Hiding the caption bar takes the bar's buttons away, but
     * the navigation bar inset keeps the bar's whole height, so the keys used
     * to stay as high as before over an empty band (#681). While the caption
     * bar is hidden its height comes off the bottom, down to the strip the
     * home gesture still needs.
     */
    fun update(insets: android.view.WindowInsets, view: View) {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
        val bars = compat.getInsets(WindowInsetsCompat.Type.navigationBars())
        left = bars.left
        top = bars.top
        right = bars.right
        val captionBar = hiddenKeyboardBarHeight(insets)
        keyboardBarHidden = captionBar > 0
        val gestures = compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
        gesturesBottom = gestures
        bottom = if (captionBar > 0) {
            maxOf(bars.bottom - captionBar, gestures).coerceAtMost(bars.bottom)
        } else {
            bars.bottom
        }
    }

    /** The height of the system's keyboard bar while it is hidden, else 0. */
    private fun hiddenKeyboardBarHeight(insets: android.view.WindowInsets): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return 0
        val captionBar = android.view.WindowInsets.Type.captionBar()
        if (insets.isVisible(captionBar)) return 0
        return insets.getInsetsIgnoringVisibility(captionBar).bottom
    }
}

/** The keyboard window's [ImeNavigationBars]; null outside it (the settings app's previews). */
val LocalImeNavigationBars = staticCompositionLocalOf<ImeNavigationBars?> { null }

/**
 * The navigation bar insets to keep the keys clear of: the keyboard window's
 * own record where there is one, Compose's everywhere else.
 */
@Composable
fun navigationBarInsets(): WindowInsets {
    val bars = LocalImeNavigationBars.current ?: return WindowInsets.navigationBars
    val left = bars.left
    val top = bars.top
    val right = bars.right
    val bottom = bars.bottom
    return remember(left, top, right, bottom) { WindowInsets(left, top, right, bottom) }
}

/**
 * What the floating panel keeps clear of: the navigation bar, and at the
 * bottom the home gesture zone as well. A panel dragged to the bottom edge
 * put its handle bar in that zone on a phone with the gesture line hidden,
 * where the navigation bar inset is zero, and a drag on the handle then went
 * home instead of moving the panel, so it could never be moved again.
 */
@Composable
fun floatingPanelInsets(): WindowInsets {
    val bars = LocalImeNavigationBars.current ?: return WindowInsets.navigationBars
    val left = bars.left
    val top = bars.top
    val right = bars.right
    val bottom = maxOf(bars.bottom, bars.gesturesBottom)
    return remember(left, top, right, bottom) { WindowInsets(left, top, right, bottom) }
}

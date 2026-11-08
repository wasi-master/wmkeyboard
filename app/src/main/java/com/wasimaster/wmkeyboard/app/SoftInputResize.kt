package com.wasimaster.wmkeyboard.app

import android.os.Build
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/**
 * Tells the window not to pan for the keyboard while the calling screen is on
 * screen, for a screen that makes room for the keyboard itself with
 * `imePadding()` (a chat composer, a code editor, the keyboard test field).
 *
 * MainActivity leaves its soft input mode unset, which on a focused field means
 * the system pans the whole window up until the field clears the keyboard.
 * Every settings screen with a text field in a scrolling list relies on that.
 * A screen that also pads itself by the keyboard's inset gets both: the field
 * rises by the keyboard's height twice and ends up under the status bar with an
 * empty screen below it. While such a screen is shown the window is set to
 * adjustNothing (adjustResize before Android 11, which only reports the
 * keyboard's inset to a resizing window).
 *
 * Not adjustResize from 11 on: the window still pans under it, whenever the
 * keyboard's inset arrives before the screen has moved its field up. Android
 * checks where the focused field is at that moment, finds it behind the
 * keyboard and slides the window; a chat with a long transcript, whose frames
 * are slower, lost that race some of the time. adjustNothing never pans, and
 * from 11 the inset reaches the screen all the same.
 *
 * Counted per window, so two such screens overlapping during a transition hand
 * over cleanly, and the mode the window had before comes back once the last of
 * them is gone.
 */
@Composable
internal fun ResizeForKeyboard() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SoftInputResize.acquire(window)
        onDispose { SoftInputResize.release(window) }
    }
}

/**
 * Re-pans the window whenever the keyboard changes height, so a focused field
 * that was cleared once stays cleared (#679).
 *
 * The pan is ViewRootImpl's: on each draw it checks whether the focused view is
 * behind the keyboard, and for Compose that view is the whole AndroidComposeView,
 * whose getFocusedRect reports the focused node (the text field). But it only
 * re-checks after a layout pass; a draw with no layout keeps the old offset.
 * A field gets its first pan because focusing it lays out for a while (cursor,
 * label, ripple) while the keyboard comes up. Once that settles, a keyboard that
 * grows afterwards, a suggestion strip or toolbar row arriving with the field's
 * input, or one that came up late, changes the window's visible insets without
 * any layout, and the field stays where the old pan left it, under the keys.
 *
 * Asking the root view for a layout on every change of the keyboard's inset
 * makes ViewRootImpl look again. Nothing in Compose is dirty, so the pass is a
 * no-op apart from that. Harmless under [ResizeForKeyboard]: with adjustNothing
 * there are no visible insets to pan for.
 */
@Composable
internal fun RepanOnKeyboardResize() {
    val view = LocalView.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(view, density, ime) {
        snapshotFlow { ime.getBottom(density) }.collect { view.requestLayout() }
    }
}

/**
 * Back with the keyboard up puts the keyboard away and does nothing else
 * (Telegram, Priyanshu Mishra): the screen under it stays as it was, the way
 * Back behaves in any app that leaves it to the system.
 *
 * From Android 13 that order is a matter of who registered last. The keyboard's
 * own back callback is forwarded into this window when it comes up, at the same
 * priority as the screens' BackHandlers, and the newest of those wins. A
 * handler that switches on after the keyboard is already up (the theme editor's
 * docked preview, picking, a part's sheet, a search) went in on top of it, and
 * Back closed the screen's layer, or the screen, with the keyboard still open.
 * An overlay-priority callback, held only while the keyboard is showing, sits
 * above all of them. Before 13 Back reaches the keyboard first anyway.
 */
@Composable
internal fun ImeBackFirst() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val dispatcher = activity.onBackInvokedDispatcher
        val callback = OnBackInvokedCallback {
            WindowCompat.getInsetsController(activity.window, view).hide(WindowInsetsCompat.Type.ime())
        }
        var registered = false
        // Every layout, which is cheap to look at: the keyboard coming or going
        // changes the window's insets, and that lays the window out again.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val up = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (up && !registered) {
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
                registered = true
            } else if (!up && registered) {
                dispatcher.unregisterOnBackInvokedCallback(callback)
                registered = false
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            if (registered) dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }
}

private object SoftInputResize {
    private class Held(val before: Int, var count: Int)

    private val held = WeakHashMap<Window, Held>()

    fun acquire(window: Window) {
        val entry = held[window]
        if (entry != null) {
            entry.count++
            return
        }
        val before = window.attributes.softInputMode
        held[window] = Held(before, 1)
        val adjust = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        window.setSoftInputMode((before and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjust)
    }

    fun release(window: Window) {
        val entry = held[window] ?: return
        if (--entry.count > 0) return
        held.remove(window)
        window.setSoftInputMode(entry.before)
    }
}

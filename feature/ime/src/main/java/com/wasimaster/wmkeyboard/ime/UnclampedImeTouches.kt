package com.wasimaster.wmkeyboard.ime

import android.view.MotionEvent
import android.view.Window

/**
 * Lets a finger that leaves the keyboard's window keep moving what it holds
 * (#1362): a list in the clipboard, the emoji or the toolbox scrolled up past
 * the keyboard's top edge stopped there, where an app's list keeps going.
 *
 * The keyboard's window is a dialog of the platform's (`SoftInputWindow`),
 * and up to Android 12 that dialog clamped every touch to the window's own
 * box before passing it on, so a finger above the window was reported at its
 * top edge and the scroll under it stood still. The same clamp is still in
 * the platform's `VoiceInteractionWindow`, which was copied from it. Android
 * 13 and later pass such a touch on as it is.
 *
 * The window's own dispatch is what that clamp sits in front of, so a touch
 * outside the window goes straight to it. A touch inside goes through the
 * dialog as before, which keeps every newer platform's handling untouched.
 */
internal class UnclampedImeTouches private constructor(
    private val base: Window.Callback,
    private val window: Window,
) : Window.Callback by base {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val decor = window.peekDecorView() ?: return base.dispatchTouchEvent(event)
        if (inside(event, decor.width, decor.height)) return base.dispatchTouchEvent(event)
        return window.superDispatchTouchEvent(event)
    }

    companion object {
        /** Puts the unclamped dispatch in front of [window]'s callback, once. */
        fun install(window: Window) {
            val current = window.callback ?: return
            if (current is UnclampedImeTouches) return
            window.callback = UnclampedImeTouches(current, window)
        }

        /** Whether every pointer of [event] is inside a window [width] by [height] px. */
        internal fun inside(event: MotionEvent, width: Int, height: Int): Boolean {
            for (i in 0 until event.pointerCount) {
                val x = event.getX(i)
                val y = event.getY(i)
                if (x < 0f || y < 0f || x >= width || y >= height) return false
            }
            return true
        }
    }
}

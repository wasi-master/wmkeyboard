package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * The Trackpad tool's mouse mode (#1411) on a phone without Shizuku: the
 * keyboard draws its own pointer, and this service touches the screen where
 * that pointer is when the user clicks, scrolls or drags on the trackpad.
 *
 * Android lets another app touch the screen for the user only through an
 * accessibility service, as `dispatchGesture`. There is no other route short
 * of Shizuku or root, which the keyboard prefers when the user has set them up.
 *
 * Its one power, and nothing else:
 *
 *  * it performs a tap, a long press, a swipe or a drag at the pointer, and
 *    only while the user is doing that on the trackpad;
 *  * it reads nothing: no window content, no window list, and its events are
 *    ignored (it subscribes to none);
 *  * it keeps nothing.
 *
 * Separate from [SystemButtonsService] and the other services on purpose:
 * pressing Back for the floating button must not also let the keyboard touch
 * the screen, nor the other way round.
 */
class TouchMouseService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        stroke = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stroke = null
    }

    /** The stroke a held drag continues, API 26 and later; null when none is under way. */
    private var stroke: GestureDescription.StrokeDescription? = null
    private var strokeX = 0f
    private var strokeY = 0f

    /** One tap at ([x], [y]) in screen pixels, or two for a double click. */
    fun tap(x: Float, y: Float, times: Int = 1): Boolean {
        val builder = GestureDescription.Builder()
        var start = 0L
        repeat(times.coerceIn(1, 2)) {
            builder.addStroke(GestureDescription.StrokeDescription(point(x, y), start, TAP_MS))
            start += TAP_MS + DOUBLE_GAP_MS
        }
        return dispatch(builder.build())
    }

    /** A long press at ([x], [y]): the touch screen's right click. */
    fun longPress(x: Float, y: Float, durationMs: Long): Boolean =
        dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(point(x, y), 0, durationMs)).build())

    /**
     * One straight swipe from ([x], [y]) by ([dx], [dy]) over [durationMs]:
     * a scroll on Android 7, where a stroke cannot be continued, and the
     * whole of a drag there too.
     */
    fun swipe(x: Float, y: Float, dx: Float, dy: Float, durationMs: Long, holdFirstMs: Long = 0): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx, y + dy)
        }
        val builder = GestureDescription.Builder()
        if (holdFirstMs > 0) {
            // A long press first, then the move, as one stroke: a held still
            // finger, then travel. Launchers lift an icon only after the hold.
            val held = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
                lineTo(x + dx, y + dy)
            }
            builder.addStroke(GestureDescription.StrokeDescription(held, 0, holdFirstMs + durationMs))
        } else {
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
        }
        return dispatch(builder.build())
    }

    /**
     * Puts a finger down at ([x], [y]) for [durationMs], and keeps it down
     * after for [continueTo]. The next piece must go out once this one has
     * run its time, or the system cancels the stroke.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun pressDown(x: Float, y: Float, durationMs: Long = SEGMENT_MS): Boolean {
        val first = GestureDescription.StrokeDescription(point(x, y), 0, durationMs.coerceAtLeast(1), true)
        stroke = first
        strokeX = x
        strokeY = y
        return dispatch(GestureDescription.Builder().addStroke(first).build())
    }

    /**
     * Moves the held finger to ([x], [y]) over [durationMs]; with [last] it
     * lifts there. False when nothing is held. A piece of no travel still
     * goes out, so a finger held still stays down.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun continueTo(x: Float, y: Float, durationMs: Long = SEGMENT_MS, last: Boolean = false): Boolean {
        val held = stroke ?: return false
        val path = Path().apply {
            moveTo(strokeX, strokeY)
            lineTo(x, y)
        }
        val next = held.continueStroke(path, 0, durationMs.coerceAtLeast(1), !last)
        stroke = if (last) null else next
        strokeX = x
        strokeY = y
        return dispatch(GestureDescription.Builder().addStroke(next).build())
    }

    /** Whether a finger is held down by [pressDown]. */
    val holding: Boolean get() = stroke != null

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun dispatch(gesture: GestureDescription): Boolean =
        runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)

    companion object {
        private const val TAP_MS = 40L
        private const val DOUBLE_GAP_MS = 60L

        /** How long each piece of a continued stroke lasts; short, so the finger follows the trackpad closely. */
        const val SEGMENT_MS = 16L

        /** The connected service, or null while it is off. */
        @Volatile
        var current: TouchMouseService? = null
            private set

        /** Whether a held stroke can be continued: Android 8 and later. */
        @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
        val canContinue: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        /**
         * Whether the user has turned the service on in Settings › Accessibility.
         * Read from the secure setting rather than [current], which is only set
         * once the system has bound it.
         */
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, TouchMouseService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}

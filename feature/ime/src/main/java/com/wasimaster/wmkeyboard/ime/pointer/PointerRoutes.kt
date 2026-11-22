package com.wasimaster.wmkeyboard.ime.pointer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.wasimaster.wmkeyboard.core.accessibility.TouchMouse
import com.wasimaster.wmkeyboard.core.accessibility.TouchMouseService
import com.wasimaster.wmkeyboard.core.shell.ShellAccess
import kotlin.math.hypot

/**
 * How a click of the Trackpad tool's mouse mode (#1411) reaches the app under
 * the pointer. Every coordinate is in screen pixels.
 */
internal interface PointerRoute {
    /** The pointer moved to ([x], [y]) with no button held. */
    fun hover(x: Float, y: Float) = Unit

    fun click(x: Float, y: Float, times: Int)

    fun rightClick(x: Float, y: Float)

    /** The button went down at ([x], [y]) for a drag. */
    fun press(x: Float, y: Float)

    /** The held button moved to ([x], [y]). */
    fun dragTo(x: Float, y: Float)

    /** The held button came up at ([x], [y]). */
    fun release(x: Float, y: Float)

    /** Scroll at ([x], [y]) by the fingers' travel ([dx], [dy]), already scaled and signed. */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float)

    /** The two-finger drag that scrolled is over. */
    fun scrollEnd(x: Float, y: Float) = Unit

    /** Lets go of anything held, the mode or the keyboard going away. */
    fun cancel() = Unit
}

/**
 * Shizuku's (or Sui's) route: real mouse events from the shell process, so
 * the app sees a mouse. A secondary button opens a context menu, a scroll is
 * a wheel, a move is a hover.
 *
 * [notchPx] is how much finger travel makes one wheel notch; the wheel is fed
 * fractions, which every scrolling view Android ships reads smoothly.
 */
internal class ShellRoute(private val displayId: () -> Int, private val notchPx: () -> Float) : PointerRoute {

    private var downTime = 0L

    private fun send(action: Int, x: Float, y: Float, buttons: Int, actionButton: Int = 0, h: Float = 0f, v: Float = 0f, down: Long = 0L) {
        ShellAccess.injectMouseNow(action, x, y, buttons, actionButton, h, v, down, displayId())
    }

    override fun hover(x: Float, y: Float) = send(MotionEvent.ACTION_HOVER_MOVE, x, y, 0)

    override fun click(x: Float, y: Float, times: Int) {
        repeat(times.coerceIn(1, 2)) { button(x, y, MotionEvent.BUTTON_PRIMARY) }
    }

    override fun rightClick(x: Float, y: Float) = button(x, y, MotionEvent.BUTTON_SECONDARY)

    private fun button(x: Float, y: Float, button: Int) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, button, down = down)
        send(MotionEvent.ACTION_BUTTON_PRESS, x, y, button, button, down = down)
        send(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, button, down = down)
        send(MotionEvent.ACTION_UP, x, y, 0, down = down)
        hover(x, y)
    }

    override fun press(x: Float, y: Float) {
        downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, down = downTime)
        send(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, down = downTime)
    }

    override fun dragTo(x: Float, y: Float) {
        if (downTime == 0L) return
        send(MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, down = downTime)
    }

    override fun release(x: Float, y: Float) {
        if (downTime == 0L) return
        send(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, MotionEvent.BUTTON_PRIMARY, down = downTime)
        send(MotionEvent.ACTION_UP, x, y, 0, down = downTime)
        downTime = 0L
        hover(x, y)
    }

    override fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        val notch = notchPx().coerceAtLeast(1f)
        // A finger on glass drags the page with it: travel down shows what is
        // above, which a wheel calls scrolling up, a positive AXIS_VSCROLL.
        // Sideways the wheel counts the other way: positive scrolls right.
        send(MotionEvent.ACTION_SCROLL, x, y, 0, h = -dx / notch, v = dy / notch)
    }

    override fun cancel() {
        downTime = 0L
    }
}

/**
 * The accessibility route ([TouchMouseService]): the app sees a finger. A
 * click is a tap, a right click a long press, a scroll a slow swipe that
 * stops before it lifts so the page does not fling, and a drag a held touch
 * that starts with a long press, which launchers and lists wait for before
 * they lift an item.
 *
 * On Android 8 and later a held touch is one stroke continued in short pieces
 * while the fingers move. A piece goes out only once the one before it has run
 * its time ([busyUntil]): a gesture dispatched over a running one cancels it.
 * Android 7 cannot continue a stroke, so there the whole drag or scroll goes
 * out as one swipe when the fingers lift.
 */
internal class GestureRoute(private val band: () -> android.graphics.RectF) : PointerRoute {

    private val handler = Handler(Looper.getMainLooper())
    private val service: TouchMouse? get() = TouchMouseService.current

    /** Where the held touch is, where it was asked to go next, and where it started. */
    private var heldX = 0f
    private var heldY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var startX = 0f
    private var startY = 0f
    private var holding = false
    private var scrolling = false

    /** Uptime at which the piece of the stroke now running ends. */
    private var busyUntil = 0L
    private var liftWanted = false
    private var flushQueued = false

    private val flush = Runnable {
        flushQueued = false
        pump()
    }

    override fun click(x: Float, y: Float, times: Int) {
        if (holding) return
        service?.tap(x, y, times)
    }

    override fun rightClick(x: Float, y: Float) {
        if (holding) return
        service?.longPress(x, y, longPressMs())
    }

    override fun press(x: Float, y: Float) = begin(x, y, holdMs = longPressMs())

    override fun dragTo(x: Float, y: Float) {
        if (!holding || scrolling) return
        moveTarget(x, y)
    }

    override fun release(x: Float, y: Float) {
        if (!holding || scrolling) return
        if (TouchMouseService.canContinue) {
            moveTarget(x, y)
            lift()
        } else {
            // Android 7: hold, then travel, as one swipe now that it is over.
            val dx = x - startX
            val dy = y - startY
            service?.swipe(startX, startY, dx, dy, swipeMs(dx, dy), holdFirstMs = longPressMs())
            holding = false
        }
    }

    override fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (holding && !scrolling) return
        if (!scrolling) {
            scrolling = true
            begin(x, y, holdMs = TouchMouseService.SEGMENT_MS)
        }
        val area = band()
        val nx = targetX + dx
        val ny = targetY + dy
        if (TouchMouseService.canContinue && !area.contains(nx, ny) && !liftWanted) {
            // The virtual finger ran off the band: lift it and put it back
            // down at the pointer, the way a thumb takes a second stroke.
            lift()
            pendingRestart = Restart(x, y, dx, dy)
            return
        }
        moveTarget(nx.coerceIn(area.left, area.right - 1), ny.coerceIn(area.top, area.bottom - 1))
    }

    /** A second scroll stroke waiting for the first to lift. */
    private class Restart(val x: Float, val y: Float, val dx: Float, val dy: Float)

    private var pendingRestart: Restart? = null

    override fun scrollEnd(x: Float, y: Float) {
        if (!scrolling) return
        pendingRestart = null
        if (TouchMouseService.canContinue) {
            lift()
        } else {
            val dx = targetX - startX
            val dy = targetY - startY
            if (dx != 0f || dy != 0f) service?.swipe(startX, startY, dx, dy, swipeMs(dx, dy))
            holding = false
        }
        scrolling = false
    }

    override fun cancel() {
        handler.removeCallbacks(flush)
        flushQueued = false
        pendingRestart = null
        if (holding && TouchMouseService.canContinue) {
            // Lift where it is, straight away: nothing waits for this one.
            service?.continueTo(heldX, heldY, last = true)
        }
        holding = false
        scrolling = false
        liftWanted = false
    }

    private fun begin(x: Float, y: Float, holdMs: Long) {
        startX = x
        startY = y
        heldX = x
        heldY = y
        targetX = x
        targetY = y
        holding = true
        liftWanted = false
        if (TouchMouseService.canContinue) {
            service?.pressDown(x, y, holdMs)
            busyUntil = SystemClock.uptimeMillis() + holdMs
        }
    }

    private fun moveTarget(x: Float, y: Float) {
        targetX = x
        targetY = y
        if (TouchMouseService.canContinue) pump()
    }

    private fun lift() {
        liftWanted = true
        pump()
    }

    /**
     * Sends the next piece if the last one has run out: the travel to the
     * target, or, once a lift is wanted and the finger is where it should be,
     * a still moment and the lift, so nothing flings.
     */
    private fun pump() {
        if (!holding) return
        val wait = busyUntil - SystemClock.uptimeMillis()
        if (wait > 0) {
            if (!flushQueued) {
                flushQueued = true
                handler.postDelayed(flush, wait)
            }
            return
        }
        val svc = service
        if (svc == null) {
            holding = false
            return
        }
        val now = SystemClock.uptimeMillis()
        if (targetX != heldX || targetY != heldY) {
            svc.continueTo(targetX, targetY)
            heldX = targetX
            heldY = targetY
            busyUntil = now + TouchMouseService.SEGMENT_MS
            if (liftWanted) schedule(TouchMouseService.SEGMENT_MS)
            return
        }
        if (liftWanted) {
            svc.continueTo(heldX, heldY, durationMs = STILL_MS, last = true)
            holding = false
            liftWanted = false
            busyUntil = now + STILL_MS
            pendingRestart?.let { restart ->
                pendingRestart = null
                handler.postDelayed({
                    if (scrolling) {
                        begin(restart.x, restart.y, holdMs = TouchMouseService.SEGMENT_MS)
                        scroll(restart.x, restart.y, restart.dx, restart.dy)
                    }
                }, STILL_MS)
            }
        }
    }

    private fun schedule(delayMs: Long) {
        if (flushQueued) return
        flushQueued = true
        handler.postDelayed(flush, delayMs)
    }

    private fun longPressMs(): Long = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS

    private fun swipeMs(dx: Float, dy: Float): Long =
        (hypot(dx, dy) / SLOW_PX_PER_MS).toLong().coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)

    companion object {
        private const val LONG_PRESS_MARGIN_MS = 100L
        /** How long the finger rests before it lifts: past what a fling reads as motion. */
        private const val STILL_MS = 80L
        private const val SLOW_PX_PER_MS = 1.5f
        private const val MIN_SWIPE_MS = 120L
        private const val MAX_SWIPE_MS = 1_500L
    }
}

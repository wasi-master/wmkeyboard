package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Moves touches on the right half of a split bar row back to where the row
 * laid out what is drawn there (#1256).
 *
 * With the see-through gap on, a bar row over a split board is laid out the
 * gap's width narrower and its right half drawn that much further right (see
 * [rememberSplitBarHalves]). Compose finds what a finger is on from the layout, so a
 * tap on a word drawn right of the gap would land on whatever the layout has
 * there instead. This frame sits between the keyboard's view and its outer
 * frame and moves each such pointer by [SplitBarShift.dx] before Compose sees
 * it.
 *
 * A pointer is moved, or not, by where it went down, for the whole of its
 * gesture: a drag that starts on the right half and crosses to the left stays
 * in the row's own space throughout, and a finger typing on the keys while
 * another holds a word is left where it is. The outer frame still reads the
 * raw touch, so a swipe in from the screen's right edge is still Back there.
 */
internal class SplitBarTouchFrame(context: Context) : FrameLayout(context) {

    /** The right halves of the split bar rows, in window pixels; empty when nothing is split. */
    var shifts: List<SplitBarShift> = emptyList()

    /** The pointers moved in the gesture under way, by id, and how far each is moved. */
    private val moved = HashMap<Int, Float>()

    private val location = IntArray(2)

    /** How far a touch at this frame's [x], [y] moves, or 0 for one outside every split half. */
    private fun shiftAt(x: Float, y: Float): Float {
        if (shifts.isEmpty()) return 0f
        getLocationInWindow(location)
        val wx = (x + location[0]).toInt()
        val wy = (y + location[1]).toInt()
        val shift = shifts.firstOrNull { wx >= it.area.left && wx < it.area.right && wy >= it.area.top && wy < it.area.bottom }
        return shift?.dx?.toFloat() ?: 0f
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moved.clear()
                val dx = shiftAt(ev.x, ev.y)
                if (dx != 0f) moved[ev.getPointerId(0)] = dx
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = ev.actionIndex
                val dx = shiftAt(ev.getX(index), ev.getY(index))
                if (dx != 0f) moved[ev.getPointerId(index)] = dx
            }
        }
        val handled = if (moved.isEmpty()) super.dispatchTouchEvent(ev) else dispatchMoved(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> moved.clear()
            MotionEvent.ACTION_POINTER_UP -> moved.remove(ev.getPointerId(ev.actionIndex))
        }
        return handled
    }

    private fun dispatchMoved(ev: MotionEvent): Boolean {
        val count = ev.pointerCount
        val shared = moved[ev.getPointerId(0)]
        val allSame = shared != null && (0 until count).all { moved[ev.getPointerId(it)] == shared }
        // One move for every pointer, which is the usual case of a single
        // finger: offset a copy, keeping the history velocity tracking reads.
        val copy = if (allSame) {
            MotionEvent.obtain(ev).apply { offsetLocation(shared!!, 0f) }
        } else {
            val properties = Array(count) { MotionEvent.PointerProperties().also { p -> ev.getPointerProperties(it, p) } }
            val coords = Array(count) { index ->
                MotionEvent.PointerCoords().also { c ->
                    ev.getPointerCoords(index, c)
                    c.x += moved[ev.getPointerId(index)] ?: 0f
                }
            }
            MotionEvent.obtain(
                ev.downTime, ev.eventTime, ev.action, count, properties, coords,
                ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision,
                ev.deviceId, ev.edgeFlags, ev.source, ev.flags,
            )
        }
        return try {
            super.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    // Explore by touch: TalkBack finds what is under the finger the same way.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val dx = shiftAt(event.x, event.y)
        if (dx == 0f) return super.dispatchHoverEvent(event)
        val copy = MotionEvent.obtain(event).apply { offsetLocation(dx, 0f) }
        return try {
            super.dispatchHoverEvent(copy)
        } finally {
            copy.recycle()
        }
    }
}

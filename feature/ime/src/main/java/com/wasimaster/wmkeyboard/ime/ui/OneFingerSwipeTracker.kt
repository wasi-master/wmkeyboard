package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import com.wasimaster.wmkeyboard.core.layout.hasFlicks
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides whether one finger on the letters is a board swipe (gap list item
 * 44), one pointer event at a time. Pure, so the rules are tested on the JVM;
 * the grid's pointer loop feeds it and acts on [claimed]. Only ever run while
 * glide typing is off: with glide on, the same movement is a word.
 *
 * With glide off a finger that slides is still mostly typing: it rolled off
 * its key, or it is holding for the alternates. So the swipe is claimed only
 * when it can be nothing else:
 *
 *  - one finger, alone: a second finger landing first makes it typing, or a
 *    multi-finger swipe, and the touch is given up;
 *  - it travels [claimPx] from where it landed, which is well past its own
 *    key, within [windowMs] of landing, so a press and hold never becomes
 *    one however far it later drifts;
 *  - it heads along one axis, within the same cone the multi-finger swipe
 *    uses; a diagonal that has gone that far is no direction and is given up.
 *
 * Until the claim nothing is consumed, so the key under the finger types as
 * it always did.
 */
internal class OneFingerSwipeTracker(
    private val claimPx: Float,
    private val windowMs: Long,
) {
    private var id: Long? = null
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lifted = false

    /** The finger is a swipe; from here every change belongs to it. */
    var claimed = false
        private set

    /** This touch can no longer become a swipe. */
    var abandoned = false
        private set

    /** Something else took the touch: a popup, another gesture. */
    fun abandon() {
        if (!claimed) abandoned = true
    }

    /**
     * One pointer's state in the latest event. Returns true when this call is
     * the one that claimed the swipe.
     */
    fun track(pointer: Long, x: Float, y: Float, pressed: Boolean, uptimeMs: Long): Boolean {
        val first = id
        if (first == null) {
            if (!pressed) return false
            id = pointer
            startX = x
            startY = y
            lastX = x
            lastY = y
            downAt = uptimeMs
            return false
        }
        if (pointer != first) {
            // A second finger: typing, or another gesture's, never this one.
            if (pressed && !claimed) abandoned = true
            return false
        }
        if (!pressed) {
            lifted = true
            if (!claimed) abandoned = true
            return false
        }
        lastX = x
        lastY = y
        if (claimed || abandoned) return false
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy < claimPx * claimPx) {
            // Still near its key. Past the window it is a hold, not a swipe.
            if (uptimeMs - downAt > windowMs) abandoned = true
            return false
        }
        if (uptimeMs - downAt > windowMs || axisOf(dx, dy) == null) {
            abandoned = true
            return false
        }
        claimed = true
        return true
    }

    /** The swipe's direction once the finger is up, or null unless it was claimed. */
    fun result(): SwipeDirection? {
        if (!claimed || !lifted) return null
        return axisOf(lastX - startX, lastY - startY, cone = false)
    }

    /** How far the finger has gone from where it landed. */
    fun travel(): Float {
        val dx = lastX - startX
        val dy = lastY - startY
        return sqrt(dx * dx + dy * dy)
    }

    private fun axisOf(dx: Float, dy: Float, cone: Boolean = true): SwipeDirection? {
        if (dx == 0f && dy == 0f) return null
        val major = maxOf(abs(dx), abs(dy))
        val minor = minOf(abs(dx), abs(dy))
        if (cone && minor > major * FingerSwipeTracker.AXIS_CONE_TAN) return null
        return when {
            abs(dx) >= abs(dy) -> if (dx > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
            else -> if (dy > 0f) SwipeDirection.DOWN else SwipeDirection.UP
        }
    }
}

/**
 * Whether a one-finger board swipe may start on [key]: a key that types a
 * letter, and has no flick arms of its own, which already own a swipe off it.
 */
internal fun startsOneFingerSwipe(key: Key): Boolean {
    if (key.action != KeyAction.Text || key.hasFlicks()) return false
    val text = key.output ?: key.label
    return text.isNotEmpty() && Character.isLetter(text.codePointAt(0))
}

/** How far the finger has to travel before a one-finger swipe is claimed, in dp. */
internal const val OneFingerSwipeClaimDp = 64f

/** How soon after landing it has to get there, in milliseconds. */
internal const val OneFingerSwipeWindowMs = 350L

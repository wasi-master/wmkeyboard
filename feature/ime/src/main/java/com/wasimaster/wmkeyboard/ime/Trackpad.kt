package com.wasimaster.wmkeyboard.ime

import kotlin.math.abs

/**
 * The arithmetic behind the trackpad tool (issue #39), pulled out of the
 * surface's pointer handler so it can be checked without a keyboard.
 *
 * Two pieces. [TrackpadAxis] turns finger travel along one axis into whole
 * caret steps and keeps the change, so a slow drag that never covers a full
 * step in one frame still adds up to a move. [TrackpadTapCounter] reads one,
 * two and three taps off the surface by their spacing, the ladder the
 * Selection mode tool already uses, with a distance test added because a
 * surface is wide and a second tap across the board is a new tap.
 */

/**
 * Finger travel to caret steps on one axis, at [stepPx] of travel per step.
 *
 * The remainder carries between calls in both directions: a drag right of
 * half a step followed by a drag left of half a step moves nothing, which is
 * what relative tracking means. [reset] at the start of every gesture, so the
 * previous drag's leftover does not fire the first frame of the next.
 */
class TrackpadAxis(stepPx: Float) {

    /** Never below one pixel: a zero step would turn a twitch into an endless move. */
    private val stepPx = if (stepPx >= 1f) stepPx else 1f
    private var carry = 0f

    /**
     * Feeds [deltaPx] more travel in and returns the whole steps it adds up to,
     * signed with the direction. Steps truncate toward zero, so the caret never
     * moves further than the finger has come.
     */
    fun advance(deltaPx: Float): Int {
        carry += deltaPx
        val steps = (carry / stepPx).toInt()
        carry -= steps * stepPx
        return steps
    }

    /** Forgets the fraction of a step carried so far. */
    fun reset() {
        carry = 0f
    }
}

/**
 * Counts taps on the trackpad surface into a run of one, two or three.
 *
 * A tap joins the run when it lands within [windowMs] of the last one and
 * within [slopPx] of where it landed. The run stops at three and a fourth
 * quick tap starts over, the same as [SelectionTapCounter]. Anything that is
 * not a tap, a drag or a hold, calls [reset] so the tap after it reads as a
 * first.
 */
class TrackpadTapCounter(private val windowMs: Long, private val slopPx: Float) {

    private var lastTapMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var runLength = 0

    /** The run this tap makes: 1 for a lone tap, 2 for a double, 3 for a triple. */
    fun tap(nowMs: Long, x: Float, y: Float): Int {
        val near = abs(x - lastX) <= slopPx && abs(y - lastY) <= slopPx
        runLength = if (nowMs - lastTapMs < windowMs && near && runLength in 1..2) runLength + 1 else 1
        lastTapMs = nowMs
        lastX = x
        lastY = y
        return runLength
    }

    /** Forgets the run so far. */
    fun reset() {
        lastTapMs = 0L
        runLength = 0
    }
}

/**
 * A gesture on the trackpad surface, told apart once every finger has lifted.
 * The moves themselves fire while the fingers are down; this is what the
 * release means, if anything.
 */
enum class TrackpadRelease {
    /** Nothing more: the drag (or the hold) already did its work. */
    NONE,

    /** A tap that made no move: the tap ladder gets it. */
    TAP,

    /** Two fingers down and up together, with no travel: a space. */
    TWO_FINGER_TAP,
}

/**
 * What the release of a gesture means, from what happened while it was down.
 *
 * [moved] is whether the fingers travelled past the touch slop, [longPressed]
 * whether the hold timer fired, [maxFingers] the most fingers down at once. A
 * hold is never a tap, even one that never moved: it armed selection and the
 * release ends that. Three or more fingers count as two: the surface knows
 * two gestures, and a third finger is a palm.
 */
fun classifyRelease(moved: Boolean, longPressed: Boolean, maxFingers: Int): TrackpadRelease = when {
    moved || longPressed -> TrackpadRelease.NONE
    maxFingers >= 2 -> TrackpadRelease.TWO_FINGER_TAP
    else -> TrackpadRelease.TAP
}

/**
 * Finger travel to pointer travel for the Trackpad tool's mouse mode (#1411).
 *
 * [speed] is the base gain (1 = the pointer moves as far as the finger).
 * With [acceleration], a fast flick earns more: the gain climbs with the
 * finger's speed above [ACCEL_FLOOR_DP_PER_MS], up to [MAX_BOOST] times, the
 * curve laptop pads use so one swipe crosses the screen while a slow drag
 * still lands on a small button. Speeds are in dp per millisecond, so the feel
 * is the same on every screen density.
 */
class PointerMotion(private val speed: Float, private val acceleration: Boolean, private val density: Float) {

    /** The gain for a move of ([dx], [dy]) pixels over [dtMs]. */
    fun gain(dx: Float, dy: Float, dtMs: Long): Float {
        if (!acceleration || dtMs <= 0) return speed
        val dpPerMs = kotlin.math.hypot(dx, dy) / density / dtMs
        val over = (dpPerMs - ACCEL_FLOOR_DP_PER_MS).coerceAtLeast(0f)
        return speed * (1f + (over * ACCEL_SLOPE).coerceAtMost(MAX_BOOST - 1f))
    }

    companion object {
        /** Below this a drag is careful aiming and moves one for one with [speed]. */
        const val ACCEL_FLOOR_DP_PER_MS = 0.25f
        const val ACCEL_SLOPE = 1.6f
        const val MAX_BOOST = 4f
    }
}

/**
 * A gesture on the trackpad surface in mouse mode, read once every finger has
 * lifted: what the release clicks, if anything. The drag, the scroll and the
 * held button already did their work while the fingers were down.
 */
enum class MouseRelease { NONE, CLICK, RIGHT_CLICK }

/**
 * What a release means in mouse mode. A still tap clicks; two fingers down
 * and up together with no travel right-click, as on a laptop's pad. A hold is
 * a held button and its release is the button coming up, not a click.
 */
fun classifyMouseRelease(moved: Boolean, longPressed: Boolean, maxFingers: Int): MouseRelease =
    when (classifyRelease(moved, longPressed, maxFingers)) {
        TrackpadRelease.NONE -> MouseRelease.NONE
        TrackpadRelease.TAP -> MouseRelease.CLICK
        TrackpadRelease.TWO_FINGER_TAP -> MouseRelease.RIGHT_CLICK
    }

/**
 * Keeps a point inside a band: the pointer's tip never leaves the strip of
 * screen above the keyboard, where a click reaches the app.
 */
fun clampToBand(value: Float, min: Float, max: Float): Float =
    if (max <= min) min else value.coerceIn(min, max - 1f)

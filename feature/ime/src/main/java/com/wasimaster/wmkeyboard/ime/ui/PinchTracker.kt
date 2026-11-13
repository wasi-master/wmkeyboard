package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.PinchGesture
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where one finger landed and where it is now, in grid pixels.
 */
internal data class PinchStroke(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val dx: Float get() = x1 - x0
    val dy: Float get() = y1 - y0
    val travel: Float get() = sqrt(dx * dx + dy * dy)
}

/**
 * Whether two fingers' strokes so far are a pinch, a stretch, or neither yet
 * (gap list item 18). Pure, so the line between a pinch and the things it must
 * never be taken for is tested on the JVM.
 *
 * Each rule shuts out one of those things:
 *
 * * The gap between the fingers changes by at least [spanPx] **and** by a
 *   factor of [MIN_SCALE] (or its inverse). A wobble of two thumbs resting on
 *   the keys moves the gap by a few pixels, and a far-apart pair that drifts
 *   still has to change scale by a fifth.
 * * Each finger travels at least [fingerPx]. A thumb holding a key still while
 *   the other finger glides or flicks changes the gap too, and that is one
 *   finger typing, not two pinching.
 * * Each finger heads along the line between them, toward the other for a
 *   pinch and away from it for a stretch: at least [RADIAL_SHARE] of its
 *   travel. Two fingers swiped the same way (a multi-finger swipe) have one
 *   heading toward the other and one away, so they never get past this.
 * * The pair's shared drift, how far their midpoint moved, stays under
 *   [TRANSLATION_SHARE] of the change in the gap. Two thumbs each tracing a
 *   word wander as a pair; a pinch stays roughly where it started.
 *
 * Null is "not yet": the caller keeps feeding it until it answers or the touch
 * is abandoned for another reason.
 */
internal fun classifyPinch(a: PinchStroke, b: PinchStroke, spanPx: Float, fingerPx: Float): PinchGesture? {
    val gx0 = b.x0 - a.x0
    val gy0 = b.y0 - a.y0
    val gap0 = sqrt(gx0 * gx0 + gy0 * gy0)
    if (gap0 < 1f) return null
    val gx1 = b.x1 - a.x1
    val gy1 = b.y1 - a.y1
    val gap1 = sqrt(gx1 * gx1 + gy1 * gy1)
    val change = gap1 - gap0
    if (abs(change) < spanPx) return null
    val gesture = when {
        gap1 >= gap0 * MIN_SCALE -> PinchGesture.STRETCH
        gap1 * MIN_SCALE <= gap0 -> PinchGesture.PINCH
        else -> return null
    }
    if (a.travel < fingerPx || b.travel < fingerPx) return null
    // The unit line from a to b where they landed. A stretch moves a along
    // -u and b along +u; a pinch the other way round.
    val ux = gx0 / gap0
    val uy = gy0 / gap0
    val aOut = -(a.dx * ux + a.dy * uy)
    val bOut = b.dx * ux + b.dy * uy
    val sign = if (gesture == PinchGesture.STRETCH) 1f else -1f
    if (aOut * sign < a.travel * RADIAL_SHARE || bOut * sign < b.travel * RADIAL_SHARE) return null
    val mx = (a.dx + b.dx) / 2f
    val my = (a.dy + b.dy) / 2f
    if (sqrt(mx * mx + my * my) > abs(change) * TRANSLATION_SHARE) return null
    return gesture
}

/** How much the gap between the fingers has to grow or shrink by, as a factor. */
internal const val MIN_SCALE = 1.25f

/** cos(50°): how far off the line between them each finger may head. */
internal const val RADIAL_SHARE = 0.64f

/** How far the pair's midpoint may move, against the change in the gap. */
internal const val TRANSLATION_SHARE = 0.5f

/** The change in the gap that claims a pinch or stretch, in dp. */
internal const val PinchSpanDp = 40f

/** How far each finger has to travel, in dp. */
internal const val PinchFingerDp = 14f

/** How far a lone finger may have moved before the second lands, in dp. */
internal const val PinchLandSlopDp = 10f

/** How long after the first finger the second may land, in ms. */
internal const val PinchLandWindowMs = 300L

/**
 * Follows the fingers on the key grid one pointer event at a time and claims
 * the touch when [classifyPinch] says the two of them are a pinch or a
 * stretch. Everything around that rule lives here: the pair has to land
 * together, stay a pair, and keep both fingers down until it is claimed.
 *
 * * The second finger lands within [landWindowMs] of the first, before the
 *   first has moved [landSlopPx]. Later than that, the first finger was
 *   already doing something of its own: holding a key, gliding, moving the
 *   cursor on the spacebar.
 * * A third finger before the claim abandons it: that is a multi-finger swipe
 *   or a resting hand.
 * * A finger lifted before the claim abandons it: that finger tapped its key.
 *
 * Until the claim the grid consumes nothing, so a touch that ends up being
 * none of these types and glides exactly as before.
 */
internal class PinchTracker(
    private val spanPx: Float,
    private val fingerPx: Float,
    private val landSlopPx: Float,
    private val landWindowMs: Long = PinchLandWindowMs,
) {
    private val ids = ArrayList<Long>(2)
    private val startX = HashMap<Long, Float>()
    private val startY = HashMap<Long, Float>()
    private val lastX = HashMap<Long, Float>()
    private val lastY = HashMap<Long, Float>()
    private val down = HashSet<Long>()
    private var firstAt = 0L

    /** Pinch or stretch, once claimed; from here every change belongs to it. */
    var claimed: PinchGesture? = null
        private set

    /** This touch can no longer become a pinch. */
    var abandoned = false
        private set

    /** Gives the touch up, for a reason the caller knows and this does not. */
    fun abandon() {
        if (claimed == null) abandoned = true
    }

    /**
     * One pointer's state in the latest event, at [uptimeMs]. Returns true
     * when this call is the one that claimed the touch.
     */
    fun track(id: Long, x: Float, y: Float, pressed: Boolean, uptimeMs: Long): Boolean {
        if (!pressed) {
            if (down.remove(id) && claimed == null) abandoned = true
            return false
        }
        if (id !in down) {
            if (claimed != null || abandoned) return false
            when (ids.size) {
                0 -> firstAt = uptimeMs
                1 -> if (uptimeMs - firstAt > landWindowMs || travel(ids[0]) > landSlopPx) {
                    abandoned = true
                    return false
                }
                else -> {
                    abandoned = true
                    return false
                }
            }
            ids.add(id)
            down.add(id)
            startX[id] = x
            startY[id] = y
        }
        lastX[id] = x
        lastY[id] = y
        if (claimed != null || abandoned || ids.size != 2) return false
        val gesture = classifyPinch(stroke(ids[0]), stroke(ids[1]), spanPx, fingerPx) ?: return false
        claimed = gesture
        return true
    }

    /** The pinch or stretch, once every finger is up. Null unless it was claimed. */
    fun result(): PinchGesture? = claimed?.takeIf { down.isEmpty() }

    private fun stroke(id: Long) =
        PinchStroke(startX.getValue(id), startY.getValue(id), lastX.getValue(id), lastY.getValue(id))

    private fun travel(id: Long): Float = stroke(id).travel
}

package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.FingerSwipe
import com.wasimaster.wmkeyboard.core.layout.FingerSwipeCounts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.CaptureSelectionAction
import kotlin.math.abs

/**
 * Decides whether the fingers on the key grid are a multi-finger swipe
 * (issue #647), one pointer event at a time. Pure, so the rules are tested on
 * the JVM; the grid's pointer loop feeds it and acts on [claimed].
 *
 * The swipe has to be told apart from fast typing, where two fingers are often
 * down at once, so it is claimed only when nothing else can be happening:
 * two or more fingers down, none of them lifted yet, every one of them moved
 * at least [claimPx] from where it landed, and every one of them heading the
 * same way. A typist rolling from one key to the next lifts before moving that
 * far, and a resting thumb under a gliding finger does not move at all. Until
 * the claim the loop consumes nothing, so taps and glides are untouched.
 *
 * Fingers have to arrive together, too: one that lands while another has
 * already travelled [claimPx] joins a glide, not a swipe. After the claim a
 * late finger still counts, so a three-finger swipe whose third finger touched
 * down a moment after the others is still three.
 *
 * @param twoThumb two-thumb glide is on (#638): two moving thumbs are two
 *   words there, so two fingers never claim, though three or four still do.
 */
internal class FingerSwipeTracker(
    private val claimPx: Float,
    private val twoThumb: Boolean,
) {
    private val startX = HashMap<Long, Float>()
    private val startY = HashMap<Long, Float>()
    private val lastX = HashMap<Long, Float>()
    private val lastY = HashMap<Long, Float>()
    private val down = HashSet<Long>()

    /** The most fingers down at once since the first landed. */
    var maxCount = 0
        private set

    /** The fingers are a swipe; from here every change belongs to it. */
    var claimed = false
        private set

    /** This touch can no longer become a swipe. */
    var abandoned = false
        private set

    /** Where each finger landed, for drawing its trail from the start. */
    fun start(id: Long): Pair<Float, Float>? = startX[id]?.let { it to startY.getValue(id) }

    /**
     * One pointer's state in the latest event. Returns true when this call is
     * the one that claimed the swipe.
     */
    fun track(id: Long, x: Float, y: Float, pressed: Boolean): Boolean {
        if (!pressed) {
            if (down.remove(id) && !claimed) abandoned = true
            return false
        }
        if (id !in down) {
            if (!claimed && down.any { travel(it) >= claimPx }) abandoned = true
            down.add(id)
            startX[id] = x
            startY[id] = y
            maxCount = maxOf(maxCount, down.size)
        }
        lastX[id] = x
        lastY[id] = y
        if (claimed || abandoned) return false
        if (down.size < FingerSwipeCounts.first || down.size > FingerSwipeCounts.last) return false
        if (twoThumb && down.size == 2) return false
        val first = directionOf(down.first()) ?: return false
        if (down.any { directionOf(it) != first }) return false
        claimed = true
        return true
    }

    /**
     * The swipe, once every finger is up: the most fingers that were down at
     * once, and the way they went on average. Null unless it was claimed.
     */
    fun result(): FingerSwipe? {
        if (!claimed || down.isNotEmpty()) return null
        var dx = 0f
        var dy = 0f
        for (id in startX.keys) {
            dx += lastX.getValue(id) - startX.getValue(id)
            dy += lastY.getValue(id) - startY.getValue(id)
        }
        val direction = axisOf(dx, dy) ?: return null
        return FingerSwipe(maxCount.coerceIn(FingerSwipeCounts), direction)
    }

    private fun travel(id: Long): Float {
        val dx = lastX.getValue(id) - startX.getValue(id)
        val dy = lastY.getValue(id) - startY.getValue(id)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** One finger's heading, or null while it is short of [claimPx] or off every axis. */
    private fun directionOf(id: Long): SwipeDirection? {
        val dx = lastX.getValue(id) - startX.getValue(id)
        val dy = lastY.getValue(id) - startY.getValue(id)
        if (dx * dx + dy * dy < claimPx * claimPx) return null
        val major = maxOf(abs(dx), abs(dy))
        val minor = minOf(abs(dx), abs(dy))
        // Within the cone around one axis; a diagonal is no direction at all.
        if (minor > major * AXIS_CONE_TAN) return null
        return axisOf(dx, dy)
    }

    private fun axisOf(dx: Float, dy: Float): SwipeDirection? = when {
        dx == 0f && dy == 0f -> null
        abs(dx) >= abs(dy) -> if (dx > 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        else -> if (dy > 0f) SwipeDirection.DOWN else SwipeDirection.UP
    }

    companion object {
        /** tan(35°): how far off an axis a finger may lean and still be heading along it. */
        const val AXIS_CONE_TAN = 0.70f
    }
}

/** How far every finger has to travel before the swipe is claimed, in dp. */
internal const val FingerSwipeClaimDp = 28f

/**
 * Whether a multi-finger swipe has taken the touch now on the grid (issue
 * #647). Set by the swipe's loop, which runs first, and read by every other
 * grid loop before it acts on a lift: they keep following their own finger
 * meanwhile, and the swipe consuming its changes is not something all of them
 * look at. Plain, not snapshot state; written and read inside pointer
 * dispatch only.
 */
internal class FingerSwipeGate {
    var claimed = false

    /**
     * The claim is a pinch or stretch (gap list item 18), not a swipe: the
     * swipe's own loop gives the touch up, and the pinch's loop owns it.
     */
    var pinched = false
}

/**
 * What a swipe's bound action means while one of the keyboard's own boxes has
 * the keys (issue #712): the translator's text, a search, a note. Select all,
 * copy, cut, paste, undo and redo act on that box, as its selection bar does,
 * rather than on the app behind the panel. [Unbound] is an action the box has
 * no counterpart for and that would otherwise reach that app; null is one
 * that already finds the box on its own way through the service (arrows,
 * backspace) or has nothing to do with text, and runs as the key it is.
 */
internal sealed interface CaptureSwipe {
    data class Selection(val action: CaptureSelectionAction) : CaptureSwipe
    data object Unbound : CaptureSwipe
}

internal fun KeyAction.inCaptureBox(): CaptureSwipe? {
    val selection = when (this) {
        is KeyAction.Edit -> when (op) {
            TextEditAction.SELECT_ALL -> CaptureSelectionAction.SELECT_ALL
            TextEditAction.COPY -> CaptureSelectionAction.COPY
            TextEditAction.CUT -> CaptureSelectionAction.CUT
            TextEditAction.PASTE -> CaptureSelectionAction.PASTE
            else -> null
        }
        is KeyAction.Tool -> when (tool) {
            ToolbarTool.SELECT_ALL -> CaptureSelectionAction.SELECT_ALL
            ToolbarTool.COPY -> CaptureSelectionAction.COPY
            ToolbarTool.CUT -> CaptureSelectionAction.CUT
            ToolbarTool.PASTE -> CaptureSelectionAction.PASTE
            ToolbarTool.UNDO -> CaptureSelectionAction.UNDO
            ToolbarTool.REDO -> CaptureSelectionAction.REDO
            else -> null
        }
        else -> null
    }
    return selection?.let(CaptureSwipe::Selection)
}

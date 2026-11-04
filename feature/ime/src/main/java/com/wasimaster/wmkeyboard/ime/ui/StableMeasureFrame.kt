package com.wasimaster.wmkeyboard.ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowInsetsCompat
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlin.math.abs

/**
 * The input view's outermost frame: measures the keyboard against the same
 * height in both of the platform's measure passes.
 *
 * The IME window's own layout (a vertical `LinearLayout` with a weighted
 * fullscreen area above the input area) measures the input view twice per
 * traversal, first against the whole screen and then against what is left —
 * `AT_MOST 2400`, then `AT_MOST 1512`, measured on a 1080x2400 phone. Compose
 * reads two different constraints inside one traversal as "this view is
 * measured with multiple constraints" (`AndroidComposeView`'s
 * `wasMeasuredWithMultipleConstraints`), and from then until the next
 * traversal *any* node that needs measuring again is walked up to the root and
 * turned into a full window layout, because its size might now matter to a
 * parent that is measured more than one way. For a keyboard that is every key
 * press, every release and every strip update: a whole-window layout pass two
 * or three times per keystroke, with the whole tree measured twice in each
 * because its root constraints changed between the passes.
 *
 * So both passes are handed one height: the largest room offered in this
 * traversal. The first pass offers the whole screen and the second what is
 * left, so the second is measured against the first's room too, and Compose
 * sees one set of constraints and keeps a key press to the nodes it touched.
 *
 * It used to be the smaller of what was offered and the height the frame was
 * last laid out in. That also held the room to the window's old height, so
 * the window could never grow: a panel that asked for more room (the AI chat's
 * composer, Translate, Wikipedia's expanded article) opened into the height
 * the last panel left and squeezed its own content to fit. The first pass of
 * each traversal starts the room afresh, so a rotation or the extract view
 * that really does shrink the room is picked up at once.
 *
 * The second pass is not always the window echoing the height the first one
 * reported, though. The first offer is the whole display, status bar included,
 * and the window cannot cover the status bar, so when the keyboard asks for
 * more than the display less that bar the second pass offers less than it
 * asked for (issue #370). On a phone held sideways that is every time: the
 * key-preview band fills whatever the board leaves of the room, up to ~130dp,
 * so board and band together reached the whole display, the window was held
 * the status bar's height shorter, and the board, laid out at the first
 * pass's room, ran that far past the screen's bottom edge. A second offer
 * below what the frame just reported is therefore a [ceiling], not an echo:
 * the room drops to it in that pass, and every later traversal under the same
 * first offer starts from it, so both passes agree again from then on. A new
 * first offer (a rotation, a resized window) forgets it, and so does every
 * new showing of the window (#669): a hold learned from one passing frame
 * kept the window short for good, its bottom cut off under the same first
 * offer, until the screen turned. A real limit is learned again within the
 * first traversal of the new showing.
 *
 * Exact specs are passed through untouched: they cannot differ between passes
 * in a way this could smooth over.
 *
 * It also keeps window-insets animations from reaching the keyboard (issue
 * #463). Compose's insets listener sets a window inset aside while an insets
 * animation is being prepared or running, and applies it only when that
 * animation ends. An IME window that is hidden or handed to another keyboard
 * part way through one never sees the end, and from then on every new inset
 * is set aside too: the navigation bar read as zero and the bottom row sat on
 * the gesture handle after switching keyboards and back. Nothing in the
 * keyboard animates with an inset, so it takes each one as it arrives.
 *
 * It also keeps drawing for a few frames after its height changes (issue
 * #424). On some phones the frame that follows an IME window resize is shown
 * at the window's old position, top-aligned, and the new position only takes
 * effect with the next frame drawn (the same capture as #217). A keyboard that
 * is still after the resize never draws that next frame. The resize tool was
 * the clearest case: it grows the window once on entry and then holds still,
 * so its outline sat at the bottom of the screen with the keys below the edge
 * until a touch redrew them.
 *
 * And, when asked, it reads a swipe in from either side edge as Back (issue
 * #437). Android's own back gesture works over a keyboard, but some phones
 * block it there, which leaves it half a screen up. Being the parent of every
 * key, the frame can take the swipe before the keys see more than its start,
 * and hand them a cancel for the press it began with.
 */
internal class StableMeasureFrame(
    context: Context,
    /** Where the navigation bar's insets are kept for the keys; see [ImeNavigationBars]. */
    private val navigationBars: ImeNavigationBars? = null,
) : FrameLayout(context) {

    /** The largest `AT_MOST` height offered since the last layout. */
    private var roomSize = 0

    /** No measure pass has run since the last layout, so the next one starts the room afresh. */
    private var freshTraversal = true

    /** The first `AT_MOST` height offered in this traversal. */
    private var firstOffer = 0

    /**
     * The most the window has been let have, learned from a second pass that
     * offered less than the frame reported, or 0 while none has; see the class
     * comment. Only good under the first offer it was learned under.
     */
    private var ceiling = 0
    private var ceilingUnder = 0

    /** Whether a swipe in from a side edge goes back; asked at each touch-down (#437). */
    var edgeSwipeBackEnabled: () -> Boolean = { false }

    /** What a finished edge swipe does. */
    var onEdgeSwipeBack: () -> Unit = {}

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** The pointer an edge swipe may be starting with, or -1 while none is. */
    private var edgePointer = -1
    private var edgeFromLeft = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    /** The swipe was taken from the keys, so the rest of it is the frame's. */
    private var edgeClaimed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeClaimed = false
                edgePointer = -1
                val edge = EDGE_WIDTH_DP * density
                if (edgeSwipeBackEnabled() && (ev.x <= edge || ev.x >= width - edge)) {
                    edgePointer = ev.getPointerId(0)
                    edgeFromLeft = ev.x <= edge
                    edgeDownX = ev.x
                    edgeDownY = ev.y
                }
            }
            MotionEvent.ACTION_MOVE -> if (edgePointer >= 0) {
                val index = ev.findPointerIndex(edgePointer)
                if (index < 0) {
                    edgePointer = -1
                } else {
                    val inward = (ev.getX(index) - edgeDownX) * if (edgeFromLeft) 1f else -1f
                    val across = abs(ev.getY(index) - edgeDownY)
                    when {
                        // Up or down more than across, or back out over the edge:
                        // a key press or a glide, and the keys keep it.
                        across > touchSlop && across > inward -> edgePointer = -1
                        inward < -touchSlop -> edgePointer = -1
                        inward >= EDGE_TRIGGER_DP * density -> {
                            edgeClaimed = true
                            return true
                        }
                    }
                }
            }
            // A second finger is typing, not swiping back.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                edgePointer = -1
        }
        return false
    }

    // Back is not a click: there is nothing here for performClick to stand for.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!edgeClaimed) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                edgeClaimed = false
                edgePointer = -1
                onEdgeSwipeBack()
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeClaimed = false
                edgePointer = -1
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, stableHeightSpec(heightMeasureSpec))
        // Never report more than was offered. The content may have been
        // measured against the traversal's larger room. A pass that offered
        // less than the content took has already lowered the room to it (see
        // stableHeightSpec), so this only trims an echo, never the keys.
        setMeasuredDimension(measuredWidth, View.resolveSize(measuredHeight, heightMeasureSpec))
        logClipping()
    }

    /** The last cut-off logged, so a held window is logged once, not every frame. */
    private var loggedClip = 0

    /**
     * How far the keyboard last measured past the window it was let have, or 0
     * while it fits. Watched by the resize tool, whose held frame gives this
     * much back (#724; see [ResizeWindowFit]).
     */
    val overflowPx = mutableIntStateOf(0)

    /**
     * Records in Diagnostics when the keyboard is taller than the window it is
     * given, the bottom rows and padding then being cut off (#669).
     */
    private fun logClipping() {
        val content = getChildAt(0)?.measuredHeight ?: return
        val clip = content - measuredHeight
        if (clip == loggedClip) return
        loggedClip = clip
        overflowPx.intValue = clip.coerceAtLeast(0)
        if (clip > 0) {
            DebugLog.w(
                LOG_TAG,
                "keyboard ${content}px cut to ${measuredHeight}px (room $roomSize, ceiling $ceiling under $ceilingUnder)",
            )
        }
    }

    /**
     * The window is on screen again: a [ceiling] learned in an earlier showing
     * no longer holds; see the class comment.
     */
    fun onShownAgain() {
        if (ceiling > 0) DebugLog.i(LOG_TAG, "forgot the window hold of ${ceiling}px")
        ceiling = 0
        ceilingUnder = 0
    }

    private fun stableHeightSpec(offered: Int): Int {
        if (MeasureSpec.getMode(offered) != MeasureSpec.AT_MOST) return offered
        val size = MeasureSpec.getSize(offered)
        roomSize = when {
            freshTraversal -> {
                firstOffer = size
                if (size != ceilingUnder) ceiling = 0
                if (ceiling > 0) minOf(size, ceiling) else size
            }
            // Held below what was just reported: the window's real limit.
            size < roomSize && size < measuredHeight -> {
                if (size != ceiling) {
                    DebugLog.i(LOG_TAG, "window held at ${size}px under ${firstOffer}px (wanted $measuredHeight)")
                }
                ceiling = size
                ceilingUnder = firstOffer
                size
            }
            else -> maxOf(roomSize, size)
        }
        freshTraversal = false
        return MeasureSpec.makeMeasureSpec(roomSize, MeasureSpec.AT_MOST)
    }

    /** Frames still to draw after the last height change; see the class comment. */
    private var settleFrames = 0

    private val settleRedraw = object : Runnable {
        override fun run() {
            if (settleFrames <= 0) return
            settleFrames--
            invalidate()
            if (settleFrames > 0) postOnAnimation(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Not the first layout: there is no older frame on screen to replace.
        if (oldh == 0 || h == oldh) return
        val idle = settleFrames == 0
        settleFrames = SETTLE_FRAMES
        if (idle) postOnAnimation(settleRedraw)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleRedraw)
        settleFrames = 0
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        freshTraversal = true
    }

    // Only the insets dispatched to this frame, never the window's raw
    // rootWindowInsets: those are taken before anything above the keyboard
    // has used them up, and reading them padded the keys a second time for a
    // bar already padded for, a row-high gap below the space bar (#569).
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navigationBars?.update(insets, this)
        logInsets(insets)
        return super.dispatchApplyWindowInsets(insets)
    }

    /** The last insets logged, so an unchanged dispatch is not logged again. */
    private var loggedInsets = ""

    /**
     * Records the bottom insets the keyboard pads by in Diagnostics, with what
     * the bar would be were it shown, so a report of the bottom row on the
     * navigation bar (#669) shows which of the two went missing.
     */
    private fun logInsets(insets: WindowInsets) {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        val nav = WindowInsetsCompat.Type.navigationBars()
        val line = "nav bottom ${compat.getInsets(nav).bottom}px" +
            " (shown ${compat.isVisible(nav)}, full ${compat.getInsetsIgnoringVisibility(nav).bottom}px)" +
            ", caption ${compat.getInsets(WindowInsetsCompat.Type.captionBar()).bottom}px" +
            " (shown ${compat.isVisible(WindowInsetsCompat.Type.captionBar())})" +
            ", gestures ${compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom}px"
        if (line == loggedInsets) return
        loggedInsets = line
        DebugLog.i(LOG_TAG, "insets: $line")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onShownAgain()
        requestApplyInsets()
    }

    // The four stages of an insets animation stop here rather than going on to
    // the keyboard; see the class comment. The insets themselves still arrive
    // through the ordinary dispatch.

    @RequiresApi(Build.VERSION_CODES.R)
    override fun dispatchWindowInsetsAnimationPrepare(animation: WindowInsetsAnimation) = Unit

    @RequiresApi(Build.VERSION_CODES.R)
    override fun dispatchWindowInsetsAnimationStart(
        animation: WindowInsetsAnimation,
        bounds: WindowInsetsAnimation.Bounds,
    ): WindowInsetsAnimation.Bounds = bounds

    @RequiresApi(Build.VERSION_CODES.R)
    override fun dispatchWindowInsetsAnimationProgress(
        insets: WindowInsets,
        runningAnimations: MutableList<WindowInsetsAnimation>,
    ): WindowInsets = insets

    @RequiresApi(Build.VERSION_CODES.R)
    override fun dispatchWindowInsetsAnimationEnd(animation: WindowInsetsAnimation) = Unit

    private companion object {
        const val LOG_TAG = "window"

        /** How far in from a side a touch may start and still be an edge swipe. */
        const val EDGE_WIDTH_DP = 16f

        /** How far inward it has to travel before it is taken from the keys. */
        const val EDGE_TRIGGER_DP = 40f

        /**
         * Frames drawn after a height change. The #217 capture showed two
         * frames not composited, then the stale one, then the right one, so
         * this covers that with some margin and still finishes in about
         * 100ms at 60Hz.
         */
        const val SETTLE_FRAMES = 6
    }
}

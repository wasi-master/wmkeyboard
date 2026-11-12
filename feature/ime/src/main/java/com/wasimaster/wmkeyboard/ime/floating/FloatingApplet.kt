package com.wasimaster.wmkeyboard.ime.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.KeyboardViewLifecycleOwner
import com.wasimaster.wmkeyboard.ime.PanelMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A tool in a floating box over other apps (#1155): one of the keyboard's
 * own tool panels, the calculator or the to-do list, say, in a card that can
 * be dragged by its title bar, resized from its corner and closed, and that
 * stays on screen when the keyboard goes.
 *
 * One box at a time; opening another tool swaps what it shows. An overlay
 * window, through the "Display over other apps" grant the floating button
 * already holds, and never focusable: a touch on it never takes the focus
 * from the app's field, so the keyboard, when it is up, still belongs to that
 * field. A tool's text box (a note, a task) types from the keyboard's keys
 * the way it does inside the keyboard, through the service's capture routing
 * ([KeyboardUiState.toolUp]); the box is only where it is drawn.
 *
 * Its place and size are kept between openings, as the window's own memory,
 * not a setting: fractions of the screen for the place, dp for the size.
 *
 * It tucks into a side of the screen, pushed past the edge by its title bar
 * or by its tuck button, as the floating button tucks: a small tab with the
 * tool's icon is left on the edge, and a tap on it brings the box back. With
 * [com.wasimaster.wmkeyboard.core.settings.SummonButtonSettings.boxClosesOutside]
 * a tap anywhere else on the screen closes it.
 *
 * It moves by its title bar, its bottom bar, any part of the tool that a
 * drag does nothing to, or two fingers anywhere, which also pinch it bigger
 * or smaller (#1263, #1262). Let go moving, it carries on a little way and
 * settles; with
 * [com.wasimaster.wmkeyboard.core.settings.SummonButtonSettings.boxGestures]
 * a throw up closes it, a throw down moves the tool into the keyboard, and a
 * throw sideways tucks it (#1262). Its top never goes under the status bar,
 * where Android's shade would take every touch meant for it (#1263).
 */
internal class FloatingApplet(
    private val context: Context,
    private val host: Host,
) {

    /** What the box asks of the keyboard. */
    interface Host {
        /** The keyboard's state, which the tool's panel reads as it does inside the keyboard. */
        val state: StateFlow<KeyboardUiState>

        /** The callback bundles the tool's panel is handed. */
        fun appletCallbacks(): AppletCallbacks

        /** The box was closed, by its button or by the tool. */
        fun appletClosed(panel: PanelMode)

        /** The box's "open in the keyboard" button: the tool moves into the keyboard as its panel. */
        fun appletToKeyboard(panel: PanelMode)

        /** The box went into the edge (true), or came back out (false). Its text box has no keys while tucked. */
        fun appletTucked(tucked: Boolean)
    }

    private val box = FloatingAppletState()
    private val lifecycle = KeyboardViewLifecycleOwner().apply { onCreate() }
    private var view: BoxHost? = null
    private var params: WindowManager.LayoutParams? = null

    /** The docked keyboard's top edge on screen, which the box stays above; null while it is down. */
    private var ceilingPx: Int? = null

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** As [FloatingButton]'s: a window context of the overlay type from API 30. */
    private val windowContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val display = context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrNull() ?: context.applicationContext
        } else {
            context.applicationContext
        }
    }

    private val windowManager: WindowManager
        get() = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float get() = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /** The tool's panel the box shows, or null with the box closed. */
    val panel: PanelMode? get() = box.panel.takeIf { view != null }

    /**
     * Shows [panel] in the box, putting the box up where it was last left,
     * or swapping what an open one shows. False without the overlay grant, or
     * when Android refuses the window.
     */
    fun open(panel: PanelMode, title: String, icon: ImageVector): Boolean {
        box.title = title
        box.icon = icon
        val current = view
        if (current != null) {
            box.panel = panel
            raise()
            return true
        }
        box.tuckedLeft = null
        if (!Settings.canDrawOverlays(context)) return false
        box.panel = panel
        val frame = BoxHost(windowContext)
        val compose = ComposeView(windowContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by host.state.collectAsState()
                FloatingAppletFrame(
                    box = box,
                    state = state,
                    callbacks = host.appletCallbacks(),
                    onMove = ::onMove,
                    onBodyDrag = ::onBodyDrag,
                    onResize = ::onResize,
                    onTuck = { tuck(nearLeft()) },
                    onTabTouch = ::onTabTouch,
                    onRestore = ::untuck,
                    onClose = { close() },
                    onOpenInKeyboard = ::toKeyboard,
                )
            }
        }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lifecycle.attachTo(frame)
        lifecycle.onResume()
        val layout = layoutParams()
        if (runCatching { windowManager.addView(frame, layout) }.isFailure) {
            lifecycle.onStop()
            box.panel = null
            return false
        }
        view = frame
        params = layout
        frame.alpha = 0f
        frame.scaleX = 0.92f
        frame.scaleY = 0.92f
        frame.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.2f)).setDuration(APPEAR_MS).start()
        return true
    }

    /** The box's "open in the keyboard" button, or a throw down (#1262): the tool moves into the keyboard. */
    private fun toKeyboard() {
        box.panel?.let { shown ->
            close(notify = false)
            host.appletToKeyboard(shown)
        }
    }

    /** Closes the box. Safe to call with none open. */
    fun close() = close(notify = true)

    private fun close(notify: Boolean) {
        val current = view ?: return
        val shown = box.panel
        view = null
        params = null
        glide?.cancel()
        glide = null
        pinching = false
        current.animate().cancel()
        runCatching { windowManager.removeViewImmediate(current) }
        lifecycle.onStop()
        box.panel = null
        box.tuckedLeft = null
        if (notify && shown != null) host.appletClosed(shown)
    }

    /**
     * The tool's own button pressed while the box shows it: the box is where
     * the tool is, so it says so with a small pulse rather than the keyboard
     * opening a second copy.
     */
    fun raise() {
        val current = view ?: return
        // Tucked away, the tool's own button is the way to have it back.
        if (box.tuckedLeft != null) {
            untuck()
            return
        }
        current.animate().cancel()
        current.scaleX = 1.04f
        current.scaleY = 1.04f
        current.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(APPEAR_MS).start()
    }

    /** Keeps the box above [topPx], the docked keyboard's top on screen, or lets it back down when null. */
    fun keepAbove(topPx: Int?) {
        if (topPx == ceilingPx) return
        ceilingPx = topPx
        place()
    }

    /** The screen changed shape: put the box back on it. */
    fun relayout() = place()

    // ---- geometry ----

    private fun layoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // Pushed part way off a side on its way into the edge.
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Told of a touch elsewhere, for [onOutsideTouch].
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val (width, height) = size()
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard floating tool"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            positionFromPrefs(this)
        }
    }

    /** The window's size in px: as last left, else a card a little narrower than the screen. */
    private fun size(): Pair<Int, Int> {
        val (screenW, screenH) = screenSize()
        val shadow = 2 * dp(FLOATING_APPLET_SHADOW_DP)
        val width = prefs.getInt(KEY_W, -1).takeIf { it > 0 }?.let(::dp)
            ?: minOf(screenW - dp(16), dp(DEFAULT_WIDTH_DP) + shadow)
        val height = prefs.getInt(KEY_H, -1).takeIf { it > 0 }?.let(::dp)
            ?: dp(DEFAULT_HEIGHT_DP) + shadow
        return width.coerceIn(minOf(dp(MIN_WIDTH_DP), screenW), screenW) to
            height.coerceIn(minOf(dp(MIN_HEIGHT_DP), screenH), screenH)
    }

    private fun positionFromPrefs(layout: WindowManager.LayoutParams) {
        val (roomX, roomY) = room(layout)
        layout.x = (prefs.getFloat(KEY_X, 0.5f).coerceIn(0f, 1f) * roomX).roundToInt()
        val floor = topFloor()
        val rest = (prefs.getFloat(KEY_Y, DEFAULT_Y).coerceIn(0f, 1f) * roomY).roundToInt().coerceAtLeast(floor)
        // Lifted clear of a docked keyboard, as the floating button is, without moving where it was left.
        val ceiling = ceilingPx?.let { it - layout.height }
        layout.y = if (ceiling != null && rest > ceiling) ceiling.coerceAtLeast(floor) else rest
    }

    /**
     * The highest the window's top may go (#1263): the card's top edge just
     * under the status bar and any cutout, its shadow room allowed over them.
     * A title bar under the status bar was a box nobody could move again,
     * since Android's shade takes every touch there.
     */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun topFloor(): Int {
        val inset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                windowManager.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            }.getOrDefault(0)
        } else {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) context.resources.getDimensionPixelSize(id) else dp(STATUS_BAR_FALLBACK_DP)
        }
        return (inset - dp(FLOATING_APPLET_SHADOW_DP)).coerceAtLeast(0)
    }

    private fun place() {
        val layout = params ?: return
        val current = view ?: return
        val onLeft = box.tuckedLeft
        if (onLeft != null) placeTab(layout, onLeft) else positionFromPrefs(layout)
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    /** Keeps where the box was let go, as fractions of the room the screen leaves it. */
    private fun saveRest(layout: WindowManager.LayoutParams) {
        val (roomX, roomY) = room(layout)
        prefs.edit {
            putFloat(KEY_X, if (roomX == 0) 0.5f else layout.x.coerceIn(0, roomX).toFloat() / roomX)
            putFloat(KEY_Y, if (roomY == 0) 0f else layout.y.coerceIn(0, roomY).toFloat() / roomY)
        }
    }

    /** Keeps the box's size, in dp, for the next time it opens. */
    private fun saveSize(layout: WindowManager.LayoutParams) {
        prefs.edit {
            putInt(KEY_W, (layout.width / density).roundToInt())
            putInt(KEY_H, (layout.height / density).roundToInt())
        }
    }

    /** Whether the box's middle is on the left half of the screen, the side a tuck goes to. */
    private fun nearLeft(): Boolean {
        val layout = params ?: return false
        return layout.x + layout.width / 2 < screenSize().first / 2
    }

    // ---- tucking into the edge ----

    /** Where the tab's top sits on screen while tucked; kept as it is dragged. */
    private var tabTopPx = 0

    /**
     * Into the [onLeft] or right edge: the box gives way to its tab there, at
     * the height of the box's middle, and comes back against that side.
     */
    private fun tuck(onLeft: Boolean) {
        val layout = params ?: return
        val current = view ?: return
        if (box.tuckedLeft != null) return
        glide?.cancel()
        glide = null
        // It comes back against the side it went into, at the height it was.
        layout.x = if (onLeft) 0 else room(layout).first
        layout.y = layout.y.coerceIn(topFloor().coerceAtMost(room(layout).second), room(layout).second)
        saveRest(layout)
        tabTopPx = layout.y + layout.height / 2 - dp(TAB_HEIGHT_DP) / 2
        box.tuckedLeft = onLeft
        layout.width = dp(TAB_WIDTH_DP)
        layout.height = dp(TAB_HEIGHT_DP)
        placeTab(layout, onLeft)
        runCatching { windowManager.updateViewLayout(current, layout) }
        current.animate().cancel()
        current.alpha = 1f
        current.scaleX = 1f
        current.scaleY = 1f
        current.translationX = if (onLeft) -layout.width.toFloat() else layout.width.toFloat()
        current.animate().translationX(0f).setInterpolator(DecelerateInterpolator()).setDuration(APPEAR_MS).start()
        host.appletTucked(true)
    }

    /** Out of the edge, at its size and place, as a tap on its tab asks. */
    private fun untuck() {
        val layout = params ?: return
        val current = view ?: return
        if (box.tuckedLeft == null) return
        box.tuckedLeft = null
        val (width, height) = size()
        layout.width = width
        layout.height = height
        positionFromPrefs(layout)
        runCatching { windowManager.updateViewLayout(current, layout) }
        current.animate().cancel()
        current.translationX = 0f
        current.alpha = 0f
        current.scaleX = 0.92f
        current.scaleY = 0.92f
        current.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.2f)).setDuration(APPEAR_MS).start()
        host.appletTucked(false)
    }

    /** The tab flat against its side, kept on screen and clear of a docked keyboard. */
    private fun placeTab(layout: WindowManager.LayoutParams, onLeft: Boolean) {
        val (screenW, screenH) = screenSize()
        layout.x = if (onLeft) 0 else screenW - layout.width
        val lowest = (ceilingPx ?: screenH) - layout.height
        val floor = topFloor()
        layout.y = tabTopPx.coerceIn(floor.coerceAtMost(lowest), lowest.coerceAtLeast(floor))
    }

    /**
     * The tab: a tap brings the box back; a drag moves the tab up or down
     * its edge, where it stays until the box comes out.
     */
    private fun onTabTouch(event: MotionEvent): Boolean {
        val layout = params ?: return false
        val current = view ?: return false
        val onLeft = box.tuckedLeft ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                downB = tabTopPx
                tabDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                if (!tabDragging && abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) tabDragging = true
                if (tabDragging) {
                    tabTopPx = downB + dy.roundToInt()
                    placeTab(layout, onLeft)
                    tabTopPx = layout.y
                    runCatching { windowManager.updateViewLayout(current, layout) }
                }
            }
            MotionEvent.ACTION_UP -> if (!tabDragging) untuck()
            MotionEvent.ACTION_CANCEL -> tabDragging = false
        }
        return true
    }

    private var tabDragging = false

    /**
     * A touch outside the box: it closes, with the setting on. Not while
     * tucked, nor for a touch on the docked keyboard, which is typing, as
     * often as not into the box's own text box. Android gives the place of an
     * outside touch only for the keyboard's own windows, the keys among them,
     * and zero for another app's.
     */
    private fun onOutsideTouch(event: MotionEvent) {
        if (box.tuckedLeft != null) return
        if (!host.state.value.settings.summonButton.boxClosesOutside) return
        val keys = ceilingPx
        if (keys != null && event.rawY >= keys) return
        close()
    }

    private fun room(layout: WindowManager.LayoutParams): Pair<Int, Int> {
        val (width, height) = screenSize()
        return (width - layout.width).coerceAtLeast(0) to (height - layout.height).coerceAtLeast(0)
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    // ---- dragging and resizing ----

    private var downRawX = 0f
    private var downRawY = 0f
    private var downA = 0
    private var downB = 0
    private var downTime = 0L

    /** The finger's speed over the last drag, for the throw and the glide after it. */
    private var tracker: VelocityTracker? = null

    /** The glide a let-go box makes to rest, or a throw on its way off the screen. */
    private var glide: ValueAnimator? = null

    /** Two fingers have the box (#1263): every other drag stands down until they let go. */
    private var pinching = false

    /** The title bar or the bottom bar: the whole box follows the finger, and is remembered where it is let go. */
    private fun onMove(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragStart(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_MOVE -> dragTo(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_UP -> dragEnd(event.rawX, event.rawY, event.eventTime, cancelled = false)
            MotionEvent.ACTION_CANCEL -> dragEnd(event.rawX, event.rawY, event.eventTime, cancelled = true)
        }
        return true
    }

    /**
     * A drag on the tool itself, where nothing in it took the drag (#1263).
     * Compose reads it; the finger's place on screen is the one the window
     * last saw, since points inside a window that moves under them chase
     * themselves.
     */
    private fun onBodyDrag(phase: BodyDrag) {
        val frame = view ?: return
        when (phase) {
            BodyDrag.START -> dragStart(frame.rawX, frame.rawY, frame.time)
            BodyDrag.MOVE -> dragTo(frame.rawX, frame.rawY, frame.time)
            BodyDrag.END -> dragEnd(frame.rawX, frame.rawY, frame.time, cancelled = false)
            BodyDrag.CANCEL -> dragEnd(frame.rawX, frame.rawY, frame.time, cancelled = true)
        }
    }

    private fun dragStart(rawX: Float, rawY: Float, time: Long) {
        val layout = params ?: return
        if (pinching) return
        glide?.cancel()
        glide = null
        // Caught mid-throw, it is solid again.
        view?.alpha = 1f
        downRawX = rawX
        downRawY = rawY
        downA = layout.x
        downB = layout.y
        downTime = time
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        track(rawX, rawY, time, MotionEvent.ACTION_DOWN)
    }

    private fun dragTo(rawX: Float, rawY: Float, time: Long) {
        val layout = params ?: return
        val current = view ?: return
        if (pinching || tracker == null) return
        track(rawX, rawY, time, MotionEvent.ACTION_MOVE)
        val (roomX, roomY) = room(layout)
        // Past a side by up to half the box, on its way into the edge.
        val over = layout.width / 2
        layout.x = (downA + (rawX - downRawX).roundToInt()).coerceIn(-over, roomX + over)
        layout.y = (downB + (rawY - downRawY).roundToInt()).coerceIn(topFloor().coerceAtMost(roomY), roomY)
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    /**
     * The drag let go. Thrown, with gestures on (#1262), the box goes: up it
     * closes, down it moves into the keyboard, sideways it tucks. Pushed far
     * enough into a side it tucks into it either way. Anywhere else it
     * carries on the way it was going and comes to rest, where it is kept.
     */
    private fun dragEnd(rawX: Float, rawY: Float, time: Long, cancelled: Boolean) {
        val layout = params ?: return
        val current = view ?: return
        val velocity = tracker ?: return
        tracker = null
        if (pinching) {
            velocity.recycle()
            return
        }
        track(rawX, rawY, time, MotionEvent.ACTION_UP, velocity)
        velocity.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
        val vx = if (cancelled) 0f else velocity.xVelocity
        val vy = if (cancelled) 0f else velocity.yVelocity
        velocity.recycle()
        val fling = THROW_DP_PER_S * density
        if (host.state.value.settings.summonButton.boxGestures && !cancelled) {
            when {
                vy < -fling && abs(vy) > abs(vx) * THROW_AXIS -> {
                    throwOff(up = true, vy)
                    return
                }
                vy > fling && abs(vy) > abs(vx) * THROW_AXIS -> {
                    throwOff(up = false, vy)
                    return
                }
                abs(vx) > fling && abs(vx) > abs(vy) * THROW_AXIS -> {
                    tuck(onLeft = vx < 0)
                    return
                }
            }
        }
        val roomX = room(layout).first
        val past = when {
            layout.x < 0 -> -layout.x
            layout.x > roomX -> layout.x - roomX
            else -> 0
        }
        // Pushed far enough into a side, it tucks into it; a little
        // way, it settles back against it.
        if (!cancelled && past > layout.width * TUCK_PAST) {
            tuck(onLeft = layout.x < 0)
            return
        }
        settle(current, layout, vx, vy)
    }

    /** Feeds the finger's place on screen to [into], or to the drag's tracker. */
    private fun track(rawX: Float, rawY: Float, time: Long, action: Int, into: VelocityTracker? = tracker) {
        val sink = into ?: return
        val event = MotionEvent.obtain(downTime, time, action, rawX, rawY, 0)
        sink.addMovement(event)
        event.recycle()
    }

    /**
     * The box carries on from the speed it was let go at, a short way and
     * slowing, and stops inside the screen, below the status bar and above
     * a docked keyboard: the floating button's feel (#1262). Kept where it
     * stops.
     */
    private fun settle(current: BoxHost, layout: WindowManager.LayoutParams, vx: Float, vy: Float) {
        val (roomX, roomY) = room(layout)
        val floor = topFloor().coerceAtMost(roomY)
        val lowest = ceilingPx?.let { (it - layout.height).coerceIn(floor, roomY) } ?: roomY
        val fromX = layout.x
        val fromY = layout.y
        val toX = (fromX + vx * GLIDE_S).roundToInt().coerceIn(0, roomX)
        val toY = (fromY + vy * GLIDE_S).roundToInt().coerceIn(floor, maxOf(floor, lowest, fromY.coerceAtMost(roomY)))
        if (toX == fromX && toY == fromY) {
            saveRest(layout)
            return
        }
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                if (view !== current) return@addUpdateListener
                val t = it.animatedValue as Float
                layout.x = fromX + ((toX - fromX) * t).roundToInt()
                layout.y = fromY + ((toY - fromY) * t).roundToInt()
                runCatching { windowManager.updateViewLayout(current, layout) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var stopped = false
                override fun onAnimationCancel(animation: Animator) {
                    stopped = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // Kept wherever it stopped, a touch that caught it included.
                    if (view === current) saveRest(layout)
                    if (!stopped) glide = null
                }
            })
            start()
        }
    }

    /**
     * Thrown off the top (closes) or the bottom (into the keyboard) of the
     * screen (#1262): the box flies on at the speed it was thrown, fading,
     * and goes once it is off. Where it rested before the throw is still
     * where it opens next time.
     */
    private fun throwOff(up: Boolean, vy: Float) {
        val layout = params ?: return
        val current = view ?: return
        val screenH = screenSize().second
        val fromY = layout.y
        val toY = if (up) -layout.height else screenH
        val ms = (abs(toY - fromY) / abs(vy).coerceAtLeast(1f) * 1000f).toLong().coerceIn(THROW_MIN_MS, THROW_MAX_MS)
        glide?.cancel()
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (view !== current) return@addUpdateListener
                val t = it.animatedValue as Float
                layout.y = fromY + ((toY - fromY) * t).roundToInt()
                current.alpha = 1f - t
                runCatching { windowManager.updateViewLayout(current, layout) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var stopped = false
                override fun onAnimationCancel(animation: Animator) {
                    stopped = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (stopped || view !== current) return
                    glide = null
                    if (up) close() else toKeyboard()
                }
            })
            start()
        }
    }

    // ---- two fingers ----

    private var pinchSpan = 0f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinchX = 0
    private var pinchY = 0
    private var pinchW = 0
    private var pinchH = 0

    /** A second finger came down on the box: the two move it, and pinch it bigger or smaller (#1262, #1263). */
    private fun pinchStart(event: MotionEvent) {
        val layout = params ?: return
        glide?.cancel()
        glide = null
        tracker?.recycle()
        tracker = null
        pinching = true
        pinchSpan = span(event).coerceAtLeast(1f)
        pinchCenterX = (rawX(event, 0) + rawX(event, 1)) / 2f
        pinchCenterY = (rawY(event, 0) + rawY(event, 1)) / 2f
        pinchX = layout.x
        pinchY = layout.y
        pinchW = layout.width
        pinchH = layout.height
    }

    private fun pinchMove(event: MotionEvent) {
        val layout = params ?: return
        val current = view ?: return
        if (!pinching || event.pointerCount < 2) return
        val (screenW, screenH) = screenSize()
        val scale = span(event) / pinchSpan
        val width = (pinchW * scale).roundToInt().coerceIn(minOf(dp(MIN_WIDTH_DP), screenW), screenW)
        val height = (pinchH * scale).roundToInt().coerceIn(minOf(dp(MIN_HEIGHT_DP), screenH), screenH)
        val cx = (rawX(event, 0) + rawX(event, 1)) / 2f
        val cy = (rawY(event, 0) + rawY(event, 1)) / 2f
        layout.width = width
        layout.height = height
        // Grown about its middle, and carried with the fingers.
        val (roomX, roomY) = room(layout)
        layout.x = (pinchX + (cx - pinchCenterX) - (width - pinchW) / 2f).roundToInt().coerceIn(0, roomX)
        layout.y = (pinchY + (cy - pinchCenterY) - (height - pinchH) / 2f).roundToInt()
            .coerceIn(topFloor().coerceAtMost(roomY), roomY)
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    private fun pinchEnd() {
        val layout = params ?: return
        if (!pinching) return
        pinching = false
        saveSize(layout)
        saveRest(layout)
    }

    private fun span(event: MotionEvent): Float =
        hypot(rawX(event, 1) - rawX(event, 0), rawY(event, 1) - rawY(event, 0))

    /** Pointer [index]'s place on screen; the window's own offset, shared by every pointer, added back before API 29. */
    private fun rawX(event: MotionEvent, index: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawX(index) else event.getX(index) + event.rawX - event.getX(0)

    private fun rawY(event: MotionEvent, index: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawY(index) else event.getY(index) + event.rawY - event.getY(0)

    /**
     * The box's window: the composition, the place on screen of every touch
     * on it for [onBodyDrag], two fingers for [pinchStart], and the touches
     * outside it for [onOutsideTouch].
     */
    @SuppressLint("ViewConstructor")
    private inner class BoxHost(context: Context) : FrameLayout(context) {
        var rawX = 0f
            private set
        var rawY = 0f
            private set
        var time = 0L
            private set

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // A touch anywhere else on the screen, for the setting that closes the box on one.
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch(ev)
                return false
            }
            rawX = ev.rawX
            rawY = ev.rawY
            time = ev.eventTime
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_POINTER_DOWN || ev.pointerCount != 2) return false
            if (box.tuckedLeft != null) return false
            pinchStart(ev)
            return true
        }

        /**
         * Two fingers that came down on a part of the box nothing in the
         * composition answers still pinch it: the first one's touch is kept
         * here so the second one's arrives.
         */
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) return box.tuckedLeft == null
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2 && !pinching) {
                if (box.tuckedLeft == null) pinchStart(event)
                return true
            }
            if (!pinching) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> pinchMove(event)
                // One finger left: the pinch is over, and the other finger's
                // drag does nothing until it lifts too.
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinchEnd()
            }
            return true
        }
    }

    /** The corner grip: the box grows or shrinks with the finger, its top left corner staying put. */
    private fun onResize(event: MotionEvent): Boolean {
        val layout = params ?: return false
        val current = view ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downA = layout.width
                downB = layout.height
            }
            MotionEvent.ACTION_MOVE -> {
                val (screenW, screenH) = screenSize()
                layout.width = (downA + (event.rawX - downRawX).roundToInt())
                    .coerceIn(minOf(dp(MIN_WIDTH_DP), screenW), (screenW - layout.x).coerceAtLeast(dp(MIN_WIDTH_DP)))
                layout.height = (downB + (event.rawY - downRawY).roundToInt())
                    .coerceIn(minOf(dp(MIN_HEIGHT_DP), screenH), (screenH - layout.y).coerceAtLeast(dp(MIN_HEIGHT_DP)))
                runCatching { windowManager.updateViewLayout(current, layout) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveSize(layout)
        }
        return true
    }

    private companion object {
        const val PREFS = "floating_applet"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_W = "w"
        const val KEY_H = "h"

        /** A card the width of a phone's keyboard less a margin, and about as tall as two keyboards' panels. */
        const val DEFAULT_WIDTH_DP = 340
        const val DEFAULT_HEIGHT_DP = 420
        const val MIN_WIDTH_DP = 220
        const val MIN_HEIGHT_DP = 200

        /** A fresh box sits a little below the top of the screen. */
        const val DEFAULT_Y = 0.12f
        const val APPEAR_MS = 220L

        /** The tab a tucked box leaves on its edge. */
        const val TAB_WIDTH_DP = 30
        const val TAB_HEIGHT_DP = 64

        /** How much of the box has to be pushed past a side for it to tuck, as a share of its width. */
        const val TUCK_PAST = 0.3f

        /** How fast a let-go box has to be moving to count as thrown (#1262), in dp a second. */
        const val THROW_DP_PER_S = 1800f

        /** A throw's direction has to beat the other axis by this much, so a diagonal flick does neither. */
        const val THROW_AXIS = 1.4f
        const val THROW_MIN_MS = 120L
        const val THROW_MAX_MS = 280L

        /** How far a let-go box carries on, as seconds of the speed it had, and how long it takes. */
        const val GLIDE_S = 0.12f
        const val GLIDE_MS = 320L

        /** The status bar's height where Android will not say (#1263). */
        const val STATUS_BAR_FALLBACK_DP = 24
    }
}

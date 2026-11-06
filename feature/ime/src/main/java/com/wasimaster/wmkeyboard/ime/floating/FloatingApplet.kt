package com.wasimaster.wmkeyboard.ime.floating

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
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
    private var view: ComposeView? = null
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
        val compose = ComposeView(windowContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by host.state.collectAsState()
                FloatingAppletFrame(
                    box = box,
                    state = state,
                    callbacks = host.appletCallbacks(),
                    onMove = ::onMove,
                    onResize = ::onResize,
                    onTuck = { tuck(nearLeft()) },
                    onTabTouch = ::onTabTouch,
                    onRestore = ::untuck,
                    onClose = { close() },
                    onOpenInKeyboard = {
                        box.panel?.let { shown ->
                            close(notify = false)
                            host.appletToKeyboard(shown)
                        }
                    },
                )
            }
        }
        // A touch anywhere else on the screen, for the setting that closes the box on one.
        compose.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(event)
            false
        }
        lifecycle.attachTo(compose)
        lifecycle.onResume()
        val layout = layoutParams()
        if (runCatching { windowManager.addView(compose, layout) }.isFailure) {
            lifecycle.onStop()
            box.panel = null
            return false
        }
        view = compose
        params = layout
        compose.alpha = 0f
        compose.scaleX = 0.92f
        compose.scaleY = 0.92f
        compose.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.2f)).setDuration(APPEAR_MS).start()
        return true
    }

    /** Closes the box. Safe to call with none open. */
    fun close() = close(notify = true)

    private fun close(notify: Boolean) {
        val current = view ?: return
        val shown = box.panel
        view = null
        params = null
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
        val rest = (prefs.getFloat(KEY_Y, DEFAULT_Y).coerceIn(0f, 1f) * roomY).roundToInt()
        // Lifted clear of a docked keyboard, as the floating button is, without moving where it was left.
        val ceiling = ceilingPx?.let { it - layout.height }
        layout.y = if (ceiling != null && rest > ceiling) ceiling.coerceAtLeast(0) else rest
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
        // It comes back against the side it went into, at the height it was.
        layout.x = if (onLeft) 0 else room(layout).first
        layout.y = layout.y.coerceIn(0, room(layout).second)
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
        layout.y = tabTopPx.coerceIn(0, lowest.coerceAtLeast(0))
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

    /** The title bar: the whole box follows the finger, and is remembered where it is let go. */
    private fun onMove(event: MotionEvent): Boolean {
        val layout = params ?: return false
        val current = view ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downA = layout.x
                downB = layout.y
            }
            MotionEvent.ACTION_MOVE -> {
                val (roomX, roomY) = room(layout)
                // Past a side by up to half the box, on its way into the edge.
                val over = layout.width / 2
                layout.x = (downA + (event.rawX - downRawX).roundToInt()).coerceIn(-over, roomX + over)
                layout.y = (downB + (event.rawY - downRawY).roundToInt()).coerceIn(0, roomY)
                runCatching { windowManager.updateViewLayout(current, layout) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val roomX = room(layout).first
                val past = when {
                    layout.x < 0 -> -layout.x
                    layout.x > roomX -> layout.x - roomX
                    else -> 0
                }
                // Pushed far enough into a side, it tucks into it; a little
                // way, it settles back against it.
                if (event.actionMasked == MotionEvent.ACTION_UP && past > layout.width * TUCK_PAST) {
                    tuck(onLeft = layout.x < 0)
                } else {
                    if (past > 0) {
                        layout.x = layout.x.coerceIn(0, roomX)
                        runCatching { windowManager.updateViewLayout(current, layout) }
                    }
                    saveRest(layout)
                }
            }
        }
        return true
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                prefs.edit {
                    putInt(KEY_W, (layout.width / density).roundToInt())
                    putInt(KEY_H, (layout.height / density).roundToInt())
                }
            }
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
    }
}

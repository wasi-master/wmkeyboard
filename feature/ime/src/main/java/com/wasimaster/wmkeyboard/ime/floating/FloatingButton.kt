package com.wasimaster.wmkeyboard.ime.floating

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.core.settings.FloatingCategory
import com.wasimaster.wmkeyboard.core.settings.FlickDirection
import com.wasimaster.wmkeyboard.core.settings.activeFlicks
import com.wasimaster.wmkeyboard.core.settings.flickDirectionOf
import com.wasimaster.wmkeyboard.core.settings.SummonButtonSettings
import com.wasimaster.wmkeyboard.core.settings.categoryOf
import com.wasimaster.wmkeyboard.core.settings.doubleTapFor
import com.wasimaster.wmkeyboard.core.settings.floatingVersion
import com.wasimaster.wmkeyboard.core.settings.floatsOverKeyboard
import com.wasimaster.wmkeyboard.core.settings.gestureActions
import com.wasimaster.wmkeyboard.core.settings.pillTouchWidthDp
import com.wasimaster.wmkeyboard.core.settings.staysTucked
import com.wasimaster.wmkeyboard.core.settings.tapFor
import com.wasimaster.wmkeyboard.core.settings.tucks
import com.wasimaster.wmkeyboard.ime.KeyboardViewLifecycleOwner
import com.wasimaster.wmkeyboard.ime.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The floating button (#755): one small round button over every app, which
 * began as the keyboard button (#374) and the AI button (#697) and is now
 * whatever its owner makes it. A tap, a double tap and a press and hold each
 * run a [FloatingAction]; the quick menu fans more of them out round it, and a
 * hold that opens the menu can pick a slot by sliding onto it and letting go.
 *
 * Three windows, all overlays that never take the focus:
 *
 * - the button, a disc with room round it for its shadow;
 * - the quick menu, the whole screen while it is open, so a tap anywhere
 *   else closes it;
 * - the drop target, shown at the bottom while the button is dragged, that
 *   puts the button away when it is let go there.
 *
 * Never focusable is the reason the button works at all: pressing it changes
 * nothing about who is focused, so the keyboard's show request lands on the
 * window the user was looking at. See [com.wasimaster.wmkeyboard.ime.KeyboardControls].
 *
 * Drawn with Compose under an owner of its own: the keyboard's lifecycle owner
 * is stopped whenever the keyboard is hidden, which is when this is most often
 * on screen. This one is resumed while a window of the button is up and
 * stopped when the last goes.
 *
 * An overlay window sits below the keyboard's own window, so while the
 * keyboard is docked the button is lifted clear of it ([keepAbove]); where it
 * was dragged to is kept, and it drops back there when the keyboard goes.
 *
 * Unless it floats over the keyboard (#926). Then, while the keyboard is up,
 * the button's windows are panels of the keyboard's own window instead
 * ([Host.keyboardWindowToken]), as a key's preview is: drawn above the keys,
 * needing no grant, and gone with the keyboard, when they come back as
 * overlays. A panel the size of the button takes only the touches on the
 * button; the keys round it get theirs.
 */
internal class FloatingButton(
    private val context: Context,
    private val host: Host,
) {

    /** What the button asks of the keyboard. */
    interface Host {
        /** Runs [action]; never [FloatingAction.Menu], [FloatingAction.Hide] or [FloatingAction.None]. */
        fun runFloatingAction(action: FloatingAction)

        /** A drag ended here, as fractions of the room the screen leaves the button. */
        fun floatingButtonMoved(xFraction: Float, yFraction: Float)

        /** Put away until the keyboard next opens or closes: a drop on the target, or the Hide action. */
        fun floatingButtonDismissed()

        /** The quick menu opened or closed, so Back can close it while the keyboard has the key (#938). */
        fun floatingMenuChanged(open: Boolean) {}

        /** The keyboard's window, which a button over the keys is a panel of (#926); null while it has none. */
        fun keyboardWindowToken(): IBinder? = null
    }

    private var setting = SummonButtonSettings()
    private var keyboardUp = false
    private var xFraction = 1f
    private var yFraction = 0.6f

    /** The docked keyboard's top edge on screen, which the button stays above; null while it is down. */
    private var ceilingPx: Int? = null

    /** Faded and, with [SummonButtonSettings.tuck], half off the edge. */
    private var idle = false

    /**
     * The windows up now are panels of the keyboard's window, over the keys
     * (#926), rather than overlays. Changes only while none are up.
     */
    private var onPanel = false

    /**
     * Where a panel's x and y were measured to count from on screen (#926):
     * null until the button has been put up once as a panel, and then read
     * off where Android really drew it. See [origin].
     */
    private var panelOrigin: IntArray? = null

    /** Corrections [checkPanelOrigin] has made since the button went up; a few at most. */
    private var originChecks = 0

    /** The category whose ring is open (#923), or null for the quick menu's own. */
    private var ring: FloatingCategory? = null

    /** The open category was reached from the quick menu, so Back goes back to it rather than closing. */
    private var ringFromMenu = false

    private val lifecycle = KeyboardViewLifecycleOwner().apply { onCreate() }
    private val face = FloatingFaceState()
    private val menu = FloatingMenuState()
    private val target = FloatingDismissState()
    private val flickGuide = FloatingFlickGuideState()

    /** The swipe guide's window (#1270), while a swipe from the fixed button is under way. */
    private var flickGuideView: ComposeView? = null

    private var button: TouchHost? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var menuView: android.view.View? = null
    private var targetView: ComposeView? = null

    /** The drop target's centre on screen, once its window has been laid out. */
    private var targetCenter: Offset? = null
    private var xAnimator: ValueAnimator? = null

    /**
     * A context Android lets draw an overlay window. From API 30 a window
     * context of the overlay type, so the window manager and the screen size
     * it reports belong to a display; before that the application context,
     * which is what overlay windows always used.
     */
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

    private val overlayManager: WindowManager
        get() = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The keyboard's own window manager, the one its panels go through (#926). */
    private val panelManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Where the button, its menu and its drop target go: over the keys or over the apps. */
    private val windowManager: WindowManager
        get() = if (onPanel) panelManager else overlayManager

    /** The context those windows' views are made with, which has to match the window manager's. */
    private val viewContext: Context
        get() = if (onPanel) context else windowContext

    private val density: Float get() = context.resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density).roundToInt()

    /** The disc's window: the disc and its shadow room on each side. */
    private val windowPx: Int get() = dp((setting.sizeDp + 2 * FLOATING_SHADOW_ROOM_DP).toFloat())

    /**
     * The button's window across: the disc's, or for the edge pill (#1137)
     * its touch area, which reaches in from the edge as far as was set.
     */
    private val windowW: Int get() = if (setting.edgePill) dp(setting.pillTouchWidthDp.toFloat()) else windowPx

    /** The button's window top to bottom: the disc's, or the pill's length. */
    private val windowH: Int get() = if (setting.edgePill) dp(setting.pillLengthDp.toFloat()) else windowPx

    /** Room in the window round what is drawn, for the disc's shadow; a pill has none below it. */
    private val shadowRoomPx: Int get() = if (setting.edgePill) 0 else dp(FLOATING_SHADOW_ROOM_DP.toFloat())

    val showing: Boolean get() = button != null

    /**
     * Takes the settings and whether the keyboard is up, which decides what
     * the keyboard action's glyph is. Cheap when nothing changed: the face is
     * snapshot state, and only a change of size moves the window.
     */
    fun update(setting: SummonButtonSettings, keyboardUp: Boolean) {
        // Called for every field the keyboard meets; only a real change wakes the button.
        val panel = setting.floatsOverKeyboard && keyboardUp && host.keyboardWindowToken() != null
        if (setting == this.setting && keyboardUp == this.keyboardUp && panel == onPanel && face.label.isNotEmpty()) return
        // Over the keys or over the apps is a different kind of window: take
        // these down, and the caller's show() puts the button up as the other.
        if (panel != onPanel) {
            hide()
            onPanel = panel
            // The last corner measured for this screen shape, so the button
            // lands where it belongs from the first frame instead of jumping
            // there once it is measured (#1238).
            panelOrigin = if (panel) knownPanelOrigin() else null
        }
        val resized = setting.sizeDp != this.setting.sizeDp ||
            setting.edgePill != this.setting.edgePill ||
            setting.pillTouchWidthDp != this.setting.pillTouchWidthDp ||
            setting.pillLengthDp != this.setting.pillLengthDp
        this.setting = setting
        this.keyboardUp = keyboardUp
        val tap = setting.tapFor(keyboardUp)
        face.icon = floatingActionIcon(tap, keyboardUp, setting.categories)
        face.image = appIcon(tap, setting.sizeDp)
        face.label = name(tap)
        face.style = setting.style
        face.shape = setting.shape
        face.roundness = setting.cornerRoundness
        face.sizeDp = setting.sizeDp
        face.pill = setting.edgePill
        face.pillThicknessDp = setting.pillThicknessDp
        face.pillLengthDp = setting.pillLengthDp
        face.pillOnLeft = onLeft
        menu.style = setting.style
        menu.shape = setting.shape
        menu.animation = setting.menuAnimation
        menu.speed = setting.animationSpeed
        menu.labels = setting.menuLabels
        menu.buttonSizeDp = setting.sizeDp
        menu.itemSizeDp = setting.menuItemSizeDp
        menu.roundness = setting.cornerRoundness
        menu.holdFloats = setting.menuHoldFloats
        val view = button ?: return
        view.contentDescription = face.label
        // A change is worth seeing: the button wakes, then fades again as set.
        idle = false
        if (appeared) view.animate().alpha(setting.opacity).setDuration(FADE_IN_MS).start()
        if (resized) {
            buttonParams?.let {
                it.width = windowW
                it.height = windowH
            }
            place()
        } else if (!view.dragging && menuView == null && buttonParams?.x != homeX()) {
            animateX(homeX(), overshoot = false)
        }
        scheduleIdle()
    }

    /**
     * Puts the button on screen at the given resting place, or moves it there
     * when it already is. Does nothing without the overlay grant, and nothing
     * when Android refuses the window anyway (a grant revoked a moment ago).
     */
    fun show(xFraction: Float, yFraction: Float) {
        val moved = xFraction != this.xFraction || yFraction != this.yFraction
        this.xFraction = xFraction
        this.yFraction = yFraction
        val current = button
        if (current != null) {
            if (moved && !current.dragging) place()
            return
        }
        if (!onPanel && !Settings.canDrawOverlays(context)) return
        val view = TouchHost(viewContext)
        val layout = overlayParams(windowW, windowH, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard floating button"
        }
        buttonParams = layout
        idle = false
        originChecks = 0
        positionFromFractions(layout)
        lifecycle.attachTo(view)
        lifecycle.onResume()
        val added = runCatching { windowManager.addView(view, layout) }.isSuccess
        if (added) {
            button = view
            view.alpha = 0f
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            appeared = false
            // Over the keys, unseen until the corner it counts from is known to
            // be right (#1238): where Android puts a panel is measured, not
            // assumed, and a button seen at the guess and then moved is the jump.
            if (onPanel) view.post { checkPanelOrigin(view) } else appear(view)
            scheduleIdle()
        } else {
            buttonParams = null
            lifecycle.onStop()
        }
    }

    /** The button has had its appearing animation since it was put up. */
    private var appeared = false

    private fun appear(view: TouchHost) {
        if (appeared || button !== view) return
        appeared = true
        view.animate().alpha(setting.opacity).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.6f)).setDuration(paced(APPEAR_MS)).start()
    }

    /**
     * Puts the button back on top of an overlay window opened after it.
     * Android stacks windows of one type in the order they were added, so a
     * floating box opened while the button was up covered the button once
     * dragged over it, and the button could not be reached through it
     * (Telegram 10316). Not over the keys, where the button is a panel of the
     * keyboard's window, nor mid-drag or with its menu open, which the
     * re-add would cut short.
     */
    fun bringToFront() {
        if (onPanel || menuView != null) return
        val view = button ?: return
        val layout = buttonParams ?: return
        if (view.dragging || view.touching) return
        runCatching { windowManager.removeViewImmediate(view) }
        if (runCatching { windowManager.addView(view, layout) }.isFailure) {
            button = null
            buttonParams = null
            lifecycle.onStop()
        }
    }

    /** Takes the button, and its menu and target, off screen. Safe to call when it is not there. */
    fun hide() {
        cancelPick()
        cancelDwell()
        hideFlickGuide()
        removeMenuWindow()
        removeTargetWindow()
        val current = button ?: return
        button = null
        buttonParams = null
        xAnimator?.cancel()
        yAnimator?.cancel()
        current.cancelPress()
        current.animate().cancel()
        runCatching { windowManager.removeViewImmediate(current) }
        lifecycle.onStop()
    }

    /**
     * Keeps the button above [topPx], the docked keyboard's top edge on screen,
     * or lets it go back to where it was dragged when null. The resting place
     * itself is untouched: this moves the button, it does not remember it.
     */
    fun keepAbove(topPx: Int?) {
        if (topPx == ceilingPx) return
        ceilingPx = topPx
        val current = button ?: return
        if (current.dragging) return
        // Over the keys the button is a panel of the keyboard's window, which
        // grows and shrinks from the bottom of the screen as the toolbar, a
        // tool or the number row come and go. Where Android counts the panel
        // from can move with it, so it is measured again rather than leaving
        // the button wherever the window's last size put it (#1238).
        if (onPanel) {
            originChecks = 0
            // A fixed button is out of sight until it is back where it was
            // put (#926), so a keyboard growing or shrinking under it never
            // shows it wandering while its corner is measured again.
            if (setting.docked && appeared) {
                current.animate().cancel()
                current.alpha = 0f
                appeared = false
            }
            place()
            return
        }
        // Over the apps the button is lifted clear of the keyboard, or let
        // back down to where it was put, and glides there with the keyboard
        // rather than jumping (#1238).
        glideY(current)
    }

    private var yAnimator: ValueAnimator? = null

    /** Moves [view] to the height [positionFromFractions] gives it now, over [LIFT_MS]. */
    private fun glideY(view: TouchHost) {
        val layout = buttonParams ?: return
        val from = layout.y
        positionFromFractions(layout)
        val to = layout.y
        layout.y = from
        yAnimator?.cancel()
        if (from == to || setting.animationSpeed <= 0f) {
            layout.y = to
            runCatching { windowManager.updateViewLayout(view, layout) }
            return
        }
        yAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = paced(LIFT_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (button !== view || view.dragging) return@addUpdateListener
                layout.y = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, layout) }
            }
            start()
        }
    }

    /** The screen changed shape (rotation, fold): put the button back on it, and shut a menu laid out for the old one. */
    fun relayout() {
        closeMenu()
        if (button != null) place()
    }

    // ---- placing ----

    private fun place() {
        val layout = buttonParams ?: return
        val current = button ?: return
        xAnimator?.cancel()
        yAnimator?.cancel()
        positionFromFractions(layout)
        runCatching { windowManager.updateViewLayout(current, layout) }
        if (onPanel) current.post { checkPanelOrigin(current) }
    }

    /**
     * Reads where Android drew the button's panel and, when that is not where
     * [origin] said x and y count from, keeps the measured corner and puts
     * the button back where it belongs (#926).
     *
     * Measured rather than assumed because the two guesses were both wrong
     * somewhere: counting from the screen left the button off by the keyboard
     * window's offset on one phone, and counting from the keyboard's window
     * (0.6.5) pushed it off the top of the screen on another, since a panel
     * laid out in the screen counts from the screen. The view's own scale and
     * shift, mid-appearance, are taken back out of the reading. A few tries at
     * most, so a window Android will not put where asked cannot loop.
     */
    private fun checkPanelOrigin(view: TouchHost) {
        if (!onPanel || button !== view || view.dragging) return
        if (originChecks >= MAX_ORIGIN_CHECKS) {
            appear(view)
            return
        }
        val layout = buttonParams ?: return
        if (!view.isAttachedToWindow) return
        val at = IntArray(2).also(view::getLocationOnScreen)
        val drawnX = at[0] - (view.pivotX * (1f - view.scaleX) + view.translationX).roundToInt()
        val drawnY = at[1] - (view.pivotY * (1f - view.scaleY) + view.translationY).roundToInt()
        val measured = intArrayOf(drawnX - layout.x, drawnY - layout.y)
        if (measured.contentEquals(origin())) {
            rememberPanelOrigin(measured)
            appear(view)
            return
        }
        originChecks++
        panelOrigin = measured
        rememberPanelOrigin(measured)
        place()
    }

    /** The panel corner measured last, and the screen size it was measured on (#1238). */
    private var measuredPanelOrigin: Pair<Pair<Int, Int>, IntArray>? = null

    private fun screenShape(): Pair<Int, Int> =
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun rememberPanelOrigin(origin: IntArray) {
        measuredPanelOrigin = screenShape() to origin.copyOf()
    }

    private fun knownPanelOrigin(): IntArray? =
        measuredPanelOrigin?.takeIf { it.first == screenShape() }?.second?.copyOf()

    private fun positionFromFractions(layout: WindowManager.LayoutParams) {
        val (roomX, roomY) = room()
        face.pillOnLeft = onLeft
        layout.x = if (idle && setting.tucks) tuckedX() else homeX()
        val rest = (yFraction.coerceIn(0f, 1f) * roomY).roundToInt()
        val ceiling = ceiling()
        layout.y = (if (ceiling != null && rest > ceiling) ceiling.coerceAtLeast(0) else rest) - origin()[1]
        if (roomX == 0) layout.x = -origin()[0]
    }

    /**
     * Where the windows' x and y count from on screen. The screen's corner for
     * an overlay, and at first for a panel over the keys too, since a panel
     * laid out in the screen is placed in it; then, for a panel, the corner
     * [checkPanelOrigin] measured (#926). A drag saves the place in the
     * screen's terms either way, so it is the same place once the keyboard
     * goes.
     */
    private fun origin(): IntArray = if (onPanel) panelOrigin ?: NO_OFFSET else NO_OFFSET

    private fun restX(): Int = (xFraction.coerceIn(0f, 1f) * room().first).roundToInt() - origin()[0]

    /**
     * Where the button sits while awake: its resting place, or half off its
     * edge for a fixed button that tucks (#1370), which stays there through
     * every touch rather than sliding out and back, so it is always the same
     * target in the same place.
     */
    private fun homeX(): Int = if (setting.staysTucked) tuckedX() else restX()

    /**
     * The lowest the button's window may sit, clear of a docked keyboard; null
     * over the keys (#926) or with none. The gap counts from the disc, not
     * the window: the window's shadow room below the disc can sit behind the
     * keyboard, which is drawn over it. Counted from the window, the room and
     * the gap together left the button floating well clear of the keys (#1126).
     */
    private fun ceiling(): Int? =
        // Fixed in place, it is never lifted (#926).
        if (onPanel || setting.docked) null else ceilingPx?.let { it - windowH + shadowRoomPx - dp(KEYBOARD_GAP_DP) }

    private val onLeft: Boolean get() = xFraction < 0.5f

    /** Half the disc off the side it rests on. */
    private fun tuckedX(): Int {
        val hidden = dp(FLOATING_SHADOW_ROOM_DP + setting.sizeDp / 2f)
        return (if (onLeft) -hidden else room().first + hidden) - origin()[0]
    }

    /** How far the button's top-left corner can travel on each axis. */
    private fun room(): Pair<Int, Int> {
        val (width, height) = screenSize()
        return (width - windowW).coerceAtLeast(0) to (height - windowH).coerceAtLeast(0)
    }

    /**
     * The screen's size, read the same way over the keys and over the apps
     * (#926): through the overlay's window context either way. Read through
     * the keyboard's own window manager while the button was a panel, the
     * room a place's fractions are measured in could differ from the
     * overlay's, and the same saved place came out somewhere else.
     */
    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = overlayManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            overlayManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private fun animateX(to: Int, overshoot: Boolean, then: () -> Unit = {}) {
        val layout = buttonParams ?: return
        val view = button ?: return
        xAnimator?.cancel()
        if (layout.x == to) {
            then()
            return
        }
        xAnimator = ValueAnimator.ofInt(layout.x, to).apply {
            duration = paced(SNAP_MS)
            interpolator = if (overshoot) OvershootInterpolator(0.9f) else DecelerateInterpolator()
            addUpdateListener {
                if (button !== view) return@addUpdateListener
                layout.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, layout) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) then()
                }
            })
            start()
        }
    }

    /**
     * A window of the button's: an overlay, or with [panel] a panel of the
     * keyboard's window (#926). Laid out in the screen either way, so the
     * same x and y put it in the same place.
     */
    private fun overlayParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        panel: Boolean = onPanel,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // A tucked button sits half off the screen's edge.
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val type = if (panel) WindowManager.LayoutParams.TYPE_APPLICATION_PANEL else overlayType()
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            if (panel) token = host.keyboardWindowToken()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ---- idle ----

    private val idleRunnable = Runnable { fadeOut() }

    private fun scheduleIdle() {
        val view = button ?: return
        view.removeCallbacks(idleRunnable)
        if (setting.idleFadeSeconds <= 0 || menuView != null || view.touching) return
        view.postDelayed(idleRunnable, setting.idleFadeSeconds * 1000L)
    }

    private fun fadeOut() {
        val view = button ?: return
        if (setting.idleFadeSeconds <= 0) return
        idle = true
        view.animate().alpha(setting.idleOpacity).setDuration(FADE_OUT_MS).start()
        if (setting.tucks && !view.dragging) animateX(tuckedX(), overshoot = false)
    }

    /** Back to full strength, and out of the edge, for a touch or an opened menu. */
    private fun wake(snap: Boolean) {
        val view = button ?: return
        view.removeCallbacks(idleRunnable)
        view.animate().alpha(setting.opacity).setDuration(FADE_IN_MS).start()
        if (!idle) return
        idle = false
        // Fixed and tucked (#1370): it brightens where it is, and stays tucked.
        if (!setting.tucks || setting.staysTucked) return
        if (snap) {
            xAnimator?.cancel()
            buttonParams?.let {
                it.x = restX()
                runCatching { windowManager.updateViewLayout(view, it) }
            }
        } else {
            animateX(restX(), overshoot = true)
        }
    }

    // ---- actions ----

    /** Runs what a gesture is bound to, the ones the button answers itself included. */
    private fun fire(action: FloatingAction) {
        when (action) {
            FloatingAction.None -> Unit
            FloatingAction.Menu -> openMenu(fromPress = false)
            FloatingAction.Hide -> host.floatingButtonDismissed()
            is FloatingAction.Category -> setting.categoryOf(action)?.let { openMenu(fromPress = false, category = it) }
            else -> host.runFloatingAction(action)
        }
    }

    /** What [action] is called on the button, in its rings and to a screen reader. */
    private fun name(action: FloatingAction): String =
        floatingActionName(context, action, keyboardUp, setting.categories)

    /** An app slot's own icon (#922), for a disc of [sizeDp]; null for the rest, or an app that is gone. */
    private fun appIcon(action: FloatingAction, sizeDp: Int): ImageBitmap? = when (action) {
        is FloatingAction.App -> FloatingAppIcons.icon(context, action, dp(sizeDp.toFloat()))
        // A shortcut button's own picture (#1213); a switch or a page has none.
        is FloatingAction.Custom -> com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.byId(action.id)
            ?.let { com.wasimaster.wmkeyboard.ime.ui.CustomButtonFaces.icon(context, it, dp(sizeDp.toFloat())) }
        // A category wearing an icon out of an installed icon pack (#1275).
        is FloatingAction.Category -> setting.categoryOf(action)?.icon
            ?.let(com.wasimaster.wmkeyboard.core.icons.SystemIconPacks::parseSource)
            ?.let { (pkg, name) ->
                com.wasimaster.wmkeyboard.core.icons.SystemIconPacks.drawable(context, pkg, name, dp(sizeDp.toFloat()))
                    ?.asImageBitmap()
            }
        else -> null
    }

    private fun haptic(kind: Int) {
        if (!setting.haptics) return
        button?.performHapticFeedback(kind)
    }

    /** [ms] at the speed set for the button's motion (#1138). */
    private fun paced(ms: Long): Long = (ms / setting.animationSpeed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(1L)

    private val confirmHaptic: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }

    // ---- quick menu ----

    /**
     * Fans the quick menu out round the button, or with [category] that
     * category's ring (#923). [fromPress] is a hold still on the button: its
     * finger picks a slot by sliding onto it, and the menu stays open for a
     * tap if it lets go without having gone anywhere.
     */
    private fun openMenu(fromPress: Boolean, category: FloatingCategory? = null) {
        val view = button ?: return
        val slots = category?.items ?: setting.menu
        if (slots.isEmpty()) {
            val empty = if (category != null) R.string.ime_floating_category_empty else R.string.ime_floating_menu_empty
            Toast.makeText(context, empty, Toast.LENGTH_SHORT).show()
            return
        }
        wake(snap = true)
        view.removeCallbacks(idleRunnable)
        ring = category
        ringFromMenu = false
        layOutRing(slots)
        menu.canGoBack = false
        menu.hovered = null
        menuFromPress = fromPress
        haptic(HapticFeedbackConstants.LONG_PRESS)
        val existing = menuView
        if (existing != null) {
            menu.open = true
            return
        }
        val compose = ComposeView(viewContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingMenuContent(
                    state = menu,
                    onPick = ::pick,
                    onClose = ::closeMenu,
                    onBack = ::backFromCategory,
                    onClosed = { if (!menu.open) removeMenuWindow() },
                    onFloat = ::pickFloating,
                )
            }
        }
        // Back closes the menu rather than reaching the app under it (#938).
        // With the keyboard up the key is the keyboard's first, and the
        // service asks [closeMenuFromBack]; with it down, the menu's window
        // takes focus to see the key, never as an input-method target, so the
        // field behind keeps its keyboard state.
        val content = object : FrameLayout(viewContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeMenuFromBack()
                return true
            }
        }.apply {
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        lifecycle.attachTo(content)
        val layout = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard floating menu"
            if (!keyboardUp) {
                flags = (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
        }
        if (runCatching { windowManager.addView(content, layout) }.isFailure) return
        menuView = content
        host.floatingMenuChanged(true)
        // Where the window really starts (a status bar or a cutout can move
        // it), so screen points land where they are meant to; open once known.
        content.post {
            val origin = IntArray(2)
            content.getLocationOnScreen(origin)
            if (menuView !== content) return@post
            menu.windowOrigin = Offset(origin[0].toFloat(), origin[1].toFloat())
            menu.open = true
        }
    }

    /**
     * Where rings come out from, on screen: the disc's middle. A pill (#1137)
     * has no disc: the ring, and the close disc in the middle of it, come out
     * from where a disc against that edge would sit, so neither is cut off.
     */
    private fun ringAnchor(view: TouchHost): Offset {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val (width, height) = screenSize()
        return if (setting.edgePill) {
            val inset = windowPx / 2f
            val y = (location[1] + windowH / 2f).coerceIn(inset, (height - inset).coerceAtLeast(inset))
            Offset(if (onLeft) inset else width - inset, y)
        } else {
            Offset(location[0] + windowPx / 2f, location[1] + windowPx / 2f)
        }
    }

    /** Puts [slots] round the button where the ring they make fits on screen. */
    private fun layOutRing(slots: List<FloatingAction>) {
        val view = button ?: return
        val (width, height) = screenSize()
        val anchor = ringAnchor(view)
        val labels = setting.menuLabels
        val item = setting.menuItemSizeDp
        val itemHalf = item * density / 2f
        menu.anchor = anchor
        menu.floatArmed = false
        armedByHold = false
        menu.centers = floatingRings(
            count = slots.size,
            anchor = anchor,
            width = width.toFloat(),
            height = height.toFloat(),
            radius = (setting.sizeDp / 2f + setting.menuGapDp + item / 2f) * density,
            spacing = floatingSlotSpacingDp(item, labels) * density,
            margin = itemHalf + MENU_MARGIN_DP * density +
                if (labels) MENU_LABEL_ROOM_DP * density else 0f,
        )
        val level = ring?.id ?: "menu"
        menu.items = slots.mapIndexed { i, action ->
            FloatingMenuSlot(
                key = "$level:$i:${action.token}",
                action = action,
                icon = floatingActionIcon(action, keyboardUp, setting.categories),
                label = name(action),
                image = appIcon(action, item),
                active = isOn(action),
                floatLabel = action.floatingVersion?.let(::name),
            )
        }
    }

    /** A phone switch's slot shows whether the switch is on (#1364); the rest are never "on". */
    private fun isOn(action: FloatingAction): Boolean =
        action is FloatingAction.Device && DeviceControls.isOn(context, action.control) == true

    /**
     * Swaps the open quick menu's ring for [category]'s (#923), the slots of
     * the one folding back as the other's come out, without closing the
     * window or letting a hold's finger go.
     */
    private fun openCategory(category: FloatingCategory) {
        if (menuView == null) return
        if (category.items.isEmpty()) {
            Toast.makeText(context, R.string.ime_floating_category_empty, Toast.LENGTH_SHORT).show()
            return
        }
        cancelDwell()
        ringFromMenu = ring == null
        ring = category
        menu.hovered = null
        layOutRing(category.items)
        menu.canGoBack = ringFromMenu
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Back to the quick menu's own ring from a category opened from it; closes it all otherwise. */
    private fun backFromCategory() {
        if (ring == null || !ringFromMenu) {
            closeMenu()
            return
        }
        cancelDwell()
        ring = null
        ringFromMenu = false
        menu.hovered = null
        menu.canGoBack = false
        layOutRing(setting.menu)
    }

    private var menuFromPress = false

    private fun pick(index: Int) {
        val action = menu.items.getOrNull(index)?.action ?: return
        val category = setting.categoryOf(action)
        if (category != null) {
            openCategory(category)
            return
        }
        haptic(confirmHaptic)
        closeMenu()
        fire(action)
    }

    /**
     * Slot [index]'s tool in its floating box rather than in the keyboard
     * (#1401): a swipe off the slot, or a hold on it with the setting on.
     * A slot with no box version runs as a pick.
     */
    private fun pickFloating(index: Int) {
        val action = menu.items.getOrNull(index)?.action ?: return
        val floating = action.floatingVersion ?: return pick(index)
        haptic(confirmHaptic)
        closeMenu()
        fire(floating)
    }

    private fun closeMenu() {
        menuFromPress = false
        cancelDwell()
        if (menuView == null) return
        menu.hovered = null
        menu.floatArmed = false
        armedByHold = false
        if (menu.open) menu.open = false else removeMenuWindow()
        scheduleIdle()
    }

    /**
     * Back while a ring is open (#938): one ring back out of a category,
     * else closes the menu. True when there was one open to answer it.
     */
    fun closeMenuFromBack(): Boolean {
        if (menuView == null) return false
        backFromCategory()
        return true
    }

    private fun removeMenuWindow() {
        val view = menuView ?: return
        menuView = null
        ring = null
        ringFromMenu = false
        menu.canGoBack = false
        host.floatingMenuChanged(false)
        menu.open = false
        runCatching { windowManager.removeViewImmediate(view) }
        scheduleIdle()
    }

    // ---- a hold resting on a category ----

    /** The category slot a hold's finger is resting on, waiting out [CATEGORY_DWELL_MS]. */
    private var dwellSlot: Int? = null

    /** The hovered slot was set to open in its box by resting on it (#1401), not by sliding past it. */
    private var armedByHold = false

    private val dwell = Runnable {
        val index = dwellSlot ?: return@Runnable
        dwellSlot = null
        val action = menu.items.getOrNull(index)?.action ?: return@Runnable
        val category = setting.categoryOf(action)
        if (category != null) {
            openCategory(category)
        } else if (action.floatingVersion != null && menu.hovered == index) {
            // Held there with the setting on (#1401): letting go opens the box.
            menu.floatArmed = true
            armedByHold = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * A sliding finger came to rest on slot [index]: a category opens if it
     * stays a moment (#923), and with [SummonButtonSettings.menuHoldFloats]
     * a tool that floats is set to open in its box if it stays a long press
     * (#1401).
     */
    private fun dwellOn(index: Int?) {
        if (index == dwellSlot) return
        cancelDwell()
        val action = index?.let { menu.items.getOrNull(it)?.action } ?: return
        val wait = when {
            setting.categoryOf(action) != null -> CATEGORY_DWELL_MS
            setting.menuHoldFloats && action.floatingVersion != null -> ViewConfiguration.getLongPressTimeout().toLong()
            else -> return
        }
        dwellSlot = index
        button?.postDelayed(dwell, wait)
    }

    private fun cancelDwell() {
        dwellSlot = null
        button?.removeCallbacks(dwell)
    }

    // ---- the swipe guide (#1270) ----

    /**
     * Puts up the guide to the fixed button's swipes: each bound direction's
     * action where its swipe points, round the button, for the finger to see
     * what letting go will run before it does. A window that takes no
     * touches, over the keys or over the apps as the button is.
     */
    private fun showFlickGuide() {
        if (flickGuideView != null) return
        val view = button ?: return
        val flicks = setting.activeFlicks
        if (flicks.isEmpty()) return
        val anchor = ringAnchor(view)
        val (width, height) = screenSize()
        val item = setting.menuItemSizeDp
        val radius = (setting.sizeDp / 2f + setting.menuGapDp + item / 2f) * density
        val margin = (item / 2f + MENU_MARGIN_DP) * density
        flickGuide.slots = flicks.entries.sortedBy { it.key.ordinal }.map { (direction, action) ->
            val angle = Math.toRadians(direction.degrees.toDouble())
            FloatingFlickSlot(
                direction = direction,
                center = Offset(
                    (anchor.x + radius * cos(angle).toFloat()).coerceIn(margin, (width - margin).coerceAtLeast(margin)),
                    (anchor.y + radius * sin(angle).toFloat()).coerceIn(margin, (height - margin).coerceAtLeast(margin)),
                ),
                icon = floatingActionIcon(action, keyboardUp, setting.categories),
                label = name(action),
                image = appIcon(action, item),
                active = isOn(action),
            )
        }
        flickGuide.anchor = anchor
        flickGuide.radius = radius
        flickGuide.style = setting.style
        flickGuide.shape = setting.shape
        flickGuide.roundness = setting.cornerRoundness
        flickGuide.itemSizeDp = item
        flickGuide.hovered = null
        flickGuide.shown = false
        val content = ComposeView(viewContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { FloatingFlickGuide(flickGuide) }
        }
        lifecycle.attachTo(content)
        val layout = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard floating swipe guide"
        }
        if (runCatching { windowManager.addView(content, layout) }.isFailure) return
        flickGuideView = content
        // Where the window really starts, as for the quick menu; it fades in once known.
        content.post {
            if (flickGuideView !== content) return@post
            val at = IntArray(2)
            content.getLocationOnScreen(at)
            flickGuide.windowOrigin = Offset(at[0].toFloat(), at[1].toFloat())
            flickGuide.shown = true
        }
    }

    private fun hideFlickGuide() {
        val view = flickGuideView ?: return
        flickGuideView = null
        flickGuide.shown = false
        flickGuide.hovered = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    // ---- drop target ----

    private fun showTarget() {
        target.armed = false
        targetView?.let {
            it.removeCallbacks(removeTarget)
            target.shown = true
            return
        }
        val size = dp(TARGET_WINDOW_DP)
        val content = ComposeView(viewContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { FloatingDismissTarget(target) }
        }
        lifecycle.attachTo(content)
        val layout = overlayParams(size, size, touchable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(TARGET_LIFT_DP)
            title = "WM Keyboard floating button target"
        }
        if (runCatching { windowManager.addView(content, layout) }.isFailure) return
        targetView = content
        content.post {
            val at = IntArray(2)
            content.getLocationOnScreen(at)
            if (targetView !== content) return@post
            targetCenter = Offset(at[0] + size / 2f, at[1] + size / 2f)
            target.shown = true
        }
    }

    private val removeTarget = Runnable { removeTargetWindow() }

    private fun hideTarget() {
        val view = targetView ?: return
        target.shown = false
        target.armed = false
        view.removeCallbacks(removeTarget)
        view.postDelayed(removeTarget, FADE_IN_MS + 40)
    }

    private fun removeTargetWindow() {
        val view = targetView ?: return
        targetView = null
        targetCenter = null
        target.shown = false
        target.armed = false
        view.removeCallbacks(removeTarget)
        runCatching { windowManager.removeViewImmediate(view) }
    }

    // ---- picking a point: Look up's "tap a word" ----

    private var pickView: PickHost? = null
    private val pickTimeout = Runnable { cancelPick() }

    /**
     * Covers the screen with a see-through window for one tap (#765): the
     * floating button's Look up, waiting for the word. The tap's point on
     * screen goes to [onTap] once the window is gone, so whatever reads the
     * screen next reads the app and not this. A tap is all it takes; the
     * timeout, or the button's next press, gives up.
     *
     * It takes the tap itself rather than letting it through, so it is never
     * the pass-through overlay Android 12 drops touches under, and it lasts
     * only until that tap.
     */
    fun pickPoint(onTap: (x: Int, y: Int) -> Unit) {
        cancelPick()
        if (!Settings.canDrawOverlays(context)) return
        val view = PickHost(windowContext, onTap)
        lifecycle.attachTo(view)
        lifecycle.onResume()
        // Always an overlay, even with the button over the keys: it is the app's word it waits for.
        val layout = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = true,
            panel = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard look up"
        }
        if (runCatching { overlayManager.addView(view, layout) }.isFailure) {
            if (button == null) lifecycle.onStop()
            return
        }
        pickView = view
        view.postDelayed(pickTimeout, PICK_TIMEOUT_MS)
    }

    private fun cancelPick() {
        val view = pickView ?: return
        pickView = null
        view.removeCallbacks(pickTimeout)
        runCatching { overlayManager.removeViewImmediate(view) }
        if (button == null) lifecycle.onStop()
    }

    /** The pick window: a light wash and a hint at the top, and the tap it waits for. */
    @SuppressLint("ViewConstructor")
    private inner class PickHost(context: Context, private val onTap: (Int, Int) -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        init {
            isClickable = true
            isFocusable = false
            contentDescription = context.getString(R.string.ime_floating_pick_hint)
            addView(
                ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent { FloatingPickHint(context.getString(R.string.ime_floating_pick_hint)) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    // A drag is not a pick; it gives up, as a stray swipe would want.
                    val moved = abs(event.rawX - downX) > slop * 3 || abs(event.rawY - downY) > slop * 3
                    val x = downX.roundToInt()
                    val y = downY.roundToInt()
                    cancelPick()
                    if (!moved) {
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        onTap(x, y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelPick()
            }
            return true
        }
    }

    // ---- the button's own view ----

    /** The fixed button's swipes as accessibility actions (#1171), one per [FlickDirection], in its order. */
    private val flickActions = intArrayOf(
        R.id.floating_button_action_flick_0,
        R.id.floating_button_action_flick_1,
        R.id.floating_button_action_flick_2,
        R.id.floating_button_action_flick_3,
        R.id.floating_button_action_flick_4,
        R.id.floating_button_action_flick_5,
        R.id.floating_button_action_flick_6,
        R.id.floating_button_action_flick_7,
    )

    private val slotActions = intArrayOf(
        R.id.floating_button_action_slot_0,
        R.id.floating_button_action_slot_1,
        R.id.floating_button_action_slot_2,
        R.id.floating_button_action_slot_3,
        R.id.floating_button_action_slot_4,
        R.id.floating_button_action_slot_5,
        R.id.floating_button_action_slot_6,
        R.id.floating_button_action_slot_7,
        R.id.floating_button_action_slot_8,
        R.id.floating_button_action_slot_9,
        R.id.floating_button_action_slot_10,
        R.id.floating_button_action_slot_11,
        R.id.floating_button_action_slot_12,
        R.id.floating_button_action_slot_13,
        R.id.floating_button_action_slot_14,
        R.id.floating_button_action_slot_15,
    )

    /**
     * The button: draws [face] and turns touches into the gestures. A plain
     * view under the composition, so the gesture rules (slop, hold timeout,
     * the double-tap window, the slide onto a menu slot) are the platform's
     * own and the composition only ever draws.
     */
    @SuppressLint("ViewConstructor")
    private inner class TouchHost(context: Context) : FrameLayout(context) {

        var dragging = false
            private set
        var touching = false
            private set

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var held = false
        private var travelled = false
        private var pendingTap = false

        /** A swipe from the fixed button (#1171) is under way, and the direction it points at so far. */
        private var flicking = false
        private var flickDirection: FlickDirection? = null

        private val longPress = Runnable {
            held = true
            face.pressed = false
            val hold = setting.hold
            if (hold == FloatingAction.Menu) {
                openMenu(fromPress = true)
            } else {
                haptic(HapticFeedbackConstants.LONG_PRESS)
                fire(hold)
            }
        }

        private val singleTap = Runnable {
            pendingTap = false
            fire(setting.tapFor(keyboardUp))
        }

        init {
            isClickable = true
            isFocusable = false
            contentDescription = face.label
            addView(
                ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        if (face.pill) {
                            // Flat against its edge; the rest of the window is touch area (#1137).
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = if (face.pillOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
                            ) {
                                FloatingEdgePill(
                                    style = face.style,
                                    thicknessDp = face.pillThicknessDp,
                                    lengthDp = face.pillLengthDp,
                                    onLeft = face.pillOnLeft,
                                    pressed = face.pressed,
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                // A swipe from the fixed button shows what letting go will run (#1171).
                                val flick = face.flickIcon
                                FloatingButtonFace(
                                    icon = flick ?: face.icon,
                                    image = if (flick != null) null else face.image,
                                    style = face.style,
                                    shape = face.shape,
                                    sizeDp = face.sizeDp,
                                    pressed = face.pressed,
                                    roundness = face.roundness,
                                )
                            }
                        }
                    }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        fun cancelPress() {
            removeCallbacks(longPress)
            removeCallbacks(singleTap)
            removeCallbacks(idleRunnable)
            pendingTap = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val layout = buttonParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    // A finger on it stops a lift mid-glide where it is (#1238).
                    yAnimator?.cancel()
                    wake(snap = true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = layout.x
                    downY = layout.y
                    dragging = false
                    held = false
                    travelled = false
                    flicking = false
                    flickDirection = null
                    face.pressed = true
                    if (setting.hold != FloatingAction.None) {
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > slop || abs(dy) > slop) travelled = true
                    if (held) {
                        if (menuFromPress) hover(event.rawX, event.rawY)
                        return true
                    }
                    // Fixed in place (#1171): the finger swipes rather than drags.
                    if (setting.docked) {
                        if (travelled) flick(dx, dy)
                        return true
                    }
                    if (!dragging && travelled) {
                        dragging = true
                        face.pressed = false
                        removeCallbacks(longPress)
                        showTarget()
                    }
                    if (dragging) drag(layout, dx, dy, event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    face.pressed = false
                    removeCallbacks(longPress)
                    when {
                        held -> releaseHold()
                        flicking -> releaseFlick()
                        dragging -> endDrag(layout)
                        else -> tap()
                    }
                    dragging = false
                    scheduleIdle()
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    face.pressed = false
                    removeCallbacks(longPress)
                    if (dragging) {
                        target.armed = false
                        endDrag(layout)
                    }
                    if (held && menuFromPress) menu.hovered = null
                    if (flicking) endFlick()
                    dragging = false
                    scheduleIdle()
                }
            }
            return true
        }

        /**
         * A tap, or the second of a double tap. Both read the keyboard-up
         * bindings while the keyboard is up (#626), so a double tap bound only
         * there leaves a tap with the keyboard down as quick as one with no
         * double tap at all.
         */
        private fun tap() {
            val doubleTap = setting.doubleTapFor(keyboardUp)
            if (doubleTap == FloatingAction.None) {
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                fire(setting.tapFor(keyboardUp))
                return
            }
            if (pendingTap) {
                removeCallbacks(singleTap)
                pendingTap = false
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                fire(doubleTap)
            } else {
                pendingTap = true
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong())
            }
        }

        /**
         * The finger has left the fixed button (#1171): once it has gone far
         * enough to mean it, the direction it points at is the swipe, and the
         * button shows that direction's action. A tick each time it changes.
         */
        private fun flick(dx: Float, dy: Float) {
            if (!flicking) {
                flicking = true
                face.pressed = false
                removeCallbacks(longPress)
                // What each direction runs, round the button (#1270).
                showFlickGuide()
            }
            val far = dx * dx + dy * dy >= (FLICK_MIN_DP * density).let { it * it }
            val direction = if (far) flickDirectionOf(dx, dy, setting.activeFlicks.keys) else null
            if (direction == flickDirection) return
            flickDirection = direction
            flickGuide.hovered = direction
            face.flickIcon = direction?.let { setting.activeFlicks[it] }?.let {
                floatingActionIcon(it, keyboardUp, setting.categories)
            }
            if (direction != null) haptic(HapticFeedbackConstants.CLOCK_TICK)
        }

        /** The swipe let go: its direction's action runs, or nothing for one near no bound direction. */
        private fun releaseFlick() {
            val action = flickDirection?.let { setting.activeFlicks[it] }
            endFlick()
            if (action == null) return
            haptic(confirmHaptic)
            fire(action)
        }

        private fun endFlick() {
            flicking = false
            flickDirection = null
            face.flickIcon = null
            hideFlickGuide()
        }

        /**
         * The hold's finger is on its way to a slot. Carried on out past a
         * slot whose tool floats, away from the button, it keeps that slot
         * and sets it to open in its box (#1401), a badge on the slot saying
         * so; back onto a slot, it is a plain pick again.
         */
        private fun hover(rawX: Float, rawY: Float) {
            val point = Offset(rawX, rawY)
            val reach = (setting.menuItemSizeDp / 2f + HOVER_SLACK_DP) * density
            var slot = floatingSlotAt(point, menu.centers, reach)
            // Still on the slot a hold set to float: it stays set.
            var floating = slot != null && slot == menu.hovered && armedByHold
            if (slot == null) {
                val last = menu.hovered
                if (last != null && pastSlot(point, last, reach)) {
                    slot = last
                    floating = true
                }
            }
            if (slot != menu.hovered) {
                menu.hovered = slot
                armedByHold = false
                if (slot != null) haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (floating != menu.floatArmed) {
                menu.floatArmed = floating
                if (floating) haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (!floating) dwellOn(slot) else cancelDwell()
        }

        /**
         * Whether [point] is beyond slot [index] as seen from the button,
         * within its width either side and no further than [FLOAT_REACH_DP]:
         * where a finger that slid onto the slot and kept going ends up.
         * Only for a slot whose tool floats.
         */
        private fun pastSlot(point: Offset, index: Int, reach: Float): Boolean {
            if (menu.items.getOrNull(index)?.floatLabel == null) return false
            val center = menu.centers.getOrNull(index) ?: return false
            val out = center - menu.anchor
            val length = out.getDistance().takeIf { it > 0f } ?: return false
            val along = (point - menu.anchor).let { (it.x * out.x + it.y * out.y) / length }
            val across = abs(((point - menu.anchor).x * out.y - (point - menu.anchor).y * out.x) / length)
            return along > length && along - length <= FLOAT_REACH_DP * density && across <= reach
        }

        /**
         * The hold let go. On a slot it runs it; having gone somewhere that is
         * not a slot it closes the menu; never having moved it leaves the menu
         * open for a tap.
         */
        private fun releaseHold() {
            if (!menuFromPress) return
            val slot = menu.hovered
            val floating = menu.floatArmed
            menuFromPress = false
            when {
                slot != null && floating -> pickFloating(slot)
                slot != null -> pick(slot)
                travelled -> closeMenu()
                else -> Unit
            }
        }

        private fun drag(layout: WindowManager.LayoutParams, dx: Float, dy: Float, rawX: Float, rawY: Float) {
            val center = targetCenter
            val snapReach = TARGET_SNAP_DP * density
            val armed = center != null && (Offset(rawX, rawY) - center).getDistance() < snapReach
            if (armed != target.armed) {
                target.armed = armed
                if (armed) haptic(HapticFeedbackConstants.LONG_PRESS)
            }
            val (ox, oy) = origin().let { it[0] to it[1] }
            if (armed && center != null) {
                layout.x = (center.x - windowW / 2f).roundToInt() - ox
                layout.y = (center.y - windowH / 2f).roundToInt() - oy
            } else {
                val (roomX, roomY) = room()
                layout.x = (downX + dx.roundToInt()).coerceIn(-ox, roomX - ox)
                layout.y = (downY + dy.roundToInt()).coerceIn(-oy, roomY - oy)
            }
            runCatching { windowManager.updateViewLayout(this, layout) }
        }

        /**
         * Ends a drag: on the target the button goes away; anywhere else it
         * settles against the nearer side and is remembered there. A drop
         * behind the keyboard is pushed back above it, and remembered where
         * it was let go.
         */
        private fun endDrag(layout: WindowManager.LayoutParams) {
            val dropped = target.armed
            hideTarget()
            if (dropped) {
                haptic(confirmHaptic)
                host.floatingButtonDismissed()
                return
            }
            val (roomX, roomY) = room()
            // Saved in the screen's terms, whichever window the button is.
            val (screenX, screenY) = origin().let { layout.x + it[0] to layout.y + it[1] }
            val left = screenX + windowW / 2 < (roomX + windowW) / 2
            xFraction = if (left) 0f else 1f
            face.pillOnLeft = left
            yFraction = if (roomY == 0) 0f else screenY.toFloat() / roomY
            val ceiling = ceiling()
            if (ceiling != null && layout.y > ceiling) {
                layout.y = ceiling.coerceAtLeast(0)
                runCatching { windowManager.updateViewLayout(this, layout) }
            }
            animateX(restX(), overshoot = true)
            host.floatingButtonMoved(xFraction, yFraction)
        }

        override fun performClick(): Boolean {
            super.performClick()
            fire(setting.tapFor(keyboardUp))
            return true
        }

        override fun performLongClick(): Boolean {
            super.performLongClick()
            fire(setting.hold)
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Button::class.java.name
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, face.label))
            if (setting.hold != FloatingAction.None) {
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, name(setting.hold)),
                )
            }
            val doubleTap = setting.doubleTapFor(keyboardUp)
            if (doubleTap != FloatingAction.None) {
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(R.id.floating_button_action_double_tap, name(doubleTap)),
                )
            }
            // Each swipe of the fixed button (#1171), since a swipe is no way in
            // for someone exploring by touch either.
            setting.activeFlicks.entries.forEach { (direction, action) ->
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(flickActions[direction.ordinal], name(action)))
            }
            // Every menu slot straight from the button: a slide is no way in
            // for someone exploring by touch. A category's slot opens its ring,
            // whose slots are then on screen to be found.
            if (FloatingAction.Menu in setting.gestureActions()) {
                setting.menu.take(slotActions.size).forEachIndexed { i, action ->
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(slotActions[i], name(action)))
                }
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == R.id.floating_button_action_double_tap) {
                fire(setting.doubleTapFor(keyboardUp))
                return true
            }
            val flick = flickActions.indexOf(action)
            if (flick >= 0) {
                setting.activeFlicks[FlickDirection.entries[flick]]?.let(::fire)
                return true
            }
            val slot = slotActions.indexOf(action)
            if (slot in setting.menu.indices) {
                fire(setting.menu[slot])
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }

    private companion object {
        /** Room left between the button's disc and the top of a docked keyboard. */
        const val KEYBOARD_GAP_DP = 4f

        /** How far a swipe from the fixed button goes before it counts as one (#1171). */
        const val FLICK_MIN_DP = 28f

        /** How long a hold's finger rests on a category before its ring opens (#923). */
        const val CATEGORY_DWELL_MS = 380L

        /** How far past a slot a hold's finger may go and still open its tool's box (#1401). */
        const val FLOAT_REACH_DP = 120f

        /** Kept clear of the screen's edge round each slot. */
        const val MENU_MARGIN_DP = 8f

        /** A slot's name hangs below its disc. */
        const val MENU_LABEL_ROOM_DP = 18f

        /** How far outside a slot's disc a sliding finger still picks it. */
        const val HOVER_SLACK_DP = 18f

        const val TARGET_WINDOW_DP = 96f
        const val TARGET_LIFT_DP = 72f

        /** A dragged button this close to the target's centre jumps onto it. */
        const val TARGET_SNAP_DP = 84f

        const val APPEAR_MS = 260L

        /** How long the button takes to rise clear of the keyboard, or settle back (#1238). */
        const val LIFT_MS = 220L
        const val SNAP_MS = 260L
        private val NO_OFFSET = IntArray(2)
        const val MAX_ORIGIN_CHECKS = 3
        const val FADE_IN_MS = 120L
        const val FADE_OUT_MS = 420L

        /** How long Look up waits for the tap on a word. */
        const val PICK_TIMEOUT_MS = 10_000L
    }
}

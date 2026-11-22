package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hands the keyboard's own window back to the keyboard while a screen reader
 * is running.
 *
 * Under explore-by-touch the accessibility framework's input filter eats the
 * touch stream before it reaches any window, so the keyboard's spacebar
 * cursor slide, backspace word-swipe, glide typing and handwriting never see a
 * coherent gesture. `setTouchExplorationPassthroughRegion` (API 30) is the one
 * supported way to carve out a rectangle where touches go straight to the view
 * hierarchy, and it may only be called by an accessibility service — hence
 * this one. It exists solely to publish that rectangle:
 *
 *  * it asks for no events ([AccessibilityServiceInfo.eventTypes] is 0) and
 *    cannot retrieve window content, so it reads nothing about the screen;
 *  * the only region it ever passes through is the keyboard's own window,
 *    supplied by the IME via [KeyboardPassthrough];
 *  * everything outside that rectangle keeps working exactly as before —
 *    TalkBack still owns the rest of the system.
 *
 * The pass-through call is only honoured while this service itself requests
 * touch exploration, so the flag is added at runtime — and only while some
 * *other* enabled service already explores by touch. Requesting it
 * unconditionally would switch explore-by-touch on for a user who never asked
 * for a screen reader at all.
 */
class TouchPassthroughService : AccessibilityService() {

    private val part = GesturePassthrough(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        part.connect()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        part.disconnect()
        return super.onUnbind(intent)
    }

    /** Nothing is subscribed — [AccessibilityServiceInfo.eventTypes] is 0. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

/**
 * The pass-through itself, apart from the service that runs it: either
 * [TouchPassthroughService] or, with the user's single-entry choice, the
 * combined [WMAccessibilityService]. Everything [TouchPassthroughService]
 * says about it holds for both hosts.
 */
internal class GesturePassthrough(private val host: AccessibilityService) {

    private var scope: CoroutineScope? = null

    private val accessibilityManager: AccessibilityManager?
        get() = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    private val stateListener = AccessibilityManager.AccessibilityStateChangeListener {
        refreshTouchExplorationFlag()
    }

    private val touchExplorationListener =
        AccessibilityManager.TouchExplorationStateChangeListener {
            refreshTouchExplorationFlag()
        }

    fun connect() {
        KeyboardPassthrough.setServiceConnected(true)
        accessibilityManager?.apply {
            addAccessibilityStateChangeListener(stateListener)
            addTouchExplorationStateChangeListener(touchExplorationListener)
        }
        refreshTouchExplorationFlag()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { this.scope = it }
        scope.launch {
            KeyboardPassthrough.region.collectLatest { applyRegion(it) }
        }
        // Restating the region is not belt-and-braces, it is the only thing
        // that makes it stick. The framework hands the region straight to the
        // display's TouchExplorer and keeps no copy:
        //
        //     if (region != null && mTouchExplorer.contains(displayId)) {
        //         mTouchExplorer.get(displayId).setTouchExplorationPassthroughRegion(region);
        //     }
        //
        // so a call that arrives while no explorer exists is dropped without an
        // error, and every rebuild of the input filter's features destroys the
        // explorer holding it — nothing replays what was set before. Both
        // happen around the moments this service cares about: the explorer is
        // created as touch exploration turns on, and the filter is rebuilt
        // whenever any service's info changes, including this one's flag flip
        // and TalkBack's own updates as the user moves between fields. Setting
        // it once left a region the framework had already thrown away: on
        // device the keys stayed dead under TalkBack while every call reported
        // success. Restating it costs one oneway binder call a second, and only
        // while a screen reader is running and the keyboard is on screen.
        scope.launch {
            while (true) {
                delay(RESTATE_INTERVAL_MS)
                applyRegion(KeyboardPassthrough.region.value ?: continue)
            }
        }
    }

    fun disconnect() {
        // Drop the carve-out before going away: a stale region would leave a
        // slab of the screen bypassing touch exploration with no keyboard
        // under it.
        applyRegion(null)
        accessibilityManager?.apply {
            removeAccessibilityStateChangeListener(stateListener)
            removeTouchExplorationStateChangeListener(touchExplorationListener)
        }
        scope?.cancel()
        scope = null
        KeyboardPassthrough.setServiceConnected(false)
    }

    /**
     * Mirrors [KeyboardPassthrough.region] into the framework. Both the touch
     * explorer and TalkBack's own gesture detector have to be told: the first
     * stops hover-and-double-tap from swallowing the touch, the second stops a
     * swipe across the keys being read as a screen-reader navigation gesture.
     */
    private fun applyRegion(rect: Rect?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val region = if (rect == null || rect.isEmpty) Region() else Region(rect)
        runCatching {
            host.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
            host.setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, region)
        }
    }

    /**
     * Carries [AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE]
     * only while another enabled service already explores by touch. The
     * pass-through region is ignored without the flag, and setting it when no
     * screen reader is running would turn explore-by-touch on by itself.
     */
    private fun refreshTouchExplorationFlag() {
        val info = host.serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        val wanted = otherServiceExploresByTouch()
        if ((info.flags and flag != 0) == wanted) return
        info.flags = if (wanted) info.flags or flag else info.flags and flag.inv()
        runCatching { host.serviceInfo = info }
        // Changing the service info resets what the framework holds for this
        // service, so restate the region under the new flags.
        applyRegion(KeyboardPassthrough.region.value)
    }

    private fun otherServiceExploresByTouch(): Boolean {
        val manager = accessibilityManager ?: return false
        val ownClass = host.javaClass.name
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { service ->
                    service.resolveInfo?.serviceInfo?.name != ownClass &&
                        service.flags and
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
                }
        }.getOrDefault(false)
    }

    private companion object {
        /**
         * How often the published region is restated while the keyboard is up
         * under a screen reader. A second is what device testing showed to be
         * enough: the region is lost around filter rebuilds, and a gap this
         * short leaves no window in which a finger lands on a grid the touch
         * explorer still owns.
         */
        const val RESTATE_INTERVAL_MS = 1000L
    }
}

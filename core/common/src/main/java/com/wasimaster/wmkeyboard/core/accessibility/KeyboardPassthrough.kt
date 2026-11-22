package com.wasimaster.wmkeyboard.core.accessibility

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bridge between the keyboard and [TouchPassthroughService].
 *
 * While an explore-by-touch service (TalkBack) is running, the accessibility
 * framework consumes the touch stream before any app window sees it: a slide
 * along the spacebar or a swipe off backspace never reaches the keyboard, so
 * every custom gesture the keyboard has is dead. The only supported way out is
 * [AccessibilityService.setTouchExplorationPassthroughRegion] (API 30) — and
 * only an accessibility service may call it, which is why this app ships one
 * of its own whose entire job is to hand the keyboard's own rectangle back to
 * the keyboard.
 *
 * The IME and the accessibility service run in the same process (neither
 * declares `android:process`), so the rectangle travels through this object
 * rather than an IPC. The IME writes [publishRegion] on every layout of its
 * window; the service mirrors whatever it finds here into the framework.
 */
object KeyboardPassthrough {

    private val _region = MutableStateFlow<Rect?>(null)

    /** The key grid's rectangle as the keyboard last laid it out. */
    private var gridRect: Rect? = null

    /** Whether the keyboard window is on screen, as the IME last said. */
    private var keyboardShown = false

    /**
     * The keyboard window in display coordinates, or null when nothing should
     * bypass touch exploration — the keyboard is hidden, or the user has not
     * asked for pass-through. The service treats null as "empty region".
     */
    val region: StateFlow<Rect?> = _region.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)

    /**
     * Whether the pass-through is bound right now, through
     * [TouchPassthroughService] or the combined [WMAccessibilityService]. The keyboard falls
     * back to letting TalkBack drive the keys when it is not: pass-through
     * without the service would leave a screen-reader user with keys that
     * neither announce nor honour explore-by-touch.
     */
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    fun publishRegion(rect: Rect?) {
        gridRect = rect
        _region.value = rect.takeIf { keyboardShown }
    }

    /**
     * The IME's window came up or went away. Kept apart from [publishRegion]
     * because the grid does not hear about a hide: the keyboard stops handing
     * its composition new state while it is off screen, so the grid neither
     * clears its rectangle on the way down nor publishes it again on the way
     * back up. A rectangle left standing there kept a slab of whatever screen
     * came next out of TalkBack's reach, and one cleared by the hide never came
     * back for the next field.
     */
    fun setKeyboardShown(shown: Boolean) {
        keyboardShown = shown
        _region.value = gridRect.takeIf { shown }
    }

    internal fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
    }

    /**
     * Whether the user has enabled the pass-through service in system
     * Settings. Read from the enabled-service list rather than
     * [serviceConnected] because the settings app has to answer this before
     * the service has ever run.
     */
    fun isServiceEnabled(context: Context): Boolean =
        AccessibilityEntries.isOn(context, TouchPassthroughService::class.java)
}

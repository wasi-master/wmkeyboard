package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Presses the system's split-screen switch for the app launcher, on phones
 * where the keyboard's own split launch opens the app full screen instead.
 *
 * The launcher's split screen is a launch-adjacent start from the keyboard
 * (see AppLaunchModes). Stock Android 12L and later route that start into the
 * split screen's free half, but before 12L the flag does nothing outside an
 * existing split, and some skins drop it altogether: on a CPH2481 (ColorOS
 * 15) a launch-adjacent start from adb shell opens full screen. The split screen itself is
 * still there on those phones, behind the Recents menu's "Split screen", and
 * the one way another app can press that is
 * [GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN], an accessibility service action. The
 * route is the one the Split Screen app (any.splitscreen 1.3.8) takes: press
 * the switch with the app in front, wait for the free half, then start the
 * second app launch-adjacent into it.
 *
 * Its one power, and nothing else:
 *
 *  * it performs one action, the split-screen switch, and only when the user
 *    opens an app in split screen or a split pair from the launcher;
 *  * before pressing it, it looks at the window list for whether split screen
 *    is already up ([inSplit]), because pressing it then would close the
 *    split instead. That is window types and positions; it never asks a
 *    window for its content, and its events are ignored;
 *  * it injects no input and keeps nothing.
 *
 * Separate from [ScreenContextService] and [TouchPassthroughService] on
 * purpose: each of those promises it performs no actions, and turning on split
 * screen must not be the same switch as letting the AI read the screen.
 */
class SplitScreenHelperService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** Events are not used; the window list is read on demand in [inSplit]. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Puts the app in front into split screen, unless split screen is already
     * up. True when the switch was pressed, so the caller waits for the free
     * half before starting the second app; false when it was not needed or
     * the system refused it, and the caller launches straight away.
     */
    fun enterSplit(): Boolean {
        if (inSplit()) return false
        return runCatching { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }.getOrDefault(false)
    }

    /**
     * Whether split screen is up: the divider's window is listed, or two app
     * windows sit side by side without overlapping. The divider is enough on
     * stock builds; the second test covers skins that do not list it. A
     * dialog over its app overlaps it, so it never counts as a second half.
     */
    private fun inSplit(): Boolean {
        val listed = runCatching { windows }.getOrNull() ?: return false
        if (listed.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }) return true
        val apps = listed
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { Rect().also(it::getBoundsInScreen) }
            .filterNot { it.isEmpty }
        return apps.any { a -> apps.any { b -> a !== b && !Rect.intersects(a, b) } }
    }

    companion object {
        /** The connected service, or null while it is off. */
        @Volatile
        var current: SplitScreenHelperService? = null
            private set

        /**
         * Whether the user has turned the service on in Settings › Accessibility.
         * Read from the secure setting rather than [current], which is only set
         * once the system has bound it.
         */
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, SplitScreenHelperService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}

package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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
 *  * it injects no input and keeps nothing;
 *  * while a split pair opens, it waits for the first app's window to come up
 *    ([awaitWindowOf]): the package name of a window-state event, nothing
 *    else, and only for that one wait.
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

    @Volatile
    private var waiting: Pair<String, CompletableDeferred<Unit>>? = null

    /**
     * Only one use: [awaitWindowOf] waiting for an app's window. The event's
     * package name is compared and dropped; no content is ever read.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val wait = waiting ?: return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName == wait.first) {
            wait.second.complete(Unit)
        }
    }

    /**
     * Waits until [packageName] shows a window, at most [timeoutMs]. True when
     * it did. A split pair's switch must not be pressed before its first app
     * is the one in front, and a fixed delay is either too short on a slow
     * phone or wasted on a fast one.
     */
    suspend fun awaitWindowOf(packageName: String, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Unit>()
        waiting = packageName to deferred
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } != null
        } finally {
            if (waiting?.second === deferred) waiting = null
        }
    }

    /**
     * Whether the system lists the split-screen switch among the actions it
     * honours. AOSP stopped handling it in 12L; skins that kept their own
     * handler list it. Null below Android 11, where nobody can ask.
     */
    fun toggleSplitListed(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { systemActions.any { it.id == GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN } }.getOrNull()
        } else {
            null
        }

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
    fun inSplit(): Boolean {
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

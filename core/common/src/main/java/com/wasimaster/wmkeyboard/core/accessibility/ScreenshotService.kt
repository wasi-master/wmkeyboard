package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Takes a screenshot for the Screenshot tool and the floating button (#1148),
 * the same one the phone's power and volume keys take: Android saves it,
 * shows its own preview, and offers its own editing and sharing.
 *
 * Android lets another app press that button only through an accessibility
 * service, as [GLOBAL_ACTION_TAKE_SCREENSHOT] (Android 9 and later). There is
 * no other route that does not ask for consent on every capture.
 *
 * Its one power, and nothing else:
 *
 *  * it performs one action, the screenshot, and only when the user presses
 *    the tool or the floating button bound to it;
 *  * it reads nothing: no window content, no window list, and its events are
 *    ignored (it subscribes to none);
 *  * it injects no input and keeps nothing. The picture is the system's,
 *    saved where every screenshot goes.
 *
 * Separate from [SplitScreenHelperService], [ScreenContextService] and
 * [TouchPassthroughService] on purpose: turning on split screen help or
 * screen reading must not also let the keyboard take screenshots, nor the
 * other way round.
 */
class ScreenshotService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Presses Android's screenshot button. False when the system refused it, or before Android 9. */
    fun takeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }.getOrDefault(false)
    }

    companion object {
        /** The connected service, or null while it is off. */
        @Volatile
        var current: ScreenshotService? = null
            private set

        /** Android 9 is where the screenshot action arrived; before it there is nothing to press. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /**
         * Whether the user has turned the service on in Settings › Accessibility.
         * Read from the secure setting rather than [current], which is only set
         * once the system has bound it.
         */
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, ScreenshotService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}

package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.wasimaster.wmkeyboard.core.settings.SystemButton

/**
 * Presses Android's own system buttons for the keyboard: the screenshot, for
 * the Screenshot tool and the floating button (#1148), and Back, recent apps,
 * the app drawer, the notification shade and quick settings, for the
 * floating button's system button actions (#1170). A screenshot is the one
 * the power and volume keys take, and Android saves it and shows its own
 * preview.
 *
 * Android lets another app press those buttons only through an accessibility
 * service, as `performGlobalAction`. There is no other route.
 *
 * Its one power, and nothing else:
 *
 *  * it presses one of those buttons, and only when the user presses the
 *    tool or the floating button action bound to it;
 *  * it reads nothing: no window content, no window list, and its events are
 *    ignored (it subscribes to none);
 *  * it injects no input and keeps nothing.
 *
 * Separate from [SplitScreenHelperService], [ScreenContextService] and
 * [TouchPassthroughService] on purpose: turning on split screen help or
 * screen reading must not also let the keyboard press system buttons, nor
 * the other way round. The split screen switch stays with its helper, which
 * also reads the window list to know whether split screen is up.
 */
class SystemButtonsService : AccessibilityService() {

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

    /**
     * Presses [button] (#1170). False when the system refused it, or when
     * this Android has no such button for another app to press.
     */
    fun press(button: SystemButton): Boolean {
        if (!button.available) return false
        val action = when (button) {
            SystemButton.BACK -> GLOBAL_ACTION_BACK
            SystemButton.RECENTS -> GLOBAL_ACTION_RECENTS
            SystemButton.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            SystemButton.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            SystemButton.APP_DRAWER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
            } else {
                return false
            }
        }
        return runCatching { performGlobalAction(action) }.getOrDefault(false)
    }

    companion object {
        /** The connected service, or null while it is off. */
        @Volatile
        var current: SystemButtonsService? = null
            private set

        /** Android 9 is where the screenshot action arrived; before it the Screenshot tool has nothing to press. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        /**
         * Whether the user has turned the service on in Settings › Accessibility.
         * Read from the secure setting rather than [current], which is only set
         * once the system has bound it.
         */
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, SystemButtonsService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}

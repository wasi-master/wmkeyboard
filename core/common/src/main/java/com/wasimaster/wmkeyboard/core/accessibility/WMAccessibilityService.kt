package com.wasimaster.wmkeyboard.core.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * All five of the keyboard's accessibility services as one entry in
 * Settings › Accessibility, for the user who would rather turn one switch on
 * than five (Privacy › Permissions › One accessibility entry).
 *
 * It runs the same parts the separate services run, and nothing more:
 * [GesturePassthrough] for TalkBack, [ScreenReader] for the floating button's
 * Ask AI and Look up, [SplitScreenHelper] for the app launcher,
 * [SystemButtons] for the Screenshot tool and the floating button's system
 * buttons, [TouchMouse] for the Trackpad's mouse mode. Each still acts only
 * when its own feature asks, so the difference is the grant, not what runs:
 * turning this one on gives the keyboard every one of those powers at once,
 * which is why the separate services stay the default.
 *
 * Off in the manifest (`android:enabled="false"`), so it is not listed at all
 * until [AccessibilityEntries.setMerged] turns it on and the five off. Never
 * rename the component: Android stores the user's grant against its name.
 */
class WMAccessibilityService : AccessibilityService() {

    private val passthrough = GesturePassthrough(this)
    private val screen = ScreenReader(this)
    private val split = SplitScreenHelper(this)
    private val buttons = SystemButtons(this)
    private val mouse = TouchMouse(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextService.current = screen
        SplitScreenHelperService.current = split
        SystemButtonsService.current = buttons
        TouchMouseService.current = mouse
        passthrough.connect()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        passthrough.disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (ScreenContextService.current === screen) ScreenContextService.current = null
        if (SplitScreenHelperService.current === split) SplitScreenHelperService.current = null
        if (SystemButtonsService.current === buttons) SystemButtonsService.current = null
        if (TouchMouseService.current === mouse) TouchMouseService.current = null
        mouse.reset()
        super.onDestroy()
    }

    /** Only the split screen helper listens; see [SplitScreenHelper.onEvent]. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = split.onEvent(event)

    override fun onInterrupt() = mouse.reset()
}

/**
 * Whether the keyboard shows as one accessibility entry or five, and which
 * entry stands for a feature right now.
 *
 * The choice is the components' own enabled state, not a stored setting: the
 * five separate services and [WMAccessibilityService] are never listed
 * together, so there is no state for a setting to disagree with.
 */
object AccessibilityEntries {

    /** The services the single entry stands in for. */
    private val separate: List<Class<out AccessibilityService>> = listOf(
        TouchPassthroughService::class.java,
        ScreenContextService::class.java,
        SplitScreenHelperService::class.java,
        SystemButtonsService::class.java,
        TouchMouseService::class.java,
    )

    private const val PREFS = "accessibility_entries"
    private const val KEY_SEPARATE_ON = "separate_on"

    private fun combined(context: Context) = ComponentName(context, WMAccessibilityService::class.java)

    /** Whether the single entry is the one listed. False on a build without it (Play). */
    fun isMerged(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(combined(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** The service to turn on for [service]'s feature: itself, or the single entry. */
    fun activeComponent(context: Context, service: Class<out AccessibilityService>): ComponentName =
        if (isMerged(context)) combined(context) else ComponentName(context, service)

    /** Whether [service]'s feature has its service on, separately or through the single entry. */
    fun isOn(context: Context, service: Class<out AccessibilityService>): Boolean =
        activeComponent(context, service) in enabledServices(context)

    /**
     * Lists the single entry instead of the five, or the five again. Returns
     * whether the grants came along: with `WRITE_SECURE_SETTINGS` (Shizuku's
     * Write secure settings row) the new entries are turned on to match the
     * old ones; without it the user turns them on in Settings › Accessibility.
     */
    fun setMerged(context: Context, merged: Boolean): Boolean {
        if (merged == isMerged(context)) return true
        val app = context.applicationContext
        val before = enabledServices(app)
        val pm = app.packageManager
        val ok = runCatching {
            pm.setComponentEnabledSetting(
                combined(app),
                if (merged) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
            for (service in separate) {
                pm.setComponentEnabledSetting(
                    ComponentName(app, service),
                    if (merged) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.isSuccess
        if (!ok) return false
        return carryGrants(app, merged, before)
    }

    /**
     * Moves the user's grants across, where the app may write the list. Going
     * to one entry, it is on if any of the five was; going back, the five that
     * were on before come back on.
     */
    private fun carryGrants(context: Context, merged: Boolean, before: Set<ComponentName>): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ours = separate.map { ComponentName(context, it) }.toSet()
        val combined = combined(context)
        val wasOn: Boolean
        val restore: Set<ComponentName>
        if (merged) {
            val on = before.filter { it in ours }
            prefs.edit().putStringSet(KEY_SEPARATE_ON, on.map { it.className }.toSet()).apply()
            wasOn = on.isNotEmpty()
            restore = if (wasOn) setOf(combined) else emptySet()
        } else {
            wasOn = combined in before
            restore = if (wasOn) {
                prefs.getStringSet(KEY_SEPARATE_ON, null).orEmpty().map { ComponentName(context.packageName, it) }.toSet()
            } else {
                emptySet()
            }
        }
        if (!wasOn) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val kept = before.filterNot { it in ours || it == combined }
        val list = (kept + restore).joinToString(":") { it.flattenToString() }
        return runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
            if (list.isNotEmpty()) Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
    }

    private fun enabledServices(context: Context): Set<ComponentName> {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return emptySet()
        return raw.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.toSet()
    }
}

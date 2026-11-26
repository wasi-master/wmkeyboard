package com.wasimaster.wmkeyboard.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP

/**
 * Which name the launcher shows under the app icon: "WM Keyboard", or "WMK"
 * (#140), and whether it shows the icon at all.
 *
 * A launcher shows the label of the activity it starts, and a manifest label
 * is fixed at build time. So the launcher entry is not MainActivity but one of
 * two `<activity-alias>`es of it, one per name, and at most one of them is
 * enabled. The application label, which Android's app settings, the keyboard
 * list and the stores read, is never touched.
 *
 * The component states *are* the setting. PackageManager keeps them across
 * updates and reboots, so there is no preference behind them that could
 * disagree, and for the same reason they do not travel in a settings backup.
 * Four states, two components:
 *
 * | Shown        | full alias | short alias |
 * |--------------|------------|-------------|
 * | WM Keyboard  | default    | default     |
 * | WMK          | disabled   | enabled     |
 * | hidden, full | disabled   | default     |
 * | hidden, WMK  | disabled   | disabled    |
 *
 * The short alias's explicit `disabled` is what remembers the name while the
 * icon is hidden, so showing it again brings back the name it had. The full
 * name is exactly a fresh install's state, not a copy of it.
 *
 * Hiding is honest only up to Android 9. From Android 10 an app that requests
 * permissions and ships a launcher entry enabled in its manifest gets a
 * stand-in icon that opens its App info page once every entry is off
 * (LauncherAppsService.shouldShowSyntheticActivity). Gboard escapes that by
 * being a system app; nothing an installed app can do does. The About row says
 * so. The television alias is never touched: a TV home screen lists it alone.
 */
internal object LauncherName {
    /** Enabled by the manifest. Stored state: read the manifest comment before renaming. */
    const val FULL_ALIAS = "com.wasimaster.wmkeyboard.app.LauncherFullName"

    /** Disabled by the manifest. Stored state: read the manifest comment before renaming. */
    const val SHORT_ALIAS = "com.wasimaster.wmkeyboard.app.LauncherShortName"

    /** What a fresh install shows, and what the About row's reset returns to. */
    const val DEFAULT_SHORT = false

    /** A fresh install has its icon. */
    const val DEFAULT_SHOWN = true

    /** The About rows' title resource names, so a restart lands on the row. */
    private const val NAME_ROW = "about_launcher_name_title"
    private const val SHOWN_ROW = "about_launcher_shown_title"

    fun isShort(context: Context): Boolean =
        state(context, SHORT_ALIAS) != COMPONENT_ENABLED_STATE_DEFAULT

    fun isShown(context: Context): Boolean =
        state(context, FULL_ALIAS) != COMPONENT_ENABLED_STATE_DISABLED ||
            state(context, SHORT_ALIAS) == COMPONENT_ENABLED_STATE_ENABLED

    fun setShort(context: Context, short: Boolean) =
        write(context, short = short, shown = isShown(context), row = NAME_ROW)

    fun setShown(context: Context, shown: Boolean) =
        write(context, short = isShort(context), shown = shown, row = SHOWN_ROW)

    private fun write(context: Context, short: Boolean, shown: Boolean, row: String) {
        val packageManager = context.packageManager
        val wanted = mapOf(
            FULL_ALIAS to if (shown && !short) COMPONENT_ENABLED_STATE_DEFAULT else COMPONENT_ENABLED_STATE_DISABLED,
            SHORT_ALIAS to when {
                shown && short -> COMPONENT_ENABLED_STATE_ENABLED
                short -> COMPONENT_ENABLED_STATE_DISABLED
                else -> COMPONENT_ENABLED_STATE_DEFAULT
            },
        ).filter { (alias, state) -> state != state(context, alias) }
        if (wanted.isEmpty()) return
        val (opening, closing) = wanted.entries.partition { (alias, state) -> launchable(alias, state) }

        // The new entry first. With neither enabled, even for the length of
        // one broadcast, a launcher sees an app with nothing to launch and can
        // delete its home-screen icon instead of moving it to the other name.
        for ((alias, state) in opening) {
            packageManager.setComponentEnabledSetting(ComponentName(context, alias), state, DONT_KILL_APP)
        }

        // DONT_KILL_APP spares the process, not the task. When a component is
        // disabled, Android removes every task whose root intent names it
        // (RecentTasks.cleanupDisabledPackageTasksLocked, "disabled-package"),
        // and a settings app opened from the launcher is rooted at exactly the
        // alias about to go: it would vanish under the user's finger. A
        // CLEAR_TASK start re-roots the task at MainActivity itself, which no
        // switch ever disables, and lands back on the row. It has to happen
        // before the calls below, whose broadcast is what triggers the removal.
        if (closing.any { (alias, _) -> rootedAt(context, alias) }) {
            context.startActivity(
                MainActivityContract.intent(context, route = "about", setting = row)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        for ((alias, state) in closing) {
            packageManager.setComponentEnabledSetting(ComponentName(context, alias), state, DONT_KILL_APP)
        }
        // The icon's menu names the entry it hangs off, which just changed.
        AppIconMenu.publish(context)
    }

    private fun state(context: Context, alias: String): Int =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, alias))

    /** Whether [alias] in [state] is a launcher entry; `default` means what the manifest says. */
    private fun launchable(alias: String, state: Int): Boolean =
        state == COMPONENT_ENABLED_STATE_ENABLED ||
            (state == COMPONENT_ENABLED_STATE_DEFAULT && alias == FULL_ALIAS)

    /** Whether any of this app's tasks was started through [alias]. */
    private fun rootedAt(context: Context, alias: String): Boolean =
        context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().any { task ->
            // A task that finishes between the listing and the read throws.
            runCatching { task.taskInfo?.baseIntent?.component?.className == alias }.getOrDefault(false)
        }
}

package com.wasimaster.wmkeyboard.core.shell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.config.BuildConfig

/**
 * Keyboard features that run through [ShellAccess] (Shizuku or root), beyond
 * the app launcher's window routes, which predate them and keep their own
 * rows.
 *
 * Two gates, both closed by default:
 *
 *  * [offered]: the Google Play build has none of this. Reading another app's
 *    window through `uiautomator` and granting the keyboard its own special
 *    access are the same powers Play refused the accessibility services and
 *    screenshot watcher for (see ChannelFeatures in :app), only reached by a
 *    side door. F-Droid's and GitHub's builds offer them.
 *  * the master switch, [isEnabled]: nothing here runs, and no row but the
 *    switch itself shows, until the user turns it on.
 *
 * Stored in device-protected preferences of their own rather than the
 * settings DataStore, so a settings backup does not carry them: each grant is
 * one device's Shizuku or root, and a restored "on" on a phone without either
 * would be a switch wired to nothing.
 */
object ShellFeatures {

    /** Whether this build has the features at all. */
    val offered: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    private const val PREFS = "shell_features"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REPLY_SCREEN = "reply_screen"

    /** The master switch: "Use Shizuku or root for keyboard features". */
    fun isEnabled(context: Context): Boolean = offered && prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** The AI reply panel reads the chat on screen as it opens. */
    fun replyReadsScreen(context: Context): Boolean =
        isEnabled(context) && replyReadsScreenSetting(context)

    /** The stored switch alone, for its row while the master switch is off. */
    fun replyReadsScreenSetting(context: Context): Boolean = prefs(context).getBoolean(KEY_REPLY_SCREEN, false)

    fun setReplyReadsScreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_REPLY_SCREEN, on).apply()
    }

    private fun prefs(context: Context) = deviceContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
}

/**
 * One-shot grants the shell can make that an app cannot make for itself.
 * Each returns whether the command succeeded; null when no door is open.
 *
 * None of these hides anything: every grant lands where Android shows it
 * (Settings › Apps, Notification access, Accessibility, the keyboard list),
 * and is taken back there the usual way.
 */
object ShellGrants {

    /** Enables the keyboard in the system list and makes it the current one. */
    suspend fun makeDefaultKeyboard(context: Context, imeService: String): Boolean? {
        val id = ComponentName(context.packageName, imeService).flattenToShortString()
        return ShellAccess.exec(context, "ime enable $id && ime set $id")?.ok
    }

    /**
     * The dangerous permissions the app declares and does not hold yet,
     * by [PackageInfo.requestedPermissions]. Background location is left out:
     * nothing in the keyboard reads location in the background.
     */
    fun missingRuntimePermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return emptyList()
        return info.requestedPermissions.orEmpty().filter { name ->
            name != Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
                ContextCompat.checkSelfPermission(context, name) != PackageManager.PERMISSION_GRANTED &&
                runCatching { pm.getPermissionInfo(name, 0).isDangerous() }.getOrDefault(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun PermissionInfo.isDangerous(): Boolean {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) protection else protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        return base == PermissionInfo.PROTECTION_DANGEROUS
    }

    /** Grants every [missingRuntimePermissions] entry; the count granted, or null with no shell. */
    suspend fun grantRuntimePermissions(context: Context): Int? {
        val missing = missingRuntimePermissions(context)
        if (missing.isEmpty()) return 0
        if (!ShellAccess.available) return null
        var granted = 0
        for (permission in missing) {
            if (ShellAccess.exec(context, "pm grant ${context.packageName} $permission")?.ok == true) granted++
        }
        return granted
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** WRITE_SECURE_SETTINGS, which the launcher's freeform row and others use without a shell after. */
    suspend fun grantWriteSecureSettings(context: Context): Boolean? =
        ShellAccess.exec(context, "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")?.ok

    /** Notification access for [listenerService]: one-time codes and media controls. */
    suspend fun allowNotificationListener(context: Context, listenerService: String): Boolean? {
        val component = ComponentName(context.packageName, listenerService).flattenToString()
        return ShellAccess.exec(context, "cmd notification allow_listener $component")?.ok
    }

    /**
     * Turns [service] on in Accessibility, keeping every service already on.
     * `settings` writes the list as given, so the current one is read first.
     */
    suspend fun enableAccessibilityService(context: Context, service: String): Boolean? {
        val component = ComponentName(context.packageName, service).flattenToString()
        val current = ShellAccess.exec(context, "settings get secure enabled_accessibility_services")
            ?: return null
        val entries = current.output.trim().takeUnless { it == "null" }.orEmpty()
            .split(':').filter { it.isNotBlank() }
        if (entries.any { ComponentName.unflattenFromString(it) == ComponentName.unflattenFromString(component) }) {
            return true
        }
        val updated = (entries + component).joinToString(":")
        return ShellAccess.exec(
            context,
            "settings put secure enabled_accessibility_services '$updated' && settings put secure accessibility_enabled 1",
        )?.ok
    }

    /** Makes [spellService] the system spell checker, for every app's text fields. */
    suspend fun useSpellChecker(context: Context, spellService: String): Boolean? {
        val component = ComponentName(context.packageName, spellService).flattenToString()
        return ShellAccess.exec(
            context,
            "settings put secure selected_spell_checker $component && " +
                "settings put secure selected_spell_checker_subtype 0 && " +
                "settings put secure spell_checker_enabled 1",
        )?.ok
    }

    /** Whether [spellService] is the system spell checker now. Needs no shell: the setting is readable. */
    fun isSpellChecker(context: Context, spellService: String): Boolean {
        val current = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "selected_spell_checker")
        }.getOrNull() ?: return false
        return ComponentName.unflattenFromString(current) == ComponentName(context.packageName, spellService)
    }
}

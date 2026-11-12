package com.wasimaster.wmkeyboard.ime.floating

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.AppIconPacks

/**
 * The icons and names of the apps the floating button opens (#922), kept so
 * the button, its rings and the settings page do not go back to the package
 * manager on every frame. An app that is gone reads as null, and the caller
 * falls back to the plain glyph and name.
 *
 * Visibility is the manifest's MAIN/LAUNCHER `<queries>` entry, as for the
 * app launcher tool ([com.wasimaster.wmkeyboard.ime.AppCatalog]): an app with
 * a launcher entry is one the keyboard can see. An app of another profile
 * (#1138) comes through [LauncherApps], badged with its profile's briefcase.
 */
object FloatingAppIcons {
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE)
    private val labels = LruCache<String, String>(ICON_CACHE)

    /**
     * The app's icon at [sizePx], or null when it is not installed (or not
     * visible). An installed icon pack the keyboard is set to use, or an icon
     * picked for the app, comes first (#1275); see [AppIconPacks].
     */
    fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        val bitmap = runCatching {
            AppIconPacks.icon(context, AppIconPacks.current, packageName, null, sizePx)?.asImageBitmap()
                ?: context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull() ?: return null
        icons.put(key, bitmap)
        return bitmap
    }

    /** The app's name, or null when it is not installed (or not visible). */
    fun label(context: Context, packageName: String): String? {
        labels.get(packageName)?.let { return it }
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        labels.put(packageName, label)
        return label
    }

    /** [app]'s icon at [sizePx], its profile's badge on it for a work app (#1138); null when it is gone. */
    fun icon(context: Context, app: FloatingAction.App, sizePx: Int): ImageBitmap? {
        val serial = app.profile ?: return icon(context, app.packageName, sizePx)
        val key = "${app.packageName}@$serial@$sizePx"
        icons.get(key)?.let { return it }
        val bitmap = runCatching {
            entry(context, app.packageName, serial)?.getBadgedIcon(0)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
        }.getOrNull() ?: return null
        icons.put(key, bitmap)
        return bitmap
    }

    /** [app]'s name, from its own profile; null when it is gone. */
    fun label(context: Context, app: FloatingAction.App): String? {
        val serial = app.profile ?: return label(context, app.packageName)
        val key = "${app.packageName}@$serial"
        labels.get(key)?.let { return it }
        val label = runCatching { entry(context, app.packageName, serial)?.label?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        labels.put(key, label)
        return label
    }

    /** The launcher entry of [packageName] in the profile with [serial], or null. */
    fun entry(context: Context, packageName: String, serial: Long): LauncherActivityInfo? = runCatching {
        val user = AppCatalog.profileHandle(context, serial) ?: return@runCatching null
        context.getSystemService(LauncherApps::class.java)?.getActivityList(packageName, user)?.firstOrNull()
    }.getOrNull()

    /** Whether the app can still be opened: it is installed and has a launcher entry. */
    fun launchable(pm: PackageManager, packageName: String): Boolean =
        runCatching { pm.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    /** Forgets everything, for a package added, removed or replaced. */
    fun clear() {
        icons.evictAll()
        labels.evictAll()
    }

    private const val ICON_CACHE = 48
}

/** [app]'s icon for a disc of [sizeDp], a work app's badged (#1138), or null; see [FloatingAppIcons]. */
@Composable
fun rememberFloatingAppIcon(app: FloatingAction.App?, sizeDp: Int): ImageBitmap? {
    val context = LocalContext.current
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    return remember(app, px) {
        app?.let { FloatingAppIcons.icon(context, it, px) }
    }
}

/** [packageName]'s icon for a disc of [sizeDp], or null; see [FloatingAppIcons]. */
@Composable
fun rememberFloatingAppIcon(packageName: String?, sizeDp: Int): ImageBitmap? {
    val context = LocalContext.current
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    return remember(packageName, px) {
        packageName?.let { FloatingAppIcons.icon(context, it, px) }
    }
}

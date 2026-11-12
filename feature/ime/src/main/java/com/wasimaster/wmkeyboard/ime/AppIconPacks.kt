package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.icons.SystemIconPacks
import com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings

/**
 * App icons out of a launcher icon pack installed on the phone (#1275), for
 * every place the keyboard draws an app: the app launcher's grid, apps on the
 * toolbar, and the floating button's apps.
 *
 * A picked icon for the app wins, then the pack's icon for the app's activity
 * (or, failing that, for any activity of its package); null means the caller
 * draws the app's own icon, so a pack that has not themed an app leaves it
 * looking as it does on the home screen.
 */
object AppIconPacks {

    /** What decides an app's icon: the pack in use and the single-app picks. */
    data class Choice(val pack: String = "", val picks: Map<String, String> = emptyMap()) {
        val none: Boolean get() = pack.isEmpty() && picks.isEmpty()

        companion object {
            fun of(settings: LauncherToolSettings) = Choice(settings.iconPack, settings.appIcons)
        }
    }

    /**
     * The choice in force for the callers with no settings at hand (the
     * floating button and its settings page, which go through
     * `FloatingAppIcons`). The keyboard sets it whenever its settings change.
     */
    @Volatile
    var current: Choice = Choice()
        private set

    /** Records [choice]; true when it changed, so the caller drops the icons it kept. */
    fun update(choice: Choice): Boolean {
        if (choice == current) return false
        current = choice
        return true
    }

    /**
     * [packageName]'s icon under [choice] at [sizePx], or null for "its own".
     * [activityName] narrows the pack's lookup to that launcher entry when the
     * caller knows it. Blocking: call it off the main thread.
     */
    fun icon(
        context: Context,
        choice: Choice,
        packageName: String,
        activityName: String?,
        sizePx: Int,
    ): Bitmap? {
        if (choice.none) return null
        choice.picks[packageName]?.let { source ->
            SystemIconPacks.parseSource(source)?.let { (pkg, name) ->
                SystemIconPacks.drawable(context, pkg, name, sizePx)?.let { return it }
            }
        }
        val pack = choice.pack.takeIf { it.isNotEmpty() } ?: return null
        val name = SystemIconPacks.index(context, pack).drawableFor(packageName, activityName) ?: return null
        return SystemIconPacks.drawable(context, pack, name, sizePx)
    }
}

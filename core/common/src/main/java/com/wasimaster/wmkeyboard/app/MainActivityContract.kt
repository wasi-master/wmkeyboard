package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent

/**
 * How library modules (the IME service in particular) launch the settings
 * activity without a compile-time dependency on the :app module above them.
 * The component is named as a string; the manifest entry and MainActivity's
 * companion constants both key off these values, so they cannot drift apart
 * without one of them failing to resolve.
 */
object MainActivityContract {
    const val CLASS_NAME = "com.wasimaster.wmkeyboard.app.MainActivity"

    /** A [com.wasimaster.wmkeyboard.core.settings.ToolbarTool].name to open the settings page for. */
    const val EXTRA_OPEN_TOOL = "open_tool"

    /**
     * A settings navigation route to open directly (e.g. "themes",
     * "typing/corrections", "tool/CLIPBOARD").
     *
     * Checked against the app's route allowlist before it is used, so an
     * unknown route opens nothing rather than throwing. The same routes are
     * addressable as `wmkeyboard://settings/<route>` links.
     */
    const val EXTRA_OPEN_ROUTE = "open_route"

    /**
     * One row on that screen, named by the resource name of its title (e.g.
     * "typing_autocorrect_title"). The row scrolls itself into view and pulses
     * once; nothing is toggled.
     *
     * Valid on its own: with no [EXTRA_OPEN_ROUTE] the app looks the row up in
     * its settings index and opens whichever screen holds it.
     */
    const val EXTRA_OPEN_SETTING = "open_setting"

    fun intent(context: Context): Intent =
        Intent()
            .setClassName(context, CLASS_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The settings activity, pointed at one screen and optionally at one row
     * on it. Both are named as strings and validated on arrival, so a caller
     * built against an older version can name a screen this one no longer has
     * without breaking anything.
     */
    fun intent(context: Context, route: String?, setting: String? = null): Intent =
        intent(context).apply {
            route?.takeIf { it.isNotEmpty() }?.let { putExtra(EXTRA_OPEN_ROUTE, it) }
            setting?.takeIf { it.isNotEmpty() }?.let { putExtra(EXTRA_OPEN_SETTING, it) }
        }
}

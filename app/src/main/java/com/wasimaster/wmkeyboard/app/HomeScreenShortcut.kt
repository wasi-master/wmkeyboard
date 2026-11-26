package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.floating.ToolScreens

/**
 * "Add to home screen" for a tool with a screen of its own in this app (#970):
 * the clock, the notes, the to-do list. Asks the launcher to pin a shortcut
 * that opens [route] straight away, the way the app icon's menu does for the
 * pages in it.
 *
 * A tool that lives in the keyboard panel gets [ToolHomeScreenShortcutRow]
 * instead, which opens it full screen or over a text box of its own.
 */
@Composable
internal fun HomeScreenShortcutRow(@StringRes label: Int, route: String) {
    val context = LocalContext.current
    val name = stringResource(label)
    ActionRow(
        R.string.home_shortcut_add_title,
        subtitle = stringResource(R.string.home_shortcut_add_subtitle, name),
        action = stringResource(R.string.home_shortcut_add_action),
    ) {
        // The same address the app icon's menu carries, read by
        // SettingsDeepLink on the way in.
        val tool = ToolLaunch.appScreens.entries.firstOrNull { it.value == route }?.key
        pinShortcut(
            context,
            "tool_$route",
            name,
            ToolLaunch.pageIntent(context, route),
            tool?.let { ToolLaunch.icon(context, it) } ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_tools),
        )
    }
}

/**
 * "Add to home screen" for any tool with a panel (#970): the calculator,
 * the compass, the translator. A tool the keyboard runs full screen opens
 * full screen from it ([ToolScreens]); any other brings the keyboard up
 * over a text box of its own with the tool open ([ToolShortcutActivity]).
 */
@Composable
internal fun ToolHomeScreenShortcutRow(tool: ToolbarTool) {
    val context = LocalContext.current
    val name = stringResource(toolTitle(tool))
    ActionRow(
        R.string.home_shortcut_add_title,
        subtitle = stringResource(
            if (ToolScreens.runs(tool)) R.string.home_shortcut_tool_screen_subtitle else R.string.home_shortcut_tool_subtitle,
            name,
        ),
        action = stringResource(R.string.home_shortcut_add_action),
    ) { pinTool(context, tool) }
}

/**
 * Asks the launcher to pin [tool]: the icon its app-list entry wears (#970
 * asked for one per tool), opening what every other way in opens
 * ([ToolLaunch.intent]). The id is the one earlier builds pinned under, so a
 * launcher that already has the tool updates it rather than adding a second.
 */
internal fun pinTool(context: Context, tool: ToolbarTool) {
    val id = ToolLaunch.appScreens[tool]?.let { "tool_$it" } ?: "panel_${tool.name}"
    pinShortcut(context, id, context.getString(toolTitle(tool)), ToolLaunch.intent(context, tool), ToolLaunch.icon(context, tool))
}

/** Asks the launcher to pin [intent] as [name], or says it cannot. */
private fun pinShortcut(context: Context, id: String, name: String, intent: Intent, icon: IconCompat) {
    val shortcut = ShortcutInfoCompat.Builder(context, id)
        .setShortLabel(name)
        .setIcon(icon)
        .setIntent(intent)
        .build()
    val asked = ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    if (!asked) Toast.makeText(context, R.string.home_shortcut_unsupported, Toast.LENGTH_SHORT).show()
}

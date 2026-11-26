package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/** Tools › Shortcuts to tools: every tool's home-screen icon, app-list entry and place in the icon's menu. */
internal const val APP_SHORTCUTS_ROUTE = "app_shortcuts"

/** Which picker the screen has open. */
private enum class ShortcutPick { HOME, MENU }

/**
 * The three ways a tool opens without a field, on one page: an icon on the
 * home screen (a picker, then the launcher's own confirmation), the menu a
 * press and hold on the app icon opens (settings pages and tools, in the
 * user's order), and a tool's own entry in the app list (a switch each).
 * Each tool's page has the same three for that tool.
 */
@Composable
internal fun AppShortcutsScreen() {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(AppIconMenu.entries(context)) }
    var listed by remember { mutableStateOf(ToolAppList.shown(context)) }
    var picking by remember { mutableStateOf<ShortcutPick?>(null) }
    val capacity = remember { AppIconMenu.capacity(context) }
    val labelOf: (AppIconMenu.Entry) -> String = { context.getString(AppIconMenu.label(it)) }

    SettingsGroup(
        stringResource(R.string.app_shortcuts_home_group),
        info = stringResource(R.string.app_shortcuts_home_info),
    ) {
        item {
            ActionRow(
                R.string.app_shortcuts_home_add_title,
                subtitle = stringResource(R.string.app_shortcuts_home_add_subtitle),
                action = stringResource(R.string.home_shortcut_add_action),
            ) { picking = ShortcutPick.HOME }
        }
    }

    SettingsGroup(
        stringResource(R.string.app_shortcuts_menu_group),
        info = stringResource(R.string.app_shortcuts_menu_info, capacity),
    ) {
        item {
            if (menu.isEmpty()) {
                CaptionText(stringResource(R.string.app_shortcuts_menu_empty))
            } else {
                ReorderableColumn(
                    items = menu,
                    label = labelOf,
                    onReorder = { order ->
                        menu = order
                        AppIconMenu.set(context, order)
                    },
                    onDelete = { gone ->
                        menu = menu - gone
                        AppIconMenu.set(context, menu)
                    },
                    keepLast = false,
                ) { entry ->
                    MenuEntryGlyph(entry)
                    Spacer(Modifier.width(12.dp))
                    Text(labelOf(entry), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            ActionRow(
                R.string.app_shortcuts_menu_add_title,
                subtitle = stringResource(R.string.app_shortcuts_menu_add_subtitle),
                action = stringResource(R.string.app_shortcuts_menu_add_action),
            ) { picking = ShortcutPick.MENU }
        }
        item(visible = menu != AppIconMenu.Default, waitsOnSetting = false) {
            ActionRow(
                R.string.app_shortcuts_menu_reset_title,
                subtitle = stringResource(R.string.app_shortcuts_menu_reset_subtitle),
                action = stringResource(R.string.app_shortcuts_menu_reset_action),
            ) {
                AppIconMenu.reset(context)
                menu = AppIconMenu.entries(context)
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.app_shortcuts_list_group),
        info = stringResource(R.string.app_shortcuts_list_info),
    ) {
        for (tool in ToolLaunch.tools) {
            item {
                ToggleSetting(
                    title = stringResource(toolTitle(tool)),
                    subtitle = null,
                    checked = tool in listed,
                    icon = toolIconFor(tool),
                ) { on ->
                    ToolAppList.setShown(context, tool, on)
                    listed = if (on) listed + tool else listed - tool
                }
            }
        }
    }

    when (picking) {
        ShortcutPick.HOME -> ToolPickerDialog(
            title = stringResource(R.string.app_shortcuts_home_add_title),
            current = null,
            options = ToolLaunch.tools,
            onDismiss = { picking = null },
            onPick = { tool ->
                picking = null
                if (tool != null) pinTool(context, tool)
            },
        )
        ShortcutPick.MENU -> MenuPicker(
            menu = menu,
            onDismiss = { picking = null },
            onPick = { entry ->
                picking = null
                menu = (menu - entry) + entry
                AppIconMenu.set(context, menu)
            },
        )
        null -> Unit
    }
}

/** A line of the menu's glyph: the page's, or the tool's. */
@Composable
private fun MenuEntryGlyph(entry: AppIconMenu.Entry) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (entry) {
        is AppIconMenu.Entry.ToolEntry -> Icon(toolIconFor(entry.tool), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        is AppIconMenu.Entry.PageEntry -> SettingsRouteIcons[entry.page.route]?.let {
            Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** What can join the icon's menu: the pages it does not hold, then the tools, grouped as the Tools screen groups them. */
@Composable
private fun MenuPicker(menu: List<AppIconMenu.Entry>, onDismiss: () -> Unit, onPick: (AppIconMenu.Entry) -> Unit) {
    val context = LocalContext.current
    val pages = AppIconMenu.Page.entries.map(AppIconMenu.Entry::PageEntry).filterNot { it in menu }
    val groups = buildList {
        if (pages.isNotEmpty()) {
            add(
                PickerGroup(
                    "pages",
                    stringResource(R.string.home_shortcut_picker_pages),
                    pages.map { entry ->
                        PickerRow(key = entry.token, title = context.getString(entry.page.longLabel)) { onPick(entry) }
                    },
                ),
            )
        }
        for ((title, tools) in ToolGroups) {
            val rows = tools.filter { ToolLaunch.launchable(it) && AppIconMenu.Entry.ToolEntry(it) !in menu }.map { tool ->
                PickerRow(
                    key = tool.name,
                    title = context.getString(toolTitle(tool)),
                    leading = { Icon(toolIconFor(tool), contentDescription = null, modifier = Modifier.size(20.dp)) },
                ) { onPick(AppIconMenu.Entry.ToolEntry(tool)) }
            }
            if (rows.isNotEmpty()) add(PickerGroup("group-$title", stringResource(title), rows))
        }
    }
    ActionPickerPage(
        memoryKey = "app_icon_menu",
        title = stringResource(R.string.app_shortcuts_menu_add_title),
        groups = groups,
        onDismiss = onDismiss,
        radios = false,
    )
}

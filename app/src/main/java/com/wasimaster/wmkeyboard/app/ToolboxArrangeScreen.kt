package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolKey
import com.wasimaster.wmkeyboard.core.settings.ToolboxSort
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.liveCustomToolIds
import com.wasimaster.wmkeyboard.core.settings.shownWith
import kotlinx.coroutines.launch

/** Appearance › Toolbox › Arrange the toolbox (#1475). */
internal const val TOOLBOX_ARRANGE_ROUTE = "appearance/toolbox/arrange"

/**
 * The toolbox grid's tools as a list the whole screen high (#1475): drag one
 * to move it, switch one off to leave it out of the grid. Dragging in the
 * keyboard's own grid was the only way to order it, a few cells at a time, and
 * hiding a tool meant finding its page under Tools.
 *
 * Lists what the grid can hold: tools that are switched on and usable here,
 * plugins and custom buttons that are live, less what is pinned to a bar,
 * since a pinned tool is not in the grid. Hidden tools stay in the list,
 * switched off, so they can be put back; hiding one leaves the tool on
 * everywhere else ([com.wasimaster.wmkeyboard.core.settings.ToolboxSettings.hiddenTools]).
 *
 * A drop writes the whole stored order once, with every entry the list does
 * not show (switched-off tools, pinned ones, folders) left in its place.
 */
@Composable
internal fun ToolboxArrangeScreen(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val pluginNames = rememberPluginToolNames()
    val hidden = settings.watch { it.toolbox.hiddenTools }
    val sort = settings.watch { it.toolbox.sort }
    val arranged = settings.watch { s -> arrangeableToolboxTools(s, pluginNames.keys) }
    // Any other order replaces this one as the grid opens: say so, with the
    // switch that puts this one back in charge.
    if (sort != ToolboxSort.CUSTOM) {
        StateBanner(
            text = stringResource(R.string.toolbox_arrange_sorted_body),
            action = stringResource(R.string.toolbox_arrange_sorted_action),
        ) { scope.launch { repository.setToolboxSort(ToolboxSort.CUSTOM) } }
    }
    val names = HashMap<ToolKey, String>()
    for (tool in arranged) names[tool] = toolKeyTitle(tool, pluginNames)
    SettingsGroup(
        stringResource(R.string.toolbox_arrange_group_title),
        info = stringResource(R.string.toolbox_arrange_info),
    ) {
        item {
            ReorderableColumn(
                arranged,
                label = { names[it].orEmpty() },
                onReorder = { order ->
                    scope.launch {
                        val stored = settings.value.toolboxOrder
                        repository.setToolboxOrder(mergedToolboxOrder(stored, arranged, order))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { tool ->
                val inGrid = tool !in hidden
                val builtin = tool.tool
                if (builtin != null) {
                    Icon(
                        toolIconFor(builtin),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp).size(20.dp),
                    )
                }
                Text(
                    names[tool].orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (inGrid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = inGrid,
                    onCheckedChange = { on -> scope.launch { repository.setToolHiddenInToolbox(tool, !on) } },
                )
            }
        }
    }
}

/**
 * What the arrange list shows, in the stored order: the entries the grid can
 * hold, as the keyboard's own toolbox decides, hidden ones included.
 */
internal fun arrangeableToolboxTools(
    s: com.wasimaster.wmkeyboard.core.settings.KeyboardSettings,
    livePlugins: Set<String>,
): List<ToolKey> {
    val pinned = s.toolbarTools.toSet() +
        (if (s.toolbarBehavior.secondRow) s.toolbarBehavior.secondRowTools else emptyList())
    val liveCustom = s.toolbox.customButtons.liveCustomToolIds()
    return s.toolboxOrder.filter { key ->
        key !is ToolKey.Folder && key !in pinned &&
            key.shownWith(livePlugins, liveCustom) { it in s.enabledTools && isSupportedTool(it) && isUsableTool(it, s) }
    }
}

/**
 * [stored] with the slots [shown] held refilled by [arranged], in its order;
 * every other entry keeps its slot.
 */
internal fun mergedToolboxOrder(stored: List<ToolKey>, shown: List<ToolKey>, arranged: List<ToolKey>): List<ToolKey> {
    val slots = shown.toHashSet()
    val next = arranged.iterator()
    return stored.map { key -> if (key in slots && next.hasNext()) next.next() else key }
}

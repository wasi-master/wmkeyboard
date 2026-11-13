package com.wasimaster.wmkeyboard.core.settings

/**
 * Where a plugin is a tool: pinned to the toolbar, in the toolbox, or neither,
 * which leaves it in the Plugins panel only. A plugin is a tool by being in
 * [KeyboardSettings.toolbarTools] or [KeyboardSettings.toolboxOrder] as a
 * [ToolKey.Plugin] entry; this is that membership as one answer, for the
 * plugin menus and the plugin's settings page.
 */
enum class PluginToolPlace { NONE, TOOLBAR, TOOLBOX }

/**
 * Where plugin [pluginId] stands in these lists: on the bar when [bar] holds
 * it, in the toolbox when [order] or a folder does, otherwise nowhere.
 */
fun pluginToolPlaceIn(
    bar: List<ToolKey>,
    order: List<ToolKey>,
    folders: List<ToolboxFolder>,
    pluginId: String,
): PluginToolPlace {
    val key = pluginToolKey(pluginId)
    return when {
        key in bar -> PluginToolPlace.TOOLBAR
        key in order || folders.folderOf(key) != null -> PluginToolPlace.TOOLBOX
        else -> PluginToolPlace.NONE
    }
}

/**
 * [bar] and [order] with [key] moved to [place]. On the toolbar it is pinned at
 * the end and keeps its rank in the order, so taking it off the bar later
 * returns it to the same cell of the grid, as a built-in tool does. In the
 * toolbox it leaves the bar, and joins the end of the order unless it already
 * had a place there. [PluginToolPlace.NONE] takes it out of both.
 */
fun withPluginToolPlace(
    bar: List<ToolKey>,
    order: List<ToolKey>,
    key: ToolKey,
    place: PluginToolPlace,
): Pair<List<ToolKey>, List<ToolKey>> {
    val ranked = if (key in order) order else order + key
    return when (place) {
        PluginToolPlace.NONE -> (bar - key) to (order - key)
        PluginToolPlace.TOOLBAR -> (if (key in bar) bar else bar + key) to ranked
        PluginToolPlace.TOOLBOX -> (bar - key) to ranked
    }
}

/**
 * What [migrated][SettingsRepository.migratePluginTiles] the plugin tiles that
 * were custom buttons: the bar, the toolbox order, and the buttons left over.
 * A tile on the bar is pinned at the end of it, one in the toolbox joins the
 * end of the grid, in the order the buttons were in; a plugin already a tool
 * keeps the place it has.
 */
data class MigratedPluginTiles(
    val bar: List<ToolKey>,
    val order: List<ToolKey>,
    val buttons: List<CustomButton>,
)

fun migratedPluginTiles(
    buttons: List<CustomButton>,
    bar: List<ToolKey>,
    order: List<ToolKey>,
): MigratedPluginTiles {
    var nextBar = bar
    var nextOrder = order
    for (button in buttons) {
        if (button.kind != CustomButtonKind.PLUGIN || !isPluginToolId(button.target)) continue
        val key = pluginToolKey(button.target)
        if (key in nextBar || key in nextOrder) continue
        val place = if (button.onToolbar) PluginToolPlace.TOOLBAR else PluginToolPlace.TOOLBOX
        val (b, o) = withPluginToolPlace(nextBar, nextOrder, key, place)
        nextBar = b
        nextOrder = o
    }
    return MigratedPluginTiles(nextBar, nextOrder, buttons.filterNot { it.kind == CustomButtonKind.PLUGIN })
}

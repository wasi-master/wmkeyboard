package com.wasimaster.wmkeyboard.core.settings

/**
 * The user's own buttons as tools (#1345). What a button is stays in
 * [ToolboxSettings.customButtons]; where it is lives in the same lists every
 * tool's place does, as a [ToolKey.Custom] entry: the toolbar, the toolbox
 * order, the hidden tools, the folders, a mode's own bar and order, the use
 * counts. So a button is dragged from the bar into the grid and back, filed in
 * a folder and ranked by use exactly as Calculator is.
 */
data class PlacedCustomButtons(
    val bar: List<ToolKey>,
    val order: List<ToolKey>,
)

/**
 * [bar] and [order] with every custom button in [buttons] given a place, and
 * the entries of buttons no longer there taken out.
 *
 * A button no list holds yet (not the bar, the order nor a folder: a new one,
 * or one made before buttons were tools) goes where its
 * [CustomButton.onToolbar] says: pinned at the end of the bar, or at the end
 * of the toolbox. Either way it is ranked in [order] too, so taking it off the
 * bar later returns it to a cell of the grid, as a built-in tool does. One
 * already placed keeps its place, whatever its flag says. Plugin tiles left
 * from before plugins were tools are [SettingsRepository.migratePluginTiles]'s
 * to move, so they are skipped here.
 */
fun placedCustomButtons(
    buttons: List<CustomButton>,
    bar: List<ToolKey>,
    order: List<ToolKey>,
    folders: List<ToolboxFolder>,
): PlacedCustomButtons {
    val ids = customToolIds(buttons)
    var nextBar = bar.keepingCustom(ids)
    var nextOrder = order.keepingCustom(ids)
    for (button in buttons) {
        if (button.id !in ids) continue
        val key = customToolKey(button.id)
        if (key in nextBar || key in nextOrder || folders.folderOf(key) != null) continue
        val place = if (button.onToolbar) PluginToolPlace.TOOLBAR else PluginToolPlace.TOOLBOX
        val (b, o) = withPluginToolPlace(nextBar, nextOrder, key, place)
        nextBar = b
        nextOrder = o
    }
    return PlacedCustomButtons(nextBar, nextOrder)
}

/** The ids of [buttons] that can be tool entries: every kind but the old plugin tile, with an id a list can hold. */
fun customToolIds(buttons: List<CustomButton>): Set<String> =
    buttons.mapNotNullTo(HashSet()) { button ->
        button.id.takeIf { button.kind != CustomButtonKind.PLUGIN && isCustomToolId(it) }
    }

/** [this] less the custom button entries whose button is not among [ids]; every other entry kept. */
fun List<ToolKey>.keepingCustom(ids: Set<String>): List<ToolKey> =
    filter { key -> key.customButtonId.let { it == null || it in ids } }

/** [this] with every custom button entry not among [ids] taken out of every folder, which can dissolve one. */
@JvmName("foldersKeepingCustom")
fun List<ToolboxFolder>.keepingCustom(ids: Set<String>): List<ToolboxFolder> {
    val gone = flatMap { it.tools }.filter { key -> key.customButtonId.let { it != null && it !in ids } }
    return gone.fold(this) { folders, key -> folders.withoutTool(key) }
}

/** This mode with the entries of custom buttons not among [ids] out of its own bar and order. */
fun KeyboardMode.keepingCustom(ids: Set<String>): KeyboardMode = copy(
    toolbarTools = toolbarTools?.keepingCustom(ids),
    toolboxOrder = toolboxOrder?.keepingCustom(ids),
)

/**
 * [bar] and [order] with button [id] moved onto the toolbar ([onToolbar]) or
 * into the toolbox, keeping its rank in the grid: the settings page's switch,
 * which does what a drag does.
 */
fun withCustomButtonPlace(
    bar: List<ToolKey>,
    order: List<ToolKey>,
    id: String,
    onToolbar: Boolean,
): Pair<List<ToolKey>, List<ToolKey>> =
    withPluginToolPlace(bar, order, customToolKey(id), if (onToolbar) PluginToolPlace.TOOLBAR else PluginToolPlace.TOOLBOX)

package com.wasimaster.wmkeyboard.core.settings

/**
 * One entry of the toolbox and the toolbar: a built-in [ToolbarTool], an
 * installed plugin shown as a tool, or one of the user's own buttons (#1345).
 *
 * Everything that arranges tools (the toolbox order, the pinned tools, the
 * tools hidden from the grid, a toolbox folder's tools, a mode's own order,
 * the use counts behind "Most used") holds these, so a plugin is dragged,
 * filed, pinned, searched and ranked exactly as Calculator is. What a tool
 * *does* stays on [ToolbarTool]; a plugin entry always opens its plugin.
 *
 * Stored as [token]: a built-in's enum name, `plugin:<id>` or `custom:<id>`.
 * All live in the same comma-joined preferences the enum names always did,
 * and a build that does not know an entry's kind skips it as it skips any
 * name it does not know.
 */
sealed interface ToolKey {
    /** The stored form; see [ToolKey]. */
    val token: String

    /** The tool this entry is, when it is a built-in one. */
    val tool: ToolbarTool?

    /** The plugin this entry opens, when it is one. */
    val pluginId: String?

    data class Builtin(override val tool: ToolbarTool) : ToolKey {
        override val token: String get() = tool.name
        override val pluginId: String? get() = null
        override fun toString(): String = token
    }

    data class Plugin(override val pluginId: String) : ToolKey {
        override val token: String get() = PLUGIN_TOOL_PREFIX + pluginId
        override val tool: ToolbarTool? get() = null
        override fun toString(): String = token
    }

    /**
     * One of the user's own buttons (#1213, #1236), by the id it has in the
     * custom button list. Dragged, pinned and filed like any tool (#1345);
     * what it runs, and what it looks like, stays on the button.
     */
    data class Custom(val id: String) : ToolKey {
        override val token: String get() = CUSTOM_TOOL_PREFIX + id
        override val tool: ToolbarTool? get() = null
        override val pluginId: String? get() = null
        override fun toString(): String = token
    }

    companion object {
        /** The entry [token] spells, or null for a name this build does not know or a malformed id. */
        fun of(token: String): ToolKey? {
            val t = token.trim()
            if (t.startsWith(PLUGIN_TOOL_PREFIX)) {
                val id = t.removePrefix(PLUGIN_TOOL_PREFIX)
                return if (isPluginToolId(id)) Plugin(id) else null
            }
            if (t.startsWith(CUSTOM_TOOL_PREFIX)) {
                val id = t.removePrefix(CUSTOM_TOOL_PREFIX)
                return if (isCustomToolId(id)) Custom(id) else null
            }
            return ToolByName[t]?.key
        }
    }
}

/** What a plugin entry's [ToolKey.token] starts with. */
const val PLUGIN_TOOL_PREFIX = "plugin:"

/**
 * Whether [id] can be a plugin's id inside a stored list: the manifest's own
 * rule (a lowercase path segment), which also keeps out the comma the lists
 * are joined with.
 */
fun isPluginToolId(id: String): Boolean = PluginToolId.matches(id)

private val PluginToolId = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

/** What a custom button entry's [ToolKey.token] starts with. */
const val CUSTOM_TOOL_PREFIX = "custom:"

/** Whether [id] can be a custom button's id inside a stored list: letters and digits (`c1`, `c2`, …). */
fun isCustomToolId(id: String): Boolean = CustomToolId.matches(id)

private val CustomToolId = Regex("^[A-Za-z0-9]{1,16}$")

/** Custom button [id] as a toolbox entry. */
fun customToolKey(id: String): ToolKey.Custom = ToolKey.Custom(id)

/** The custom button this entry is, by id, or null for a built-in tool or a plugin. */
val ToolKey.customButtonId: String? get() = (this as? ToolKey.Custom)?.id

private val ToolByName: Map<String, ToolbarTool> = ToolbarTool.entries.associateBy { it.name }

private val BuiltinKeys: List<ToolKey.Builtin> = ToolbarTool.entries.map { ToolKey.Builtin(it) }

/** This tool as a toolbox entry. One shared instance per tool. */
val ToolbarTool.key: ToolKey.Builtin get() = BuiltinKeys[ordinal]

/** The plugin [id] as a toolbox entry. */
fun pluginToolKey(id: String): ToolKey.Plugin = ToolKey.Plugin(id)

/** Each tool as a toolbox entry, in order. */
fun Iterable<ToolbarTool>.toolKeys(): List<ToolKey> = map { it.key }

/** The built-in tools among these entries, in order. */
fun Iterable<ToolKey>.builtinTools(): List<ToolbarTool> = mapNotNull { it.tool }

/** The plugin ids among these entries, in order. */
fun Iterable<ToolKey>.pluginIds(): List<String> = mapNotNull { it.pluginId }

/** Whether these entries hold built-in [tool]. */
operator fun Iterable<ToolKey>.contains(tool: ToolbarTool): Boolean = tool.key in this

/** The stored form of [keys]: tokens joined by commas, each once. */
fun encodeToolKeys(keys: Iterable<ToolKey>): String = keys.distinct().joinToString(",") { it.token }

/** The inverse of [encodeToolKeys]; unknown names and malformed ids are dropped, and each entry is kept once. */
fun decodeToolKeys(csv: String?): List<ToolKey> =
    csv?.split(',')?.mapNotNull { ToolKey.of(it) }?.distinct().orEmpty()

/**
 * [keys] with every entry of plugin [pluginId] gone. What uninstalling a
 * plugin does to each stored list, so its entry disappears cleanly.
 */
fun List<ToolKey>.withoutPlugin(pluginId: String): List<ToolKey> = filterNot { it.pluginId == pluginId }

/**
 * Whether an entry may be shown: a built-in tool by the caller's own test, a
 * plugin only while it is in [livePlugins], the installed plugins that are
 * switched on and draw a panel, and a custom button only while it is in
 * [liveCustom], the buttons that exist and this build can run. An uninstalled
 * or switched-off plugin keeps its place in the stored lists, so turning it
 * back on returns it where it was, but nothing draws it.
 */
inline fun ToolKey.shownWith(
    livePlugins: Set<String>,
    liveCustom: Set<String> = emptySet(),
    builtin: (ToolbarTool) -> Boolean,
): Boolean =
    when (this) {
        is ToolKey.Builtin -> builtin(tool)
        is ToolKey.Plugin -> pluginId in livePlugins
        is ToolKey.Custom -> id in liveCustom
    }

/** [DefaultToolbarTools] as entries: what a fresh install pins. */
val DefaultToolbarKeys: List<ToolKey> = DefaultToolbarTools.toolKeys()

/** [DefaultToolOrder] as entries: the toolbox order before the user drags anything. */
val DefaultToolOrderKeys: List<ToolKey> = DefaultToolOrder.toolKeys()

/**
 * A stored toolbox order made complete: the built-in tools it does not name
 * (shipped later, or dropped from a corrupt entry) rejoin after the nearest
 * tool that outranks them in [DefaultToolOrder], rather than all at the end,
 * so a new tool lands beside its peers. Plugin and custom button entries keep
 * the places they were stored at. An empty list is the default order.
 */
fun completedToolOrder(stored: List<ToolKey>): List<ToolKey> {
    if (stored.isEmpty()) return DefaultToolOrderKeys
    val result = stored.distinct().toMutableList()
    val present = result.toHashSet()
    // Walk the default order so several new tools keep their relative rank;
    // each anchors after the last already-placed tool that outranks it.
    for ((rank, tool) in DefaultToolOrder.withIndex()) {
        val key = tool.key
        if (key in present) continue
        val anchor = DefaultToolOrder.subList(0, rank).lastOrNull { it.key in present }
        val at = if (anchor == null) 0 else result.indexOf(anchor.key) + 1
        result.add(at, key)
        present += key
    }
    return result
}

/** [tools] as entries, for writing a list of built-in tools where entries are kept. */
fun toolKeysOf(vararg tools: ToolbarTool): List<ToolKey> = tools.map { it.key }

/** Whether this build has the entry: a built-in tool by [isSupportedTool], a plugin or a custom button always (whether it is there is asked apart). */
fun ToolKey.isSupported(): Boolean = tool?.let(::isSupportedTool) ?: true

package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.icons.SvgParser
import com.wasimaster.wmkeyboard.core.icons.SymbolNames
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.settings.ToolKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A plugin shown as a tool, as the keyboard draws it: its name in the user's
 * language and what its manifest's `tool.icon` names. [stamp] changes when
 * either does, so a cell drawn from an older one is redrawn.
 */
@Immutable
data class PluginToolFace(
    val id: String,
    val name: String,
    val icon: String,
    val version: String,
) {
    val stamp: String get() = "$version|$icon"
}

/** The face of [plugin] for the tool lists. */
fun pluginToolFace(plugin: InstalledPlugin): PluginToolFace =
    PluginToolFace(plugin.id, plugin.displayName, plugin.tool.icon, plugin.version)

/**
 * The plugins that can be tools right now, by id: on, drawing a panel, with
 * the plugin system on. What [com.wasimaster.wmkeyboard.ime.KeyboardUiState.pluginTools]
 * holds; reads the store's list, which is in memory.
 */
fun livePluginTools(store: PluginStore): Map<String, PluginToolFace> =
    if (!store.subsystemEnabled()) emptyMap() else store.panelPlugins().associate { it.id to pluginToolFace(it) }

/**
 * A plugin's own icon, from its manifest's `tool.icon`: a Material Symbols name
 * looked up in [SymbolNames] (`kitchen`, `directions_transit`), or
 * `assets/<file>.svg`, a one-colour 24×24 drawing in the plugin's archive.
 * Anything else, a blank, a name this build does not carry or a file that will
 * not parse, is the jigsaw every plugin wore before.
 *
 * An SVG is read and parsed once per plugin version and kept here, since the
 * toolbar draws it on every frame of its animations; [warm] does that off the
 * main thread, and [forSlot] only ever reads what is kept.
 */
object PluginToolIcons {
    private const val SLOT_PREFIX = "plugin."
    private const val ASSET_PREFIX = "assets/"

    private class Entry(val stamp: String, val vector: ImageVector)

    private val built = ConcurrentHashMap<String, Entry>()

    /** The icon slot a plugin's tool draws through, so [SlotIcon] finds it. */
    fun slot(pluginId: String): String = SLOT_PREFIX + pluginId

    /** The plugin a slot from [slot] names, or null for any other slot. */
    fun pluginOfSlot(slot: String): String? = if (slot.startsWith(SLOT_PREFIX)) slot.removePrefix(SLOT_PREFIX) else null

    /** Whether [icon] names an SVG in the plugin's archive rather than a symbol. */
    fun isAssetIcon(icon: String): Boolean = icon.trim().startsWith(ASSET_PREFIX)

    /**
     * What [slot] draws, when it is a plugin's: the icon [warm] built, or the
     * symbol its name gives, or the jigsaw. Null for a slot that is no plugin's.
     */
    fun forSlot(slot: String): ImageVector? {
        val id = pluginOfSlot(slot) ?: return null
        return built[id]?.vector ?: Icons.Outlined.Extension
    }

    /** The glyph for a symbol [name], or the jigsaw. Cheap: no file is read. */
    fun symbol(name: String): ImageVector {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || isAssetIcon(trimmed)) return Icons.Outlined.Extension
        return SymbolNames.byName(trimmed) ?: KeyIcons.byName(trimmed) ?: Icons.Outlined.Extension
    }

    /**
     * Builds and keeps the icon of each of [faces] whose icon or version
     * changed since the last call. Reads files: call it off the main thread.
     */
    fun warm(context: Context, faces: Collection<PluginToolFace>) {
        val store = PluginStore.get(context)
        for (face in faces) {
            if (built[face.id]?.stamp == face.stamp) continue
            built[face.id] = Entry(face.stamp, build(store, face.id, face.icon))
        }
    }

    /** [plugin]'s icon, built now. Reads a file for an SVG: call it off the main thread. */
    fun load(context: Context, plugin: InstalledPlugin): ImageVector {
        val face = pluginToolFace(plugin)
        built[face.id]?.takeIf { it.stamp == face.stamp }?.let { return it.vector }
        val vector = build(PluginStore.get(context), face.id, face.icon)
        built[face.id] = Entry(face.stamp, vector)
        return vector
    }

    private fun build(store: PluginStore, id: String, icon: String): ImageVector {
        val trimmed = icon.trim()
        if (!isAssetIcon(trimmed)) return symbol(trimmed)
        val name = trimmed.removePrefix(ASSET_PREFIX)
        if (!name.endsWith(".svg")) return Icons.Outlined.Extension
        val bytes = store.asset(id, name) ?: return Icons.Outlined.Extension
        if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return Icons.Outlined.Extension
        // The parser is lenient and the vector builder is not: a stranger's
        // drawing that will not build is the jigsaw, never a crash.
        return runCatching { SvgParser.parse(bytes.decodeToString())?.toImageVector(slot(id)) }
            .getOrNull() ?: Icons.Outlined.Extension
    }
}

/** The slot [key] draws its icon through: a built-in tool's own, or its plugin's. */
fun toolKeySlot(key: ToolKey): String = when (key) {
    is ToolKey.Builtin -> com.wasimaster.wmkeyboard.core.icons.IconSlots.forTool(key.tool)
    is ToolKey.Plugin -> PluginToolIcons.slot(key.pluginId)
}

/**
 * [plugin]'s icon outside the keyboard (its rows in Settings, the pickers), in
 * [tint]. The jigsaw until an SVG has been read, then the drawing.
 */
@Composable
fun PluginIcon(
    plugin: InstalledPlugin,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val vector by produceState(PluginToolIcons.symbol(plugin.tool.icon), plugin.id, plugin.version, plugin.tool.icon) {
        value = withContext(Dispatchers.IO) { PluginToolIcons.load(context, plugin) }
    }
    Icon(vector, contentDescription = contentDescription, modifier = modifier, tint = tint)
}

package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A named group of toolbox tools (#709): Dictionary and Grammar dropped on each
 * other become one cell, "Language", that opens onto the two of them.
 *
 * A folder holds no place of its own in the toolbox order. It is drawn where
 * the first of its [tools] would be, so [KeyboardSettings.toolboxOrder] stays a
 * plain ranking of tools and every mode's own order keeps working unchanged.
 * [tools] is the order inside the folder, which the user rearranges there.
 *
 * [pinned] puts the folder on the toolbar as well, after the pinned tools; a
 * tap on it there opens the toolbox at the folder.
 */
data class ToolboxFolder(
    val id: String,
    val name: String,
    val tools: List<ToolbarTool>,
    val pinned: Boolean = false,
    /**
     * On the toolbar a tap runs the folder's first tool and a hold opens the
     * folder (#970), so the main tool of a group stays one tap away.
     */
    val firstIsMain: Boolean = false,
)

/** The longest folder name the keyboard keeps. A cell has room for two short lines. */
const val TOOLBOX_FOLDER_NAME_MAX = 40

/** Fewer tools than this and a folder is no longer a folder: it dissolves. */
const val TOOLBOX_FOLDER_MIN_TOOLS = 2

/** The folder [tool] is filed in, or null. A tool is in one folder at most. */
fun List<ToolboxFolder>.folderOf(tool: ToolbarTool): ToolboxFolder? = firstOrNull { tool in it.tools }

/** The folder with this [id], or null. */
fun List<ToolboxFolder>.folderById(id: String?): ToolboxFolder? =
    if (id == null) null else firstOrNull { it.id == id }

/** An id no folder in this list uses yet: `f1`, `f2`, … */
fun List<ToolboxFolder>.nextFolderId(): String {
    val taken = mapTo(HashSet()) { it.id }
    var n = 1
    while ("f$n" in taken) n++
    return "f$n"
}

/**
 * [dropped] let go over [target] in the toolbox grid.
 *
 * Into [target]'s folder when it is in one; otherwise a new folder of the two,
 * named [newName], placed where [target] was. [dropped] leaves whatever folder
 * it was in first, which can dissolve that one. Dropping a tool on itself, or
 * on a tool it already shares a folder with, changes nothing.
 */
fun List<ToolboxFolder>.withMerge(
    dropped: ToolbarTool,
    target: ToolbarTool,
    newName: String,
): List<ToolboxFolder> {
    if (dropped == target) return this
    val home = folderOf(target)
    if (home != null && dropped in home.tools) return this
    val without = withoutTool(dropped)
    return if (home != null) {
        without.map { if (it.id == home.id) it.copy(tools = it.tools + dropped) else it }
    } else {
        without + ToolboxFolder(
            id = without.nextFolderId(),
            name = newName.trim().take(TOOLBOX_FOLDER_NAME_MAX).ifEmpty { "…" },
            tools = listOf(target, dropped),
        )
    }
}

/** [dropped] let go over the folder [folderId]: filed at the end of it. */
fun List<ToolboxFolder>.withAdded(dropped: ToolbarTool, folderId: String): List<ToolboxFolder> {
    val folder = folderById(folderId) ?: return this
    if (dropped in folder.tools) return this
    return withoutTool(dropped).map { if (it.id == folderId) it.copy(tools = it.tools + dropped) else it }
}

/**
 * [tool] taken out of its folder, back to a cell of its own. A folder left with
 * fewer than [TOOLBOX_FOLDER_MIN_TOOLS] dissolves, its last tool with it.
 */
fun List<ToolboxFolder>.withoutTool(tool: ToolbarTool): List<ToolboxFolder> =
    mapNotNull { folder ->
        if (tool !in folder.tools) return@mapNotNull folder
        val rest = folder.tools - tool
        if (rest.size < TOOLBOX_FOLDER_MIN_TOOLS) null else folder.copy(tools = rest)
    }

/**
 * Drops what cannot be a folder: tools filed twice (the first folder keeps
 * them), and folders left with fewer than [TOOLBOX_FOLDER_MIN_TOOLS]. Run on
 * everything read back from storage, so a hand-edited backup or a tool a later
 * version retired cannot leave a folder of one.
 */
fun List<ToolboxFolder>.normalizedFolders(): List<ToolboxFolder> {
    val seen = HashSet<ToolbarTool>()
    val ids = HashSet<String>()
    return mapNotNull { folder ->
        if (!ids.add(folder.id)) return@mapNotNull null
        val tools = folder.tools.filter { seen.add(it) }
        if (tools.size < TOOLBOX_FOLDER_MIN_TOOLS) null else folder.copy(tools = tools)
    }
}

/**
 * The stored form: a JSON array of `{"id", "name", "tools": [names], "pinned"}`.
 * Empty encodes as null, so the preference is removed rather than kept empty.
 */
fun encodeToolboxFolders(folders: List<ToolboxFolder>): String? {
    if (folders.isEmpty()) return null
    return buildJsonArray {
        for (folder in folders) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(folder.id))
                    put("name", JsonPrimitive(folder.name))
                    put("tools", JsonArray(folder.tools.map { JsonPrimitive(it.name) }))
                    if (folder.pinned) put("pinned", JsonPrimitive(true))
                    if (folder.firstIsMain) put("firstIsMain", JsonPrimitive(true))
                },
            )
        }
    }.toString()
}

/** The inverse of [encodeToolboxFolders]; unknown tools and malformed entries are dropped. */
fun decodeToolboxFolders(raw: String?): List<ToolboxFolder> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val tools = obj["tools"]?.jsonArray.orEmpty().mapNotNull { tool ->
                tool.jsonPrimitive.contentOrNull?.let { name -> ToolbarTool.entries.firstOrNull { it.name == name } }
            }
            ToolboxFolder(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(TOOLBOX_FOLDER_NAME_MAX),
                tools = tools,
                pinned = obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                firstIsMain = obj["firstIsMain"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }.normalizedFolders()
}

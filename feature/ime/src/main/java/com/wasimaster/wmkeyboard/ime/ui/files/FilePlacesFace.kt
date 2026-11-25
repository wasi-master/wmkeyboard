package com.wasimaster.wmkeyboard.ime.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.files.FileCategory
import com.wasimaster.wmkeyboard.core.files.FileShortcut
import com.wasimaster.wmkeyboard.core.files.FileTree
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Usb
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FileManagerUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.focusRing

/** Places' tiles in the order the category grid draws them. Downloads is a folder, not a category, and sits among them. */
private val TILES: List<FileCategory?> = listOf(
    FileCategory.IMAGES, FileCategory.VIDEOS, FileCategory.AUDIO, FileCategory.DOCUMENTS,
    FileCategory.APKS, FileCategory.ARCHIVES, null, FileCategory.LARGE,
)

/** The shortcut folders listed under Places beside the category tiles (Downloads has a tile). */
private val FOLDER_SHORTCUTS = listOf(FileShortcut.DOCUMENTS, FileShortcut.PICTURES, FileShortcut.CAMERA, FileShortcut.MUSIC, FileShortcut.MOVIES)

/**
 * Everything Places offers, in the order it draws them, so a hardware
 * keyboard's arrows walk the same order the eye does.
 */
internal fun placeTargets(ui: FileManagerUi): List<FileAction> = buildList {
    if (ui.access) {
        ui.volumes.forEach { add(FileAction.OpenFolder(it.path)) }
        TILES.forEach { add(if (it == null) FileAction.OpenShortcut(FileShortcut.DOWNLOADS) else FileAction.OpenCategory(it)) }
    }
    ui.pins.forEach { add(FileAction.OpenPin(it)) }
    if (ui.access) ui.shortcuts.filter { it in FOLDER_SHORTCUTS }.forEach { add(FileAction.OpenShortcut(it)) }
    if (ui.access) add(FileAction.OpenRecent)
    ui.recentFolders.take(RECENT_SHOWN).forEach { add(FileAction.OpenPin(it)) }
    ui.trees.forEach { add(FileAction.OpenTree(it)) }
    add(FileAction.AddTree)
    if (!ui.access && ui.canAskAccess) add(FileAction.AskPermission)
}

/**
 * Places, as the owner picked (1A): a card per storage volume with how
 * full it is, eight tiles for the kinds of file across the phone, pinned
 * folders as chips, the folders opened lately, and the folders added
 * through Android's picker. Without All files access only the added folders
 * and the way to add one (or to allow access) are left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlacesFace(ui: FileManagerUi, focused: Int?, compact: Boolean, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    var at = 0
    fun next(): Boolean = (focused == at).also { at++ }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 6.dp)) {
        // The list's content can be built again on its own; the count starts over each time.
        at = 0
        if (ui.access && ui.volumes.isNotEmpty()) {
            val focus = ui.volumes.map { next() }
            item(key = "volumes") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.volumes.forEachIndexed { i, volume ->
                        StorageCard(
                            label = volume.label,
                            icon = if (volume.primary) Icons.Outlined.Smartphone else Icons.Outlined.Storage,
                            space = ui.space[volume.path],
                            focused = focus[i],
                            compact = compact,
                            modifier = Modifier.width(
                                when {
                                    compact -> 150.dp
                                    ui.volumes.size == 1 -> 340.dp
                                    else -> 165.dp
                                },
                            ),
                        ) { act(FileAction.OpenFolder(volume.path)) }
                    }
                }
            }
        }
        if (ui.access) {
            val focus = TILES.map { next() }
            item(key = "tiles") {
                val columns = if (compact) 2 else 4
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    TILES.chunked(columns).forEachIndexed { row, tiles ->
                        Row(Modifier.fillMaxWidth()) {
                            tiles.forEachIndexed { col, category ->
                                val index = row * columns + col
                                Box(Modifier.weight(1f)) {
                                    if (category == null) {
                                        CategoryTile(
                                            label = stringResource(R.string.ime_files_downloads),
                                            icon = DownloadsIcon,
                                            colour = kb.accent,
                                            count = null,
                                            focused = focus[index],
                                        ) { act(FileAction.OpenShortcut(FileShortcut.DOWNLOADS)) }
                                    } else {
                                        CategoryTile(
                                            label = stringResource(categoryLabel(category)),
                                            icon = categoryIcon(category),
                                            colour = categoryColour(category),
                                            count = ui.categoryCounts[category],
                                            focused = focus[index],
                                        ) { act(FileAction.OpenCategory(category)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val shortcuts = if (ui.access) ui.shortcuts.filter { it in FOLDER_SHORTCUTS } else emptyList()
        if (ui.pins.isNotEmpty() || shortcuts.isNotEmpty()) {
            val pinFocus = ui.pins.map { next() }
            val shortcutFocus = shortcuts.map { next() }
            item(key = "pins") {
                Column {
                    SectionLabel(stringResource(if (ui.pins.isEmpty()) R.string.ime_files_section_folders else R.string.ime_files_section_pinned))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ui.pins.forEachIndexed { i, pin ->
                            PlaceChip(pin.label, Icons.Outlined.PushPin, pinFocus[i], onHold = { act(FileAction.Unpin(pin)) }) { act(FileAction.OpenPin(pin)) }
                        }
                        shortcuts.forEachIndexed { i, shortcut ->
                            PlaceChip(stringResource(shortcutLabel(shortcut)), Icons.Outlined.Folder, shortcutFocus[i]) {
                                act(FileAction.OpenShortcut(shortcut))
                            }
                        }
                    }
                }
            }
        }
        val recent = ui.recentFolders.take(RECENT_SHOWN)
        if (ui.access || recent.isNotEmpty()) {
            val recentFilesFocus = if (ui.access) next() else false
            val folderFocus = recent.map { next() }
            item(key = "recent-head") { SectionLabel(stringResource(R.string.ime_files_section_recent)) }
            if (ui.access) {
                item(key = "recent-files") {
                    PlaceRow(stringResource(R.string.ime_files_recent_files), null, Icons.Outlined.History, kb.secondaryText, recentFilesFocus) {
                        act(FileAction.OpenRecent)
                    }
                }
            }
            recent.forEachIndexed { i, pin ->
                item(key = "recent-${pin.path}") {
                    PlaceRow(pin.label, null, Icons.Outlined.Folder, kb.accent, folderFocus[i]) { act(FileAction.OpenPin(pin)) }
                }
            }
        }
        val treeFocus = ui.trees.map { next() }
        val addFocus = next()
        item(key = "added-head") { SectionLabel(stringResource(R.string.ime_files_section_added)) }
        ui.trees.forEachIndexed { i, tree ->
            item(key = "tree-${tree.uri}") {
                PlaceRow(tree.label, null, treeIcon(tree), kb.secondaryText, treeFocus[i], onHold = { act(FileAction.AskRemoveTree(tree)) }) {
                    act(FileAction.OpenTree(tree))
                }
            }
        }
        item(key = "add-tree") {
            PlaceRow(
                stringResource(R.string.ime_files_add_tree),
                stringResource(R.string.ime_files_add_tree_detail).takeIf { !compact },
                Icons.Outlined.Add,
                kb.secondaryText,
                addFocus,
            ) { act(FileAction.AddTree) }
        }
        if (!ui.access && ui.canAskAccess) {
            val allowFocus = next()
            item(key = "allow") {
                PlaceRow(
                    stringResource(R.string.ime_files_allow_all),
                    stringResource(R.string.ime_files_allow_all_detail),
                    Icons.Outlined.Storage,
                    kb.accent,
                    allowFocus,
                ) { act(FileAction.AskPermission) }
            }
        }
    }
}

@Composable
private fun StorageCard(
    label: String,
    icon: ImageVector,
    space: Pair<Long, Long>?,
    focused: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val shape = kb.cardShape()
    Column(
        modifier
            .focusRing(focused, shape)
            .clip(shape)
            .background(kb.chip)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (space != null && space.second > 0) {
            val (free, total) = space
            val used = total - free
            MeterBar(used.toFloat() / total, kb.accent, Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.ime_files_space, bytes(context, used), bytes(context, total)),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategoryTile(label: String, icon: ImageVector, colour: Color, count: Int?, focused: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing(focused, shape)
            .clip(shape)
            .combinedClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colour.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = kb.suggestionText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            count?.let { "%,d".format(it) } ?: " ",
            color = kb.secondaryText,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceChip(label: String, icon: ImageVector, focused: Boolean, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .focusRing(focused, shape)
            .clip(shape)
            .background(kb.chip)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.chipText, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    label: String,
    detail: String?,
    icon: ImageVector,
    tint: Color,
    focused: Boolean,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) kb.accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(kb.chip), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = kb.suggestionText, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = kb.secondaryText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = LocalKbTheme.current.secondaryText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

private fun treeIcon(tree: FileTree): ImageVector = when (tree.kind) {
    FileTree.Kind.PHONE -> Icons.Outlined.Folder
    FileTree.Kind.REMOVABLE -> Icons.Outlined.Usb
    FileTree.Kind.ELSEWHERE -> Icons.Outlined.Cloud
}

internal fun shortcutLabel(shortcut: FileShortcut): Int = when (shortcut) {
    FileShortcut.DOWNLOADS -> R.string.ime_files_downloads
    FileShortcut.DOCUMENTS -> R.string.ime_files_documents
    FileShortcut.PICTURES -> R.string.ime_files_pictures
    FileShortcut.CAMERA -> R.string.ime_files_camera
    FileShortcut.MUSIC -> R.string.ime_files_music
    FileShortcut.MOVIES -> R.string.ime_files_movies
}

private const val RECENT_SHOWN = 4

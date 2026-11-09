package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.IconLibrary
import com.wasimaster.wmkeyboard.core.icons.IconOverrides
import com.wasimaster.wmkeyboard.core.icons.IconPack
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.LibraryAddResult
import com.wasimaster.wmkeyboard.core.icons.LibraryIcon
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutIcon
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.ime.ui.ResolvedIcon
import com.wasimaster.wmkeyboard.ime.ui.rememberLibraryIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/*
 * "Your icons": the pictures the user imported, kept in the [IconLibrary]
 * apart from every slot. Shared by the icon picker's tab, the Icons screen's
 * own page, the layout editor's key icons and the theme editor's key icons, so
 * the four agree on what a long press offers and on what Add accepts.
 */

/**
 * The library's pictures as they change. A plain list read off the store,
 * re-read whenever its revision moves.
 */
@Composable
internal fun rememberLibraryIcons(): List<LibraryIcon> {
    val context = LocalContext.current
    val library = remember(context) { IconLibrary.get(context) }
    val revision by library.revision.collectAsState()
    return remember(revision) { library.icons() }
}

/**
 * A launcher that picks any number of pictures and adds each one to the
 * library. [onAdded] hears every picture that went in, in the order picked,
 * including one that was already there. Files the library refused are named in
 * a dialog this composable draws itself.
 */
@Composable
internal fun rememberLibraryImport(onAdded: (List<LibraryIcon>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { IconLibrary.get(context) }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = ArrayList<LibraryIcon>()
            val rejected = ArrayList<String>()
            var full = false
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val fileName = runCatching { WMFileTypes.displayName(context, uri) }.getOrNull().orEmpty()
                    val bytes = runCatching {
                        context.contentResolver.requireInputStream(uri).use { it.readBoundedBytes(ICON_FILE_CAP + 1) }
                    }.getOrNull()
                    when (val result = bytes?.let { library.add(it, IconLibrary.nameFrom(fileName)) }) {
                        is LibraryAddResult.Added -> added += result.icon
                        LibraryAddResult.Full -> full = true
                        else -> rejected += fileName.ifBlank { "?" }
                    }
                }
            }
            if (added.isNotEmpty()) onAdded(added)
            message = when {
                full -> context.resources.getQuantityString(
                    R.plurals.plugins_icons_library_full,
                    IconLibrary.MAX_ICONS,
                    IconLibrary.MAX_ICONS,
                )
                rejected.isNotEmpty() -> context.getString(
                    R.string.plugins_icons_library_rejected,
                    rejected.joinToString(", "),
                    ICON_FILE_CAP / 1024,
                )
                else -> null
            }
        }
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
    return { launcher.launch(ICON_PICKER_MIME_TYPES) }
}

/**
 * [icon] at whatever size [modifier] gives it, taking [tint] only when it is
 * monochrome: a picture with colours of its own is drawn as painted.
 */
@Composable
internal fun TintedResolvedIcon(
    icon: ResolvedIcon,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val shade = if (icon.monochrome) tint else Color.Unspecified
    val bitmap = icon.bitmap
    val vector = icon.vector
    when {
        bitmap != null -> Icon(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, tint = shade)
        vector != null -> Icon(vector, contentDescription = contentDescription, modifier = modifier, tint = shade)
    }
}

/** One library picture in a grid, drawn as the keyboard would draw it. */
@Composable
internal fun LibraryIconCell(
    icon: LibraryIcon,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    IconGridCell(icon.name, selected, onClick, modifier, onLongClick = onLongClick) {
        ResolvedGlyph(rememberLibraryIcon(icon.id))
    }
}

/** The grid's first cell, which opens the file picker. */
@Composable
internal fun LibraryAddCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconGridCell(stringResource(R.string.plugins_icons_library_add), selected = false, onClick, modifier) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(IconGridGlyph))
    }
}

/**
 * The library as a picker: Add first, then every picture, the most recently
 * used leading. A tap picks; a long press opens [LibraryIconMenu].
 *
 * [onAdded] hears pictures the Add cell brought in, so a picker can put the
 * first one straight on what it is choosing for. Lazy, so it lives inside a
 * dialog with a bounded height.
 */
@Composable
internal fun LibraryIconPickerGrid(
    selectedId: String?,
    onPick: (LibraryIcon) -> Unit,
    onAdded: (List<LibraryIcon>) -> Unit,
) {
    val icons = rememberLibraryIcons()
    val add = rememberLibraryImport(onAdded)
    var menuFor by remember { mutableStateOf<LibraryIcon?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icons.isEmpty()) {
            Text(
                stringResource(R.string.plugins_icons_yours_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val gridState = rememberLazyGridState()
        val rail = rememberScrollRailState(gridState)
        ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 320.dp)) { cells ->
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(IconGridCellMinWidth),
                modifier = cells,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "add") { LibraryAddCell(add) }
                items(icons, key = { it.id }) { icon ->
                    LibraryIconCell(
                        icon = icon,
                        selected = icon.id == selectedId,
                        onClick = { onPick(icon) },
                        onLongClick = { menuFor = icon },
                    )
                }
            }
        }
    }
    menuFor?.let { icon -> LibraryIconMenu(icon) { menuFor = null } }
}

/**
 * What a long press on a picture offers: its name, how its colours are drawn,
 * and deleting it. Deleting puts every icon that wore it back on its default
 * and says which ones first.
 */
@Composable
internal fun LibraryIconMenu(icon: LibraryIcon, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { IconLibrary.get(context) }
    // Its own handle rather than one passed down: the menu opens from the
    // layout editor's key sheet too, which has no repository to hand over, and
    // every handle reads the one DataStore.
    val repository = remember(context) { SettingsRepository(context.applicationContext) }
    var name by remember(icon.id) { mutableStateOf(icon.name) }
    var keep by remember(icon.id) { mutableStateOf(icon.keepColours) }
    var confirmDelete by remember { mutableStateOf(false) }
    val usedBy by remember(repository, icon.id) {
        repository.settings.map { s ->
            s.icons.overrides.filterValues { it == IconOverrides.librarySource(icon.id) }.keys.sorted()
        }
    }.collectAsState(emptyList())
    fun commit() {
        library.rename(icon.id, name)
        library.setKeepColours(icon.id, keep)
    }
    if (confirmDelete) {
        val labels = usedBy.mapNotNull { IconSlots.byId(it) }.map { slotLabel(context, it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.plugins_icons_library_delete_title, icon.name)) },
            text = {
                Text(
                    if (labels.isEmpty()) {
                        stringResource(R.string.plugins_icons_library_delete_body)
                    } else {
                        stringResource(R.string.plugins_icons_library_delete_used_body, labels.joinToString(", "))
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    library.delete(icon.id)
                    // Closed only once the settings have let go of it: the
                    // scope ends with this dialog.
                    scope.launch {
                        repository.forgetLibraryIcon(icon.id)
                        onDismiss()
                    }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = {
            commit()
            onDismiss()
        },
        icon = { ResolvedGlyph(rememberLibraryIcon(icon.id)) },
        title = { Text(icon.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(IconLibrary.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.plugins_icons_library_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.plugins_icons_library_colours_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColourChoice(
                    label = stringResource(R.string.plugins_icons_library_colours_auto),
                    body = stringResource(R.string.plugins_icons_library_colours_auto_body),
                    selected = keep == null,
                ) { keep = null }
                ColourChoice(
                    label = stringResource(R.string.plugins_icons_library_colours_keep),
                    selected = keep == true,
                ) { keep = true }
                ColourChoice(
                    label = stringResource(R.string.plugins_icons_library_colours_tint),
                    selected = keep == false,
                ) { keep = false }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                commit()
                onDismiss()
            }) { Text(stringResource(CommonR.string.common_done)) }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.plugins_icons_library_delete))
            }
        },
    )
}

@Composable
private fun ColourChoice(label: String, selected: Boolean, body: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The Icons screen's "Your icons" page: the whole library at full size, for
 * adding several pictures at once, tidying them, and exporting the icons that
 * wear them as a pack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconLibraryScreen(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { IconLibrary.get(context) }
    val icons = rememberLibraryIcons()
    val add = rememberLibraryImport { }
    var menuFor by remember { mutableStateOf<LibraryIcon?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val packName = stringResource(R.string.plugins_icons_library_pack_name)

    // Slot → picture, for every icon wearing one; parked for the export picker.
    var pendingExport by remember { mutableStateOf<Map<String, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(IconPackFile.MIME_TYPE),
    ) { uri ->
        val worn = pendingExport
        pendingExport = null
        if (uri == null || worn == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        IconPackFile.write(
                            out,
                            IconPack(id = "library", name = packName, slots = worn.keys.sorted()),
                            appVersion = BuildConfig.VERSION_CODE,
                            appVersionName = BuildConfig.VERSION_NAME,
                        ) { slot -> worn[slot]?.let { library.fileFor(it) } }
                    }
                }.isSuccess
            }
            message = if (ok) {
                context.getString(R.string.plugins_icons_export_done_message, packName)
            } else {
                context.getString(R.string.plugins_icons_export_failed_message)
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.plugins_icons_library_title),
        info = stringResource(R.string.plugins_icons_library_info),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                if (icons.isEmpty()) {
                    Text(
                        stringResource(R.string.plugins_icons_yours_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LibraryAddCell(add, modifier = Modifier.width(IconGridCellMinWidth))
                    for (icon in icons) {
                        LibraryIconCell(
                            icon = icon,
                            selected = false,
                            // A tap has nothing to pick here, so it opens the
                            // same menu a long press does.
                            onClick = { menuFor = icon },
                            onLongClick = { menuFor = icon },
                            modifier = Modifier.width(IconGridCellMinWidth),
                        )
                    }
                }
            }
        }
    }
    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.plugins_icons_library_export_title),
                subtitle = stringResource(R.string.plugins_icons_library_export_subtitle),
                icon = Icons.Outlined.FileUpload,
                accent = routeAccent("icons"),
                onClick = {
                    val worn = settings.value.icons.overrides.mapNotNull { (slot, source) ->
                        IconOverrides.libraryIdOf(source)
                            ?.takeIf { library.icon(it) != null }
                            ?.let { slot to it }
                    }.toMap()
                    if (worn.isEmpty()) {
                        message = context.getString(R.string.plugins_icons_library_export_empty)
                    } else {
                        pendingExport = worn
                        exportLauncher.launch(IconPackFile.fileName(IconPack(id = "library", name = packName)))
                    }
                },
            )
        }
    }

    menuFor?.let { icon -> LibraryIconMenu(icon) { menuFor = null } }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/**
 * The Your icons pictures [layouts] wear, ready for a layout file to carry.
 * Blocking: it reads each picture's file.
 */
internal fun layoutIconsFor(context: Context, layouts: List<LayoutSpec>): List<LayoutIcon> {
    val library = IconLibrary.get(context)
    return LayoutFile.libraryIconIds(layouts).mapNotNull { id ->
        val icon = library.icon(id) ?: return@mapNotNull null
        val bytes = library.bytesOf(id) ?: return@mapNotNull null
        LayoutIcon(id = id, name = icon.name, data = Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}

/**
 * Adds the pictures a layout file carried to Your icons, so its keys find them.
 * Each one is checked like any picture the user picks; one that is too big,
 * not an icon, or whose bytes do not hash to the id it claims is dropped, and
 * the key that named it draws its text. Blocking.
 */
internal fun adoptLayoutIcons(context: Context, icons: List<LayoutIcon>) {
    if (icons.isEmpty()) return
    val library = IconLibrary.get(context)
    // Base64 is four characters per three bytes; anything longer than the
    // largest icon would decode to is refused before it is decoded.
    val maxChars = (ICON_FILE_CAP / 3 + 1) * 4
    for (icon in icons.take(IconLibrary.MAX_ICONS)) {
        if (!IconLibrary.isId(icon.id) || icon.data.length > maxChars) continue
        if (library.icon(icon.id) != null) continue
        val bytes = runCatching { Base64.decode(icon.data, Base64.DEFAULT) }.getOrNull() ?: continue
        if (IconLibrary.idFor(bytes) != icon.id) continue
        library.add(bytes, icon.name.ifBlank { icon.id })
    }
}

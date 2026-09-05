package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.icons.IconImportResult
import com.wasimaster.wmkeyboard.core.icons.IconOverrides
import com.wasimaster.wmkeyboard.core.icons.IconPack
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.IconSlot
import com.wasimaster.wmkeyboard.core.icons.IconSlotGroup
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.SvgParser
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.ime.ui.BuiltinIcons
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.icons.R as IconsR

/**
 * Icon customisation: which pack is active, and per-slot replacements.
 *
 * The screen is deliberately one level deep. Picking a different glyph for one
 * button is the common case, and installing a whole pack is the rare one, so
 * the slot list is the body of the screen and the pack controls sit above it.
 *
 * Everything the user changes here — a single icon or a whole pack — ends up
 * expressible as a `.wmicons` file, because the single-icon path writes into
 * the reserved "My icons" pack that Export then writes out.
 */
@Composable
internal fun IconsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IconPackStore.get(context) }
    // The store is a plain file-backed object with no flow of its own, so this
    // is what re-reads it after a mutation.
    var revision by remember { mutableIntStateOf(0) }
    val packs = remember(revision) { store.packs() }

    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<IconPack?>(null) }
    var picking by remember { mutableStateOf<IconSlot?>(null) }

    // The store and the importer name packs they cannot word themselves: both
    // run off the main thread with no context, so the screen resolves the
    // wording once and hands it over.
    val importedPackName = stringResource(IconsR.string.core_icons_pack_imported_label)
    val minePackName = stringResource(IconsR.string.core_icons_pack_mine_label)

    // CreateDocument cannot carry a payload, so the pack waiting to be written
    // is parked here between launching the picker and its result.
    var pendingExport by remember { mutableStateOf<IconPack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(IconPackFile.MIME_TYPE),
    ) { uri ->
        val pack = pendingExport
        pendingExport = null
        if (uri == null || pack == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        IconPackFile.write(
                            out,
                            pack,
                            appVersion = BuildConfig.VERSION_CODE,
                            appVersionName = BuildConfig.VERSION_NAME,
                        ) { store.fileFor(pack.id, it) }
                    }
                }.isSuccess
            }
            message = if (ok) {
                context.getString(R.string.plugins_icons_export_done_message, pack.name)
            } else {
                context.getString(R.string.plugins_icons_export_failed_message)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { IconPackFile.import(it, store, importedPackName) }
                }.getOrDefault(IconImportResult.Failed)
            }
            revision++
            message = describeImport(context, result)
            // Switching to it is what the user came for; leaving it installed
            // but inactive would read as "nothing happened".
            (result as? IconImportResult.Imported)?.let { repository.setIconPack(it.pack.id) }
        }
    }

    // The one-slot SVG picker. The slot is parked the same way the export pack
    // is, because OpenDocument carries no payload either.
    var pendingSlot by remember { mutableStateOf<IconSlot?>(null) }
    val svgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri == null || slot == null) return@rememberLauncherForActivityResult
        scope.launch {
            val mine = store.mine(minePackName) ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.requireInputStream(uri).use { input ->
                        // Reads one byte past the cap: an oversized file is
                        // then rejected by setIcon rather than silently
                        // truncated into something that happens to parse.
                        input.readBoundedBytes(SvgParser.MAX_SOURCE_BYTES + 1)
                    }
                    store.setIcon(mine.id, slot.id, bytes.decodeToString())
                }.getOrDefault(false)
            }
            revision++
            message = if (ok) {
                // Pinning the slot to "My icons" is what makes the choice
                // survive switching packs — it is an override, not a pack.
                repository.setIconOverride(slot.id, IconOverrides.packSource(mine.id))
                context.getString(
                    R.string.plugins_icons_slot_custom_message,
                    slotLabel(context, slot),
                )
            } else {
                context.getString(
                    R.string.plugins_icons_slot_svg_error,
                    SvgParser.MAX_SOURCE_BYTES / 1024,
                )
            }
        }
    }


    SettingsGroup(
        stringResource(R.string.plugins_icons_pack_title),
        info = stringResource(R.string.plugins_icons_intro_info),
    ) {
        item {
            PackRow(
                name = stringResource(R.string.plugins_icons_pack_builtin_name),
                supporting = stringResource(R.string.plugins_icons_pack_builtin_supporting),
                selected = settings.icons.activePackId.isEmpty(),
                onClick = { scope.launch { repository.setIconPack("") } },
            )
        }
        for (pack in packs) {
            item {
                val count = pack.slots.size
                val countText =
                    pluralStringResource(R.plurals.plugins_icons_pack_icon_count, count, count)
                val authorText = if (pack.author.isNotBlank()) {
                    stringResource(R.string.plugins_icons_pack_author_suffix, pack.author)
                } else {
                    ""
                }
                val versionText = if (pack.version.isNotBlank()) {
                    stringResource(R.string.plugins_icons_pack_version_suffix, pack.version)
                } else {
                    ""
                }
                val exportDesc = stringResource(R.string.plugins_icons_pack_export_desc, pack.name)
                val deleteDesc = stringResource(R.string.plugins_icons_pack_delete_desc, pack.name)
                HighlightableItem(pack.id) {
                    PackRow(
                        name = pack.name,
                        supporting = countText + authorText + versionText,
                        selected = settings.icons.activePackId == pack.id,
                        onClick = { scope.launch { repository.setIconPack(pack.id) } },
                        trailing = {
                            Row {
                                IconButton(onClick = {
                                    pendingExport = pack
                                    exportLauncher.launch(IconPackFile.fileName(pack))
                                }) {
                                    Icon(Icons.Outlined.FileUpload, contentDescription = exportDesc)
                                }
                                IconButton(onClick = { confirmDelete = pack }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = deleteDesc)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    SettingsGroup {
        // Beside the file importer, because both answer "where do I get more of
        // these" — one from a file the user already has, one from the store.
        item { AddonStoreRow(AddonType.IconPack, onNavigate) }
        item {
            WmRow(
                title = stringResource(R.string.plugins_icons_import_title),
                subtitle = stringResource(R.string.plugins_icons_import_subtitle),
                icon = Icons.Outlined.FileOpen,
                accent = routeAccent("icons"),
                onClick = { importLauncher.launch(IconPackFile.IMPORT_MIME_TYPES) },
            )
        }
        item {
            val resetMessage = stringResource(R.string.plugins_icons_reset_message)
            WmRow(
                title = stringResource(R.string.plugins_icons_reset_title),
                subtitle = stringResource(R.string.plugins_icons_reset_subtitle),
                icon = Icons.Outlined.Refresh,
                accent = routeAccent("icons"),
                onClick = {
                    scope.launch {
                        repository.clearIconOverrides()
                        message = resetMessage
                    }
                },
            )
        }
    }

    for (group in IconSlotGroup.entries) {
        SettingsGroup(stringResource(group.titleRes)) {
            for (slot in IconSlots.inGroup(group)) {
                item {
                    WmRow(
                        title = slotLabel(context, slot),
                        leading = {
                            WmIconTile(routeAccent("icons")) {
                                SlotIcon(
                                    slot.id,
                                    contentDescription = null,
                                    modifier = Modifier.size(WmIconTileGlyph),
                                )
                            }
                        },
                        supporting = {
                            CaptionText(describeSource(context, settings, slot, store))
                        },
                        onClick = { picking = slot },
                    )
                }
            }
        }
    }

    picking?.let { slot ->
        IconPickerDialog(
            slot = slot,
            settings = settings,
            onPickBuiltin = { name ->
                scope.launch { repository.setIconOverride(slot.id, IconOverrides.builtinSource(name)) }
                picking = null
            },
            onImportSvg = {
                pendingSlot = slot
                picking = null
                svgLauncher.launch(arrayOf("image/svg+xml", "text/xml", "text/plain", "application/octet-stream"))
            },
            onReset = {
                // An SVG the user imported for this slot lives in "My icons".
                // Dropping the override alone would leave the file behind, so
                // it would keep turning up in exports of a pack they thought
                // they had reverted.
                if (settings.icons.overrides[slot.id] ==
                    IconOverrides.packSource(IconPackStore.MINE_ID)
                ) {
                    store.removeIcon(IconPackStore.MINE_ID, slot.id)
                    revision++
                }
                scope.launch { repository.setIconOverride(slot.id, null) }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }

    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(stringResource(R.string.plugins_icons_pack_delete_confirm_title, pack.name))
            },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.plugins_icons_pack_delete_confirm_body,
                        pack.slots.size,
                        pack.slots.size,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    store.deletePack(pack.id)
                    scope.launch { repository.forgetIconPack(pack.id) }
                    confirmDelete = null
                    revision++
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

/**
 * A tool slot uses the tool's own settings wording, so the two agree.
 *
 * Takes a [Context] rather than reading a resource itself: the SVG picker names
 * the slot from a coroutine, where there is no composition to read from.
 */
private fun slotLabel(context: Context, slot: IconSlot): String =
    slot.tool?.let { context.getString(toolTitle(it)) }
        ?: slot.labelRes?.let { context.getString(it) }.orEmpty()

/** What the row says under the name: where this icon currently comes from. */
private fun describeSource(
    context: Context,
    settings: KeyboardSettings,
    slot: IconSlot,
    store: IconPackStore,
): String {
    val default = context.getString(CommonR.string.common_default)
    val override = settings.icons.overrides[slot.id]
    if (override != null) {
        if (override.startsWith(IconOverrides.BUILTIN_PREFIX)) {
            val builtin = override.removePrefix(IconOverrides.BUILTIN_PREFIX)
            // A name that is gone (renamed between versions) means the slot is
            // drawing its default; say so rather than naming a ghost.
            return if (BuiltinIcons.byName(builtin) != null) BuiltinIcons.label(builtin) else default
        }
        if (override.startsWith(IconOverrides.PACK_PREFIX)) {
            val packId = override.removePrefix(IconOverrides.PACK_PREFIX)
            val pack = store.pack(packId) ?: return default
            return if (slot.id in pack.slots) {
                context.getString(R.string.plugins_icons_source_from_pack, pack.name)
            } else {
                default
            }
        }
    }
    val active = store.pack(settings.icons.activePackId)
    if (active != null && slot.id in active.slots) {
        return context.getString(R.string.plugins_icons_source_from_pack, active.name)
    }
    return default
}

/** Shared with the open-a-file dialog, which reports the same outcomes. */
internal fun describeImport(context: Context, result: IconImportResult): String = when (result) {
    is IconImportResult.Imported -> buildString {
        val count = result.pack.slots.size
        append(
            context.resources.getQuantityString(
                R.plurals.plugins_icons_import_done_message,
                count,
                result.pack.name,
                count,
            ),
        )
        if (result.repairs.isNotEmpty()) {
            append("\n\n")
            append(context.getString(R.string.plugins_icons_import_repairs_intro))
            // Each note arrives as a resource and its arguments, so that the
            // language follows the screen rather than the thread that read
            // the file.
            for (line in result.repairs) append("\n• ${line.resolve(context)}")
        }
    }
    IconImportResult.NotAnIconPack ->
        context.getString(R.string.plugins_icons_import_not_a_pack_error)

    IconImportResult.TooManyPacks -> context.resources.getQuantityString(
        R.plurals.plugins_icons_import_too_many_error,
        IconPackStore.MAX_PACKS,
        IconPackStore.MAX_PACKS,
    )

    IconImportResult.Failed -> context.getString(R.string.plugins_icons_import_read_error)
}

@Composable
private fun PackRow(
    name: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    WmRow(
        title = name,
        subtitle = supporting,
        leading = {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.plugins_icons_pack_active_desc),
                )
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
        },
        trailing = trailing,
        onClick = onClick,
    )
}

/**
 * Picks one slot's icon: a bundled glyph, a file, or back to the default.
 *
 * The grid is the bundled Material set rather than the whole
 * `material-icons-extended` library — see [BuiltinIcons] for why — with a
 * search box, because 180 icons is well past what anyone scans by eye.
 */
@Composable
private fun IconPickerDialog(
    slot: IconSlot,
    settings: KeyboardSettings,
    onPickBuiltin: (String) -> Unit,
    onImportSvg: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val selected = settings.icons.overrides[slot.id]?.removePrefix(IconOverrides.BUILTIN_PREFIX)
    val shown = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) BuiltinIcons.names
        else BuiltinIcons.names.filter { BuiltinIcons.label(it).contains(needle, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(slotLabel(context, slot)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.plugins_icons_picker_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        stringResource(
                            R.string.plugins_icons_picker_no_match,
                            query.trim(),
                        ),
                    )
                } else {
                    val gridState = rememberLazyGridState()
                    // Opens on the glyph this slot already carries. The catalogue
                    // runs to hundreds of icons in a 280dp window, so a slot that
                    // has been changed once is otherwise a scrolling hunt.
                    LaunchedEffect(shown) {
                        val index = shown.indexOf(selected)
                        if (index >= 0) gridState.scrollToItem(index)
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(52.dp),
                        modifier = Modifier.heightIn(max = 280.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(shown, key = { it }) { name ->
                            val vector = BuiltinIcons.catalog.getValue(name)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (name == selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { onPickBuiltin(name) },
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        vector,
                                        contentDescription = BuiltinIcons.label(name),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onImportSvg, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.plugins_icons_picker_use_svg_action))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.plugins_icons_picker_use_default_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Reads at most [limit] bytes. Android has no `InputStream.readNBytes` before
 * API 33 and this app runs back to 24, so the bound is applied by hand — an
 * unbounded read here would let a picked file of any size into memory.
 */
private fun java.io.InputStream.readBoundedBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n <= 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.stickers.CustomSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerAddResult
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerKeywords
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.util.readBytesCapped
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One sticker pack's page, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`sticker_pack/{packId}`) is the same string for every
 * pack and would hang one key on all of them.
 */
internal fun stickerPackRoute(packId: String): String = "sticker_pack/$packId"

/**
 * The sticker packs the user owns, and everything that edits them.
 *
 * The keyboard can only view, send, and save-a-search-result into a pack —
 * an IME has no activity window, so a photo picker, a rename field or a
 * delete confirmation all have to live here.
 */
@Composable
internal fun StickerPacksScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    // Bumped after every mutation; the store is a plain file-backed object with
    // no flow of its own, so this is what re-reads it.
    var revision by remember { mutableIntStateOf(0) }
    val packs = remember(revision) { store.packs() }

    var newPackName by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<StickerPack?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // The pack the community publish dialog is open for.
    var publishing by remember { mutableStateOf<StickerPack?>(null) }
    publishing?.let { PublishStickerPackDialog(it) { publishing = null } }

    // CreateDocument cannot carry a payload, so the pack waiting to be written
    // is parked here between launching the picker and its result.
    var pendingExport by remember { mutableStateOf<StickerPack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(StickerPackFile.MIME_TYPE),
    ) { uri ->
        val pack = pendingExport
        pendingExport = null
        if (uri == null || pack == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        StickerPackFile.write(
                            out,
                            pack,
                            appVersion = BuildConfig.VERSION_CODE,
                            appVersionName = BuildConfig.VERSION_NAME,
                        ) { store.fileFor(pack.id, it) }
                    }
                }.isSuccess
            }
            message = if (ok) {
                context.getString(R.string.import_sticker_pack_saved, pack.name)
            } else {
                context.getString(R.string.import_sticker_pack_write_error)
            }
        }
    }

    // Picked packs, all in one zip, parked like [pendingExport].
    var pendingBulkExport by remember { mutableStateOf<List<StickerPack>?>(null) }
    val bulkExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PackBundle.MIME_TYPE),
    ) { uri ->
        val picked = pendingBulkExport
        pendingBulkExport = null
        if (uri == null || picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        PackBundle.write(
                            out,
                            picked.map { pack ->
                                StickerPackFile.fileName(pack) to { stream: java.io.OutputStream ->
                                    StickerPackFile.write(
                                        stream,
                                        pack,
                                        appVersion = BuildConfig.VERSION_CODE,
                                        appVersionName = BuildConfig.VERSION_NAME,
                                    ) { store.fileFor(pack.id, it) }
                                }
                            },
                        )
                    }
                }.isSuccess
            }
            message = if (ok) {
                context.resources.getQuantityString(R.plurals.pick_exported_message, picked.size, picked.size)
            } else {
                context.getString(R.string.import_sticker_pack_write_error)
            }
        }
    }

    // The file being imported, from reading it through picking its stickers
    // (#965) to the report. The row takes a WhatsApp pack as well as one of
    // the app's own: see [stageStickerFile]. Several files, or a zip of packs
    // from Export selected, skip the picking: every sticker of every pack
    // comes in, and one message says how it went.
    var importing by remember { mutableStateOf<Uri?>(null) }
    var importingMany by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val one = uris.singleOrNull()
        scope.launch {
            val bundled = one != null &&
                withContext(Dispatchers.IO) { PackBundle.isBundle(context, one, StickerPackFile.FILE_EXTENSION) }
            if (one != null && !bundled) {
                importing = one
                return@launch
            }
            importingMany = true
            val results = withContext(Dispatchers.IO) { importStickerFilesWhole(context, store, uris) }
            importingMany = false
            revision++
            message = bulkImportSummary(
                context,
                imported = results.count { it.second is StickerImportResult.Imported },
                failed = results.filter { it.second !is StickerImportResult.Imported }.map { it.first },
            )
        }
    }
    if (importingMany) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_stickers_reading)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
    importing?.let { uri ->
        StickerFileImport(uri) { report ->
            importing = null
            revision++
            message = report
        }
    }


    // Long-pressing a pack starts picking, as in the theme gallery: taps then
    // tick packs, and the dock exports or deletes every ticked one at once.
    var pickedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val livePicked = packs.filter { it.id in pickedIds }
    val picking = livePicked.isNotEmpty()
    BackHandler(enabled = picking) { pickedIds = emptySet() }
    if (picking) {
        RegisterDock {
            PickBar(
                count = livePicked.size,
                allPicked = livePicked.size == packs.size,
                onClear = { pickedIds = emptySet() },
                onSelectAll = { pickedIds = packs.mapTo(HashSet()) { it.id } },
                onExport = {
                    pendingBulkExport = livePicked
                    bulkExportLauncher.launch(PackBundle.fileName("sticker-packs"))
                },
                onDelete = { confirmBulkDelete = true },
            )
        }
    }
    if (confirmBulkDelete && picking) {
        val count = livePicked.size
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text(pluralStringResource(R.plurals.pick_delete_title, count, count)) },
            text = { Text(stringResource(R.string.pick_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    for (pack in livePicked) store.deletePack(pack.id)
                    pickedIds = emptySet()
                    revision++
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    SettingsGroup(
        stringResource(R.string.import_sticker_packs_section_title),
        info = stringResource(R.string.import_stickers_caption),
    ) {
        if (packs.isEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.import_sticker_packs_empty_title),
                    subtitle = stringResource(R.string.import_sticker_packs_empty_subtitle),
                )
            }
        }
        for (pack in packs) {
            item(key = pack.id) {
                HighlightableItem(pack.id) {
                    StickerPackRow(
                        pack = pack,
                        fileFor = { store.fileFor(pack.id, it) },
                        onOpen = { onNavigate(stickerPackRoute(pack.id)) },
                        onExport = {
                            pendingExport = pack
                            exportLauncher.launch(StickerPackFile.fileName(pack))
                        },
                        onPublish = { publishing = pack }.takeIf { pack.stickers.isNotEmpty() },
                        onDelete = { confirmDelete = pack },
                        picking = picking,
                        picked = pack.id in pickedIds,
                        onPick = { pickedIds = pickedIds.toggled(pack.id) },
                    )
                }
            }
        }
    }

    if (!picking) RegisterAddFab(stringResource(R.string.import_sticker_pack_new_title)) { newPackName = "" }
    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.stickergen_title),
                subtitle = stringResource(R.string.stickergen_entry_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.stickergen_title,
                onClick = { onNavigate(STICKER_GENERATOR_ROUTE) },
            )
        }
        item { AddonStoreRow(AddonType.Stickers, onNavigate) }
        item {
            WmRow(
                title = stringResource(R.string.import_signal_row_title),
                subtitle = stringResource(R.string.import_signal_row_subtitle),
                icon = SymbolIcons.StickerAdd,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_signal_row_title,
                onClick = { onNavigate(SIGNAL_STICKERS_ROUTE) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.import_telegram_row_title),
                subtitle = stringResource(R.string.import_telegram_row_subtitle),
                icon = SymbolIcons.StickerAdd,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_telegram_row_title,
                onClick = { onNavigate(TELEGRAM_STICKERS_ROUTE) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.import_whatsapp_row_title),
                subtitle = stringResource(R.string.import_whatsapp_row_subtitle),
                icon = SymbolIcons.StickerAdd,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_whatsapp_row_title,
                onClick = { onNavigate(WHATSAPP_STICKERS_ROUTE) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.import_sticker_pack_import_title),
                subtitle = stringResource(R.string.import_sticker_pack_import_subtitle),
                icon = Icons.Outlined.FileOpen,
                accent = routeAccent("sticker_packs"),
                onClick = { importLauncher.launch(StickerPackFile.IMPORT_MIME_TYPES + PackBundle.MIME_TYPE) },
            )
        }
    }

    newPackName?.let { draft ->
        NameDialog(
            title = stringResource(R.string.import_sticker_pack_new_title),
            value = draft,
            onValueChange = { newPackName = it },
            onDismiss = { newPackName = null },
            onConfirm = {
                val created = store.createPack(draft)
                revision++
                newPackName = null
                if (created == null) {
                    message = context.resources.getQuantityString(
                        R.plurals.import_sticker_packs_full,
                        StickerPackStore.MAX_PACKS,
                        StickerPackStore.MAX_PACKS,
                    )
                } else {
                    onNavigate(stickerPackRoute(created.id))
                }
            },
        )
    }

    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.import_sticker_pack_delete_title, pack.name)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.import_sticker_pack_delete_body,
                        pack.stickers.size,
                        pack.stickers.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.deletePack(pack.id)
                    revision++
                    confirmDelete = null
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

@Composable
private fun StickerPackRow(
    pack: StickerPack,
    fileFor: (CustomSticker) -> File?,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    /** Null for an empty pack, which the community repository would refuse. */
    onPublish: (() -> Unit)?,
    onDelete: () -> Unit,
    /** Ticks or unticks the row for the dock's bulk actions. */
    onPick: () -> Unit,
    picking: Boolean,
    picked: Boolean,
) {
    val loader = rememberMediaImageLoader()
    // No `flightTo`: the row's title is the pack's name and the page it opens
    // is headed "Sticker pack", so a title flight would stretch one word into
    // a different one. The page would have to wear the pack's name for this to
    // read as one thing opening, and the name lives in a file-backed store the
    // nav graph cannot follow a rename in.
    WmRow(
        title = pack.name,
        onClick = if (picking) onPick else onOpen,
        onLongClick = onPick,
        leading = if (picking) {
            { Checkbox(checked = picked, onCheckedChange = null) }
        } else {
            null
        },
        supporting = {
            Column {
                Text(
                    if (pack.stickers.isEmpty()) {
                        stringResource(R.string.import_sticker_pack_empty_label)
                    } else {
                        pluralStringResource(
                            R.plurals.import_sticker_count,
                            pack.stickers.size,
                            pack.stickers.size,
                        )
                    },
                )
                if (pack.stickers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (sticker in pack.stickers.take(6)) {
                            AsyncImage(
                                model = fileFor(sticker),
                                contentDescription = null,
                                imageLoader = loader,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                }
            }
        },
        swipeActions = if (picking) emptyList() else listOf(
            SwipeAction(
                Icons.Outlined.Share,
                stringResource(R.string.import_sticker_pack_export_desc, pack.name),
                onClick = onExport,
            ),
            deleteSwipeAction(stringResource(R.string.import_sticker_pack_delete_desc, pack.name), onDelete),
        ),
        trailing = if (picking) null else {
            {
            Row {
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(
                            R.string.import_sticker_pack_export_desc,
                            pack.name,
                        ),
                    )
                }
                if (onPublish != null) {
                    IconButton(onClick = onPublish) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = stringResource(R.string.community_publish_sticker_pack_desc, pack.name),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(
                            R.string.import_sticker_pack_delete_desc,
                            pack.name,
                        ),
                    )
                }
            }
            }
        },
    )
}

/**
 * One pack: rename it, add stickers from photos, edit or remove each one.
 *
 * @param openPicker put the photo picker up as the page opens, for the
 *   keyboard's add button (#281). Once per visit: coming back from the editor
 *   or from a rotation lands on the page, not on a second picker.
 */
@Composable
internal fun StickerPackScreen(
    packId: String,
    openPicker: Boolean = false,
    /** A sticker whose title and keywords open for editing at once: the keyboard's hold sheet (#1157). */
    editStickerId: String? = null,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    var revision by remember { mutableIntStateOf(0) }
    val pack = remember(revision) { store.pack(packId) }
    val allPacks = remember(revision) { store.packs() }
    val loader = rememberMediaImageLoader()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CustomSticker?>(null) }
    // Once, on the way in: the edit the keyboard asked for, not again after it closes.
    var editAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editStickerId) {
        if (!editAsked && editStickerId != null) {
            editAsked = true
            editing = pack?.stickers?.firstOrNull { it.id == editStickerId }
        }
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    // A long press starts selecting stickers to delete together (#965); a
    // sticker that went meanwhile leaves the selection with it.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulk by remember { mutableStateOf(false) }
    // The selection on its way to another pack (#1061): true moves it, false copies it.
    var sending by remember { mutableStateOf<Boolean?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // One still image goes through the editor first; a batch is added
            // as it always was, because an editor thirty times over is not a
            // flow anybody wants. An animation skips the editor either way:
            // it is stored frame for frame and there is no encoder to put it
            // back together.
            val single = uris.singleOrNull()?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.readBytesCapped(uri)
                    }.getOrNull()
                }
            }
            if (single != null && !StickerImage.isAnimatedSource(single)) {
                busy = false
                StickerEditHandoff.current = StickerEditRequest(packId, single, stickerId = null)
                onNavigate(STICKER_EDITOR_ROUTE)
                return@launch
            }
            val outcome = addPickedStickers(context, store, packId, uris)
            busy = false
            revision++
            message = outcome.describe(context)
        }
    }

    // Saved with the back-stack entry, so the editor's pop back to this page
    // finds it already spent.
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openPicker && !pickerOpened && pack != null) {
            pickerOpened = true
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    val selected = pack?.stickers.orEmpty().mapNotNullTo(HashSet()) { it.id.takeIf { id -> id in picked } }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { picked = emptySet() }
    val elsewhere = store.packs().filter { it.id != packId }
    if (selecting && pack != null) {
        RegisterDock {
            StickerSelectionBar(
                count = selected.size,
                allPicked = selected.size == pack.stickers.size,
                onClear = { picked = emptySet() },
                onSelectAll = { picked = pack.stickers.mapTo(HashSet()) { it.id } },
                onDelete = { confirmBulk = true },
                onMove = { sending = true }.takeIf { elsewhere.isNotEmpty() },
                onCopy = { sending = false }.takeIf { elsewhere.isNotEmpty() },
            )
        }
    }

    if (pack == null) {
        CaptionText(stringResource(R.string.import_sticker_pack_missing))
        return
    }


    SettingsGroup(
        stringResource(R.string.import_sticker_pack_section_title),
        info = stringResource(R.string.import_sticker_pack_caption),
    ) {
        item {
            WmRow(
                title = pack.name,
                subtitle = stringResource(R.string.import_sticker_pack_rename_subtitle),
                icon = SettingsRowIcons[R.string.import_sticker_pack_rename_subtitle],
                onClick = { renaming = pack.name },
            )
        }
    }

    SettingsGroup {
        item {
            WmRow(
                title = stringResource(
                    if (busy) {
                        R.string.import_stickers_adding_title
                    } else {
                        R.string.import_stickers_add_title
                    },
                ),
                subtitle = stringResource(
                    R.string.import_stickers_used_subtitle,
                    pack.stickers.size,
                    StickerPackStore.MAX_STICKERS_PER_PACK,
                ),
                icon = Icons.Outlined.Add,
                accent = routeAccent("sticker_packs"),
                enabled = !busy,
                onClick = {
                    pickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.stickergen_entry_title),
                subtitle = stringResource(R.string.stickergen_entry_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                accent = routeAccent("sticker_packs"),
                enabled = !busy,
                onClick = { onNavigate("$STICKER_GENERATOR_ROUTE?pack=${Uri.encode(packId)}") },
            )
        }
    }

    if (pack.stickers.isEmpty()) {
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.import_stickers_empty_title),
                    subtitle = stringResource(R.string.import_stickers_empty_subtitle),
                )
            }
        }
    } else {
        // Read out for a sticker with no name of its own. Resolved once here
        // rather than in every cell of the grid.
        val unnamedSticker = stringResource(R.string.import_sticker_desc_fallback)
        val reduceMotion = LocalReduceMotion.current
        if (pack.stickers.size > 1) CaptionText(stringResource(R.string.import_stickers_select_hint))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(((pack.stickers.size + 2) / 3 * 104).coerceAtMost(640).dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pack.stickers, key = { it.id }) { sticker ->
                // Deleting, moving out or nudging a sticker along moves the
                // cells after it; they slide to their new places rather than
                // the grid redrawing in the new order.
                // While selecting, a press ticks the sticker instead of
                // opening it, the way a long press started the selection.
                StickerPickCell(
                    checked = if (selecting) sticker.id in selected else null,
                    onClick = {
                        if (selecting) picked = selected.withToggled(sticker.id) else editing = sticker
                    },
                    onLongClick = { picked = selected.withToggled(sticker.id) },
                    modifier = gridItemMotion(reduceMotion),
                ) {
                    AsyncImage(
                        model = store.fileFor(packId, sticker),
                        contentDescription = sticker.name.ifBlank { unnamedSticker },
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!selecting) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    sending?.let { move ->
        val count = selected.size
        AlertDialog(
            onDismissRequest = { sending = null },
            title = {
                val title = if (move) R.plurals.import_stickers_move_title else R.plurals.import_stickers_copy_title
                Text(pluralStringResource(title, count, count))
            },
            text = {
                LazyColumn {
                    items(elsewhere.size) { at ->
                        val target = elsewhere[at]
                        TextButton(
                            onClick = {
                                val sent = if (move) {
                                    store.moveStickers(packId, selected, target.id)
                                } else {
                                    store.copyStickers(packId, selected, target.id)
                                }
                                if (sent < count) message = context.getString(R.string.import_sticker_pack_full)
                                picked = emptySet()
                                sending = null
                                revision++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sending = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (confirmBulk) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text(pluralStringResource(R.plurals.import_stickers_delete_selected_title, count, count)) },
            text = { Text(stringResource(R.string.import_stickers_delete_selected_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.removeStickers(packId, selected)
                    picked = emptySet()
                    confirmBulk = false
                    revision++
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulk = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    renaming?.let { draft ->
        NameDialog(
            title = stringResource(R.string.import_sticker_pack_rename_title),
            value = draft,
            onValueChange = { renaming = it },
            onDismiss = { renaming = null },
            onConfirm = {
                store.renamePack(packId, draft)
                revision++
                renaming = null
            },
        )
    }

    editing?.let { sticker ->
        StickerEditDialog(
            sticker = sticker,
            otherPacks = allPacks.filter { it.id != packId },
            onDismiss = { editing = null },
            onSave = { title, keywords ->
                store.updateSticker(packId, sticker.id, title, keywords)
                revision++
                editing = null
            },
            onEditImage = {
                editing = null
                scope.launch {
                    // The kept original when there is one: an edit flattens
                    // the picture, so re-opening the sticker itself would
                    // crop a crop and erase an erasure. Stickers from a
                    // provider, from an imported pack or from before
                    // originals were kept re-open themselves.
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching {
                            (store.originalFor(sticker.id) ?: store.fileFor(packId, sticker))
                                ?.readBytes()
                        }.getOrNull()
                    }
                    if (bytes == null) {
                        message = context.getString(R.string.import_sticker_editor_save_error)
                    } else {
                        StickerEditHandoff.current =
                            StickerEditRequest(packId, bytes, stickerId = sticker.id)
                        onNavigate(STICKER_EDITOR_ROUTE)
                    }
                }
            },
            onMove = { targetId ->
                if (!store.moveSticker(packId, sticker.id, targetId)) {
                    message = context.getString(R.string.import_sticker_pack_full)
                }
                revision++
                editing = null
            },
            onReorder = { delta ->
                store.reorderSticker(packId, sticker.id, delta)
                revision++
                editing = null
            },
            onDelete = {
                store.removeSticker(packId, sticker.id)
                revision++
                editing = null
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

/** What came of an import, as the one message the screen shows for it. */
internal fun StickerImportResult.describe(context: Context): String = when (this) {
    is StickerImportResult.Imported -> buildString {
        append(
            context.resources.getQuantityString(
                R.plurals.import_stickers_done,
                pack.stickers.size,
                pack.name,
                pack.stickers.size,
            ),
        )
        if (repairs.isNotEmpty()) {
            append("\n\n").append(context.getString(R.string.import_repairs_title))
            // The reader hands back a resource and its arguments, so the note
            // is worded here.
            for (line in repairs.take(MAX_SHOWN_REPAIRS)) append("\n• ${line.resolve(context)}")
            appendMoreRepairs(context, repairs.size - MAX_SHOWN_REPAIRS)
        }
    }
    StickerImportResult.NotAStickerPack -> context.getString(R.string.import_not_a_sticker_pack)
    is StickerImportResult.NoStickers -> buildString {
        append(context.getString(R.string.import_stickers_none_read))
        for (line in repairs.take(MAX_SHOWN_REPAIRS)) append("\n• ${line.resolve(context)}")
        appendMoreRepairs(context, repairs.size - MAX_SHOWN_REPAIRS)
    }
    StickerImportResult.TooManyPacks -> context.resources.getQuantityString(
        R.plurals.import_stickers_too_many,
        StickerPackStore.MAX_PACKS,
        StickerPackStore.MAX_PACKS,
    )
    StickerImportResult.Failed -> context.getString(R.string.import_file_unreadable)
}

private fun StringBuilder.appendMoreRepairs(context: Context, extra: Int) {
    if (extra <= 0) return
    append("\n• ")
    append(context.resources.getQuantityString(R.plurals.import_repairs_more, extra, extra))
}

/** How many photos one trip through the picker may add. */
private const val MAX_PICK = 30

/** A pack that dropped every sticker has one reason per sticker; show a few. */
private const val MAX_SHOWN_REPAIRS = 5

/**
 * Adds every picture at [uris] to [packId], stopping at the first that fills
 * the pack. Reads and encodes on the IO dispatcher.
 *
 * Shared by the pack page's photo picker and the WhatsApp screen's document
 * picker: two ways of choosing files, one way of adding them.
 */
internal suspend fun addPickedStickers(
    context: Context,
    store: StickerPackStore,
    packId: String,
    uris: List<Uri>,
): AddOutcome = withContext(Dispatchers.IO) {
    var added = 0
    var tooLarge = 0
    var unreadable = 0
    var full = false
    for (uri in uris) {
        val bytes = runCatching {
            context.contentResolver.readBytesCapped(uri)
        }.getOrNull()
        if (bytes == null) {
            unreadable++
            continue
        }
        when (val processed = StickerImage.process(bytes)) {
            is StickerImage.Result.Ok ->
                when (
                    store.addSticker(
                        packId,
                        processed.sticker,
                        // Kept so "Edit image" starts from the photo and not
                        // from the 512-pixel copy of it this batch just made.
                        original = StickerImage.encodeOriginal(bytes),
                    )
                ) {
                    is StickerAddResult.Added -> added++
                    StickerAddResult.PackFull -> full = true
                    else -> unreadable++
                }
            StickerImage.Result.TooLarge -> tooLarge++
            StickerImage.Result.NotAnImage -> unreadable++
        }
        if (full) break
    }
    AddOutcome(added, tooLarge, unreadable, full)
}

internal data class AddOutcome(
    val added: Int,
    val tooLarge: Int,
    val unreadable: Int,
    val packFull: Boolean,
) {
    /** One message, in the language [context] is configured for. */
    fun describe(context: Context): String = buildString {
        append(
            if (added == 0) {
                context.getString(R.string.import_stickers_none_added)
            } else {
                context.resources.getQuantityString(R.plurals.import_stickers_added, added, added)
            },
        )
        if (tooLarge > 0) {
            append(" ")
            append(
                context.resources.getQuantityString(
                    R.plurals.import_stickers_too_large,
                    tooLarge,
                    tooLarge,
                ),
            )
        }
        if (unreadable > 0) {
            append(" ")
            append(
                context.resources.getQuantityString(
                    R.plurals.import_stickers_unreadable,
                    unreadable,
                    unreadable,
                ),
            )
        }
        if (packFull) append(" ").append(context.getString(R.string.import_sticker_pack_now_full))
    }
}

@Composable
private fun StickerEditDialog(
    sticker: CustomSticker,
    otherPacks: List<StickerPack>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
    onEditImage: () -> Unit,
    onMove: (String) -> Unit,
    onReorder: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    // Two fields, because they do two jobs while typing (#329): the title
    // offers the sticker only once it is typed out in full, while a keyword
    // can be any shorter handle for it, or an emoji. Both feed the panel's
    // search alike. See [StickerKeywords] for how the keywords line is read.
    var title by remember(sticker.id) { mutableStateOf(sticker.name) }
    var keywords by remember(sticker.id) { mutableStateOf(StickerKeywords.format(sticker.keywords)) }
    var moveOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_sticker_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.import_sticker_title_label)) },
                    supportingText = {
                        Text(stringResource(R.string.import_sticker_title_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text(stringResource(R.string.import_sticker_keywords_label)) },
                    supportingText = {
                        Text(stringResource(R.string.import_sticker_keywords_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Hidden, not disabled, for an animation: there is no still to
                // edit and a greyed row would only raise the question.
                if (!sticker.animated) {
                    TextButton(onClick = onEditImage) {
                        Text(stringResource(R.string.import_sticker_editor_edit_action))
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { onReorder(-1) }) {
                        Text(stringResource(R.string.import_sticker_move_up))
                    }
                    TextButton(onClick = { onReorder(1) }) {
                        Text(stringResource(R.string.import_sticker_move_down))
                    }
                }
                if (otherPacks.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { moveOpen = true }) {
                            Text(stringResource(R.string.import_sticker_move_pack))
                        }
                        DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                            for (pack in otherPacks) {
                                DropdownMenuItem(
                                    text = {
                                        Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    onClick = {
                                        moveOpen = false
                                        onMove(pack.id)
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.import_sticker_delete_action))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title.trim(), StickerKeywords.parse(keywords))
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
internal fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.import_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

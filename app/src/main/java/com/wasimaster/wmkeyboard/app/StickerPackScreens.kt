package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.stickers.CustomSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerAddResult
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.StickerSearchWords
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Names a pack whose own file gives no name.
        val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { StickerPackFile.import(it, store, fallbackName) }
                }.getOrDefault(StickerImportResult.Failed)
            }
            revision++
            message = when (result) {
                is StickerImportResult.Imported -> buildString {
                    append(
                        context.resources.getQuantityString(
                            R.plurals.import_stickers_done,
                            result.pack.stickers.size,
                            result.pack.name,
                            result.pack.stickers.size,
                        ),
                    )
                    if (result.repairs.isNotEmpty()) {
                        append("\n\n").append(context.getString(R.string.import_repairs_title))
                        // The reader hands back a resource and its arguments,
                        // so the note is worded here.
                        for (line in result.repairs) append("\n• ${line.resolve(context)}")
                    }
                }
                StickerImportResult.NotAStickerPack ->
                    context.getString(R.string.import_not_a_sticker_pack)
                is StickerImportResult.NoStickers -> buildString {
                    append(context.getString(R.string.import_stickers_none_read))
                    for (line in result.repairs.take(MAX_SHOWN_REPAIRS)) {
                        append("\n• ${line.resolve(context)}")
                    }
                    val extra = result.repairs.size - MAX_SHOWN_REPAIRS
                    if (extra > 0) {
                        append("\n• ")
                        append(
                            context.resources.getQuantityString(
                                R.plurals.import_repairs_more,
                                extra,
                                extra,
                            ),
                        )
                    }
                }
                StickerImportResult.TooManyPacks ->
                    context.resources.getQuantityString(
                        R.plurals.import_stickers_too_many,
                        StickerPackStore.MAX_PACKS,
                        StickerPackStore.MAX_PACKS,
                    )
                StickerImportResult.Failed -> context.getString(R.string.import_file_unreadable)
            }
        }
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
            item {
                HighlightableItem(pack.id) {
                    StickerPackRow(
                        pack = pack,
                        fileFor = { store.fileFor(pack.id, it) },
                        onOpen = { onNavigate("sticker_pack/${pack.id}") },
                        onExport = {
                            pendingExport = pack
                            exportLauncher.launch(StickerPackFile.fileName(pack))
                        },
                        onDelete = { confirmDelete = pack },
                    )
                }
            }
        }
    }

    RegisterAddFab(stringResource(R.string.import_sticker_pack_new_title)) { newPackName = "" }
    SettingsGroup {
        item { AddonStoreRow(AddonType.Stickers, onNavigate) }
        item {
            WmRow(
                title = stringResource(R.string.import_sticker_pack_import_title),
                subtitle = stringResource(R.string.import_sticker_pack_import_subtitle),
                icon = Icons.Outlined.FileOpen,
                accent = routeAccent("sticker_packs"),
                onClick = { importLauncher.launch(StickerPackFile.IMPORT_MIME_TYPES) },
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
                    onNavigate("sticker_pack/${created.id}")
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
    onDelete: () -> Unit,
) {
    val loader = rememberMediaImageLoader()
    WmRow(
        title = pack.name,
        onClick = onOpen,
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
        trailing = {
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
        },
    )
}

/** One pack: rename it, add stickers from photos, edit or remove each one. */
@Composable
internal fun StickerPackScreen(packId: String, onNavigate: (String) -> Unit) {
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
    var renaming by remember { mutableStateOf<String?>(null) }

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
                        context.contentResolver.requireInputStream(uri).use { it.readBytes() }
                    }.getOrNull()
                }
            }
            if (single != null && !StickerImage.isAnimatedSource(single)) {
                busy = false
                StickerEditHandoff.current = StickerEditRequest(packId, single, stickerId = null)
                onNavigate(STICKER_EDITOR_ROUTE)
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                var added = 0
                var tooLarge = 0
                var unreadable = 0
                var full = false
                for (uri in uris) {
                    val bytes = runCatching {
                        context.contentResolver.requireInputStream(uri).use { it.readBytes() }
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
                                    // Kept so "Edit image" starts from the
                                    // photo and not from the 512-pixel copy
                                    // of it this batch just made.
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
            busy = false
            revision++
            message = outcome.describe(context)
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
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { editing = sticker },
                ) {
                    AsyncImage(
                        model = store.fileFor(packId, sticker),
                        contentDescription = sticker.name.ifBlank { unnamedSticker },
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
            onSave = { name, emojis ->
                store.updateSticker(packId, sticker.id, name, emojis)
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

/** How many photos one trip through the picker may add. */
private const val MAX_PICK = 30

/** A pack that dropped every sticker has one reason per sticker; show a few. */
private const val MAX_SHOWN_REPAIRS = 5

private data class AddOutcome(
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
    // One field, not a name plus "emoji tags": both feed the same search, and
    // the first word stays the name so the label a screen reader gets is one
    // word and not the whole list. See [StickerSearchWords].
    var words by remember(sticker.id) { mutableStateOf(StickerSearchWords.of(sticker)) }
    var moveOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_sticker_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it },
                    label = { Text(stringResource(R.string.import_sticker_search_words_label)) },
                    supportingText = {
                        Text(stringResource(R.string.import_sticker_search_words_hint))
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
                val (name, tags) = StickerSearchWords.split(words)
                onSave(name, tags)
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun NameDialog(
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

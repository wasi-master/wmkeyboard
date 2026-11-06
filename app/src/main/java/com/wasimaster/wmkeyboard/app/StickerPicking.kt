package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.stickers.StagedStickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.StickerStageResult
import com.wasimaster.wmkeyboard.core.stickers.whatsapp.WaStickersFile
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Picking stickers (#965), both ways round: which stickers of a pack to bring
 * in before any of them is converted, and which stickers of a pack of the
 * user's own to delete together.
 */

/** [this] with [item] ticked if it was not, and unticked if it was. */
internal fun <T> Set<T>.withToggled(item: T): Set<T> = if (item in this) this - item else this + item

/**
 * One square of a sticker grid that can be ticked. [onClick] null makes it a
 * plain picture.
 *
 * [checked] null is a grid that is not picking, and the cell draws no mark at
 * all: the pack page outside selection mode looks as it always did. Otherwise
 * a ticked cell is outlined in the accent colour with a filled tick in its
 * corner, and an unticked one has an empty ring there, so it reads as
 * something to tick rather than something left out by mistake.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StickerPickCell(
    checked: Boolean?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (checked == true) Modifier.border(3.dp, accent, shape) else Modifier)
            .then(
                // A preview with nothing to tick is a picture, not a button.
                if (onClick == null) Modifier else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
            )
            .semantics {
                if (checked != null) {
                    selected = checked
                    role = Role.Checkbox
                }
            },
    ) {
        content()
        if (checked != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(
                        if (checked) {
                            Modifier.background(accent)
                        } else {
                            Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * How many of a pack's stickers are ticked, with Select all and Select none
 * beside it. Every sticker starts ticked, so Select none is the quick way to
 * a pack where only three of forty are wanted.
 */
@Composable
internal fun StickerPickBar(
    chosen: Int,
    total: Int,
    onAll: () -> Unit,
    onNone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.import_stickers_chosen, chosen, chosen, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAll, enabled = enabled && chosen < total) {
            Text(stringResource(CommonR.string.common_select_all))
        }
        TextButton(onClick = onNone, enabled = enabled && chosen > 0) {
            Text(stringResource(R.string.import_stickers_select_none))
        }
    }
}

/** One sticker the pick page shows: what the image loader reads, and how it is read out. */
internal class PickSticker(val model: Any?, val label: String)

/**
 * The whole screen given to choosing which of a pack's stickers to import
 * (#965), before any of them is converted. Every sticker starts ticked;
 * pressing one unticks it.
 *
 * [progress] is set once the user has pressed Add: the grid stays where it is
 * and the button row turns into the adding line, so the user watches the
 * stickers they chose go in rather than a blank dialog.
 */
@Composable
internal fun StickerPickPage(
    title: String,
    subtitle: String?,
    stickers: List<PickSticker>,
    chosen: Set<Int>,
    onChosenChange: (Set<Int>) -> Unit,
    progress: Pair<Int, Int>?,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val working = progress != null
    Dialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !working) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.import_stickers_pick_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                StickerPickBar(
                    chosen = chosen.size,
                    total = stickers.size,
                    onAll = { onChosenChange(stickers.indices.toSet()) },
                    onNone = { onChosenChange(emptySet()) },
                    enabled = !working,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                )
                val loader = rememberMediaImageLoader()
                val unnamed = stringResource(R.string.import_sticker_desc_fallback)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers.indices.toList(), key = { it }) { index ->
                        val sticker = stickers[index]
                        StickerPickCell(
                            checked = index in chosen,
                            onClick = { if (!working) onChosenChange(chosen.withToggled(index)) },
                        ) {
                            AsyncImage(
                                model = sticker.model,
                                contentDescription = sticker.label.ifBlank { unnamed },
                                imageLoader = loader,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (progress != null) {
                            Column {
                                Text(
                                    stringResource(
                                        R.string.import_stickers_adding_progress,
                                        progress.first,
                                        progress.second,
                                    ),
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.second > 0) progress.first.toFloat() / progress.second else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        } else {
                            Button(
                                onClick = onAdd,
                                enabled = chosen.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    pluralStringResource(R.plurals.import_stickers_add_chosen, chosen.size, chosen.size),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reads a sticker pack file at [uri] for the pick page: the app's own
 * `.wmstickers` first, then a WhatsApp `.wastickers`, which has no manifest
 * to fail on and so is only tried once the first says the file is not one of
 * its own. Blocking.
 */
internal fun stageStickerFile(context: Context, store: StickerPackStore, uri: Uri): StickerStageResult {
    // Names a pack whose own file gives no name.
    val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
    val own = runCatching {
        context.contentResolver.requireInputStream(uri).use { StickerPackFile.stage(it, store, fallbackName) }
    }.getOrElse { StickerStageResult.Done(StickerImportResult.Failed) }
    val notOurs = own is StickerStageResult.Done && own.result == StickerImportResult.NotAStickerPack
    if (!notOurs) return own
    return runCatching {
        context.contentResolver.requireInputStream(uri).use { WaStickersFile.stage(it, store, fallbackName) }
    }.getOrElse { StickerStageResult.Done(StickerImportResult.Failed) }
}

/**
 * Imports every pack in [uris] whole, with no pick page: several files picked
 * at once, or a zip of packs from Export selected. Each file is read the way
 * [stageStickerFile] reads one, and a zip hands over each pack inside it.
 * Returns each file's (or zipped pack's) name with how it went. Blocking.
 */
internal fun importStickerFilesWhole(
    context: Context,
    store: StickerPackStore,
    uris: List<Uri>,
): List<Pair<String, StickerImportResult>> = buildList {
    val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
    for (uri in uris) {
        val name = WMFileTypes.displayName(context, uri)
        if (PackBundle.isBundle(context, uri, StickerPackFile.FILE_EXTENSION)) {
            runCatching {
                PackBundle.forEachPack(context, uri, StickerPackFile.FILE_EXTENSION) { entry, input ->
                    val result = input?.use {
                        runCatching { StickerPackFile.import(it, store, fallbackName) }.getOrDefault(StickerImportResult.Failed)
                    } ?: StickerImportResult.Failed
                    add(entry to result)
                }
            }.onFailure { add(name to StickerImportResult.Failed) }
        } else {
            val result = when (val staged = stageStickerFile(context, store, uri)) {
                is StickerStageResult.Done -> staged.result
                is StickerStageResult.Ready -> runCatching {
                    staged.pack.adopt(store, staged.pack.everything())
                }.getOrDefault(StickerImportResult.Failed)
            }
            add(name to result)
        }
    }
}

/**
 * A sticker pack file at [uri], from reading it to the report: a reading
 * spinner, then the pick page, then the import of what was ticked.
 * [onFinished] gets the report to show, or null when the user backed out of
 * the pick page and nothing was imported.
 *
 * Shared by every way a pack file comes in: the Sticker packs screen's
 * Import a pack row, the WhatsApp screen's file row, and a file opened with
 * the app from a file manager or a chat.
 */
@Composable
internal fun StickerFileImport(uri: Uri, onFinished: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    var staged by remember(uri) { mutableStateOf<StagedStickerPack?>(null) }
    var chosen by remember(uri) { mutableStateOf<Set<Int>>(emptySet()) }
    var progress by remember(uri) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Set while the staged pack still owns its directory, so leaving the page
    // any way at all lets go of the pictures. Adopting clears it first.
    var owned by remember(uri) { mutableStateOf<StagedStickerPack?>(null) }
    DisposableEffect(uri) { onDispose { owned?.discard() } }

    LaunchedEffect(uri) {
        when (val read = withContext(Dispatchers.IO) { stageStickerFile(context, store, uri) }) {
            is StickerStageResult.Done -> onFinished(read.result.describe(context))
            is StickerStageResult.Ready -> {
                owned = read.pack
                // Every sticker that has a picture, ticked: the import as it
                // always was is one press away.
                chosen = read.pack.pickable.indices.toSet()
                staged = read.pack
            }
        }
    }

    val pack = staged
    if (pack == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_stickers_reading)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        return
    }
    // The page counts the stickers it shows; the pack counts every sticker its
    // file lists, pictures or not. This maps one onto the other.
    val pickable = remember(pack) { pack.pickable }
    val unnamed = stringResource(R.string.import_sticker_desc_fallback)
    val shown = remember(pack) {
        pickable.map { index ->
            val sticker = pack.stickers[index]
            val label = sticker.incoming.name.ifBlank { sticker.incoming.emojis.firstOrNull().orEmpty() }
            PickSticker(sticker.file, label.ifBlank { unnamed })
        }
    }
    // A WhatsApp pack is said to be one, with who made it: that is shown here
    // and nowhere else, because a pack of this app's keeps no author.
    val count = pluralStringResource(R.plurals.import_sticker_count, pickable.size, pickable.size)
    val subtitle = when {
        pack.source != StickerPack.whatsappSource() -> null
        pack.author.isBlank() -> stringResource(R.string.import_whatsapp_file_body, count)
        else -> stringResource(R.string.import_whatsapp_file_body_author, count, pack.author)
    }
    StickerPickPage(
        title = pack.name,
        subtitle = subtitle,
        stickers = shown,
        chosen = chosen,
        onChosenChange = { chosen = it },
        progress = progress,
        onAdd = {
            owned = null
            progress = 0 to chosen.size
            val picked = chosen.mapTo(HashSet()) { pickable[it] }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        pack.adopt(store, picked) { done, total -> progress = done to total }
                    }.getOrDefault(StickerImportResult.Failed)
                }
                onFinished(result.describe(context))
            }
        },
        onDismiss = {
            owned?.discard()
            owned = null
            onFinished(null)
        },
    )
}

/**
 * The bar docked along the bottom of a pack's page while stickers in it are
 * selected: how many, Move and Copy when there is another pack to send them
 * to, Select all, and Delete, which asks first.
 */
@Composable
internal fun StickerSelectionBar(
    count: Int,
    allPicked: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
            }
            Text(
                pluralStringResource(R.plurals.import_stickers_selected, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (onMove != null) {
                IconButton(onClick = onMove) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = stringResource(R.string.import_stickers_move_selected))
                }
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.import_stickers_copy_selected))
                }
            }
            IconButton(onClick = onSelectAll, enabled = !allPicked) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(CommonR.string.common_select_all))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.import_stickers_delete_selected))
            }
        }
    }
}

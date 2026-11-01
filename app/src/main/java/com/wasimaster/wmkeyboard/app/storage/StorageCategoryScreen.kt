package com.wasimaster.wmkeyboard.app.storage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ChartIntroScope
import com.wasimaster.wmkeyboard.app.CountingText
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.app.RunGlyph
import com.wasimaster.wmkeyboard.app.RunPhase
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.deleteSwipeAction
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.stateSwapTransform
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One category, item by item.
 *
 * Deliberately generic: every category gets the same screen rather than
 * twenty-odd bespoke ones, because the question is always the same — what is in
 * here, how big is each of them, and can this one go. Where a category already
 * has a proper management screen (voice models, sticker packs, plugins) this
 * one links across to it instead of trying to be it.
 */
@Composable
internal fun StorageCategoryScreen(
    categoryId: String,
    repository: SettingsRepository,
    onOpenRoute: (String) -> Unit,
) {
    // The sizes count up when the screen opens, as the Storage screen's do;
    // coming back to it on the same visit finds them already there.
    ChartIntroScope { StorageCategoryBody(categoryId, repository, onOpenRoute) }
}

/** What the item list is showing: nothing yet, the empty note, or the items. */
private enum class ItemsShown { LOADING, EMPTY, LIST }

@Composable
private fun StorageCategoryBody(
    categoryId: String,
    repository: SettingsRepository,
    onOpenRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val category = remember(categoryId) { StorageCategories.byId(categoryId) } ?: return
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmItem by remember { mutableStateOf<StorageItem?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    // Clearing the whole category: a spinner on its button while the files go,
    // then the tick for a moment before the button folds away.
    var clearPhase by remember(categoryId) { mutableStateOf(RunPhase.IDLE) }
    LaunchedEffect(clearPhase) {
        if (clearPhase == RunPhase.DONE) {
            delay(CLEAR_DONE_HOLD_MS)
            clearPhase = RunPhase.IDLE
        }
    }
    // Items on their way out: hidden the moment the deletion is confirmed, so
    // the row folds away while the files go rather than a beat later when the
    // list is read again. Cleared on that read — an item that is still there
    // (the deletion failed) comes back.
    var leaving by remember(categoryId) { mutableStateOf(emptySet<String>()) }

    // Null until the first read lands, so the screen does not say "empty" for
    // the moment it takes to look. A re-read keeps the old list until the new
    // one is in.
    val loaded by produceState<List<StorageItem>?>(null, categoryId, revision) {
        value = withContext(Dispatchers.IO) {
            category.items(StorageEnv(context, repository, storageRoots(context)))
                .sortedByDescending { it.bytes }
        }
    }
    val items = loaded.orEmpty()

    LaunchedEffect(loaded) { leaving = emptySet() }

    fun perform(clearingAll: Boolean = false, work: suspend (StorageEnv) -> Unit) {
        scope.launch {
            busy = true
            if (clearingAll) clearPhase = RunPhase.RUNNING
            withContext(Dispatchers.IO) {
                work(StorageEnv(context, repository, storageRoots(context)))
            }
            // The Storage screen paints from the last measurement, so it has to
            // be told that measurement is now wrong — otherwise going back
            // shows the size of something that has just been deleted.
            StorageSnapshot.invalidate()
            busy = false
            if (clearingAll) clearPhase = RunPhase.DONE
            revision++
        }
    }

    CaptionText(stringResource(category.subtitle))
    if (category.note != 0) CaptionText(stringResource(category.note))

    // The empty note and the list hand over to each other — the last item
    // deleted, or the first read landing — rather than swapping in a frame.
    AnimatedContent(
        targetState = when {
            loaded == null -> ItemsShown.LOADING
            items.isEmpty() -> ItemsShown.EMPTY
            else -> ItemsShown.LIST
        },
        transitionSpec = { stateSwapTransform(reduceMotion) },
        modifier = Modifier.fillMaxWidth(),
        label = "storageItems",
    ) { shown ->
        when (shown) {
            ItemsShown.LOADING -> Unit
            ItemsShown.EMPTY -> CaptionText(stringResource(R.string.storage_detail_empty))
            ItemsShown.LIST -> {
                // A leading slot on every row or on none of them. Thumbnails on
                // the picture rows and nothing on the JSON beside them would
                // step the headlines in and out down the column, which reads as
                // a broken list rather than as two kinds of thing.
                val slotted = items.any { it.preview != null }
                SettingsGroup {
                    for (stored in items) {
                        // Keyed by the item, so a deleted one closes out of its
                        // place and the rows under it slide up into the gap,
                        // where a row matched by position would jump.
                        item(visible = stored.id !in leaving, key = stored.id) {
                            StorageItemRow(
                                item = stored,
                                accent = category.accent,
                                slotted = slotted,
                                // Also off while it folds away: the fading row
                                // still takes a press.
                                enabled = !busy && stored.id !in leaving,
                                onOpenRoute = onOpenRoute,
                            ) { if (!busy && stored.id !in leaving) confirmItem = stored }
                        }
                    }
                }
            }
        }
    }

    category.manageRoute?.let { route ->
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.storage_detail_manage_action),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    accent = category.accent,
                    flightTo = route,
                    onClick = { onOpenRoute(route) },
                )
            }
        }
    }

    // Kept while its tick shows, then folded away with nothing left to clear.
    val clearAllShown = category.clearable &&
        (items.any { it.deletable && it.id !in leaving } || clearPhase != RunPhase.IDLE)
    val clearAll: @Composable () -> Unit = {
        TextButton(
            onClick = { if (!busy && clearPhase == RunPhase.IDLE) confirmAll = true },
            enabled = !busy && clearPhase == RunPhase.IDLE,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        ) {
            RunGlyph(clearPhase, MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.storage_delete_all_desc, stringResource(category.title)),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (reduceMotion) {
        if (clearAllShown) clearAll()
    } else {
        AnimatedVisibility(
            visible = clearAllShown,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            label = "storageClearAll",
        ) { clearAll() }
    }

    confirmItem?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_item_title, item.label),
            body = stringResource(bodyForItem(category.danger), formatBytes(item.bytes)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirmItem = null },
            onConfirm = {
                confirmItem = null
                leaving = leaving + item.id
                perform { env -> category.delete(env, item) }
            },
        )
    }

    if (confirmAll) {
        val total = items.filter { it.deletable }.sumOf { it.bytes }
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_title, stringResource(category.title)),
            body = stringResource(bodyForItem(category.danger), formatBytes(total)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirmAll = false },
            onConfirm = {
                confirmAll = false
                // The rows that are going fold away now, as a single delete's
                // does, rather than when the list is read again.
                leaving = leaving + items.filter { it.deletable }.map { it.id }
                perform(clearingAll = true) { env -> category.clear(env) }
            },
        )
    }
}

@Composable
private fun StorageItemRow(
    item: StorageItem,
    accent: Color,
    slotted: Boolean,
    enabled: Boolean,
    onOpenRoute: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteDesc = stringResource(R.string.storage_delete_item_desc, item.label)
    val preview = item.preview
    WmRow(
        title = item.label.ifBlank { stringResource(R.string.storage_item_unknown_label) },
        subtitle = item.detail,
        flightTo = item.route,
        onClick = item.route?.let { route -> { onOpenRoute(route) } },
        swipeActions = if (item.deletable && enabled) listOf(deleteSwipeAction(deleteDesc, onDelete)) else emptyList(),
        leading = when {
            preview != null -> {
                { StorageThumbnail(preview, accent) }
            }
            slotted -> {
                {
                    WmIconTile(
                        if (item.directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                        accent,
                    )
                }
            }
            else -> null
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Counts up with the screen's entrance, as the Storage screen's
                // sizes do.
                CountingText(
                    target = item.bytes,
                    format = { formatBytes(it) },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (item.deletable) {
                    IconButton(onClick = onDelete, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = deleteDesc,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The picture itself in the row's icon slot.
 *
 * Drawn inside the same tile the icon rows use, so the two kinds of row line up
 * to the pixel and a picture with transparency in it — a decal, a cut-out
 * sticker — has the tile's wash behind it instead of the card.
 *
 * No content description: the row's own headline already names it, and a second
 * reading of "Key texture" is noise to a screen reader rather than help.
 */
@Composable
private fun StorageThumbnail(file: File, accent: Color) {
    WmIconTile(accent) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** How long the tick stays on Clear all before the button folds away. */
private const val CLEAR_DONE_HOLD_MS = 1_200L

private fun bodyForItem(danger: Danger): Int = when (danger) {
    Danger.NONE -> R.string.storage_confirm_free_body
    Danger.REDOWNLOAD -> R.string.storage_confirm_redownload_body
    Danger.PERSONAL, Danger.RESET -> R.string.storage_confirm_personal_body
}

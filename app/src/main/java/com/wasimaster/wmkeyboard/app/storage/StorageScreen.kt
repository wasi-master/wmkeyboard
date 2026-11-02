package com.wasimaster.wmkeyboard.app.storage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.FeatureLibrariesNavRow
import com.wasimaster.wmkeyboard.app.FeatureLibraryList
import com.wasimaster.wmkeyboard.app.LanguagePackDownloads
import com.wasimaster.wmkeyboard.app.OfflineImportRow
import com.wasimaster.wmkeyboard.app.OfflineLink
import com.wasimaster.wmkeyboard.app.SectionHeader
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.LockTarget
import com.wasimaster.wmkeyboard.app.rememberLockGuard
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.app.ChartIntroScope
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.app.rememberCount
import com.wasimaster.wmkeyboard.app.stateSwapTransform
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NativeDownloader
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * What the app is using on your device, and how to get it back.
 *
 * The headline is the system's own figure — see [scanStorage] — so this screen
 * and the App info page in the system settings never disagree. Everything below
 * it is a share of that figure, including two residual rows that hold whatever
 * the named categories do not account for. Nothing is silently dropped.
 */
@Composable
internal fun StorageScreen(
    repository: SettingsRepository,
    onOpenCategory: (String) -> Unit,
) {
    // The ring sweeps and the sizes count up when the screen opens; stepping
    // back in from a category finds them already there.
    ChartIntroScope { StorageBody(repository, onOpenCategory) }
}

@Composable
private fun StorageBody(
    repository: SettingsRepository,
    onOpenCategory: (String) -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showEmpty by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<StorageCategory?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmFreeUp by remember { mutableStateOf(false) }

    // Painted from the last measurement, so stepping back in from a category
    // costs nothing and the list keeps both its order and its scroll position.
    var report by remember { mutableStateOf(StorageSnapshot.last ?: StorageReport()) }
    LaunchedEffect(revision) {
        if (revision == 0 && StorageSnapshot.isFresh()) return@LaunchedEffect
        // With nothing on screen yet, show each category as it lands. With a
        // picture already up, hold it and swap in the finished one: a partial
        // report would blank the rows it has not reached and reorder the rest.
        val progressive = StorageSnapshot.last == null
        scanStorage(context).collect { next ->
            report = if (progressive || !next.scanning) next
            else report.copy(scanning = true, progress = next.progress)
            if (!next.scanning) StorageSnapshot.publish(next)
        }
    }

    // Categories cleared on this visit. They keep their row, and their place,
    // while their size ticks down to "Empty", instead of dropping out of the
    // list the moment the new measurement lands — the reader sees what the
    // press did rather than a row that has gone.
    var cleared by remember { mutableStateOf(emptySet<String>()) }
    // Whether the next finished measurement may reorder the list. Not after a
    // deletion: the cleared row would leap to the bottom at the moment its
    // size starts ticking down, out from under the eye that is watching it.
    var resort by remember { mutableStateOf(true) }

    fun perform(clearing: Collection<String>, work: suspend (StorageEnv) -> Unit) {
        cleared = cleared + clearing
        resort = false
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                work(StorageEnv(context, repository, storageRoots(context)))
            }
            StorageSnapshot.invalidate()
            busy = false
            revision++
        }
    }

    // Largest first, but only re-sorted when a scan finishes. Sorting on every
    // emission had the rows leapfrogging for the whole of a measure, and doing
    // it mid-refresh would move them under a finger that is already reaching.
    // A cached report is already complete, so it is sorted from the first
    // frame rather than one frame after it.
    var ordered by remember {
        mutableStateOf(
            if (report.scanning) StorageCategories.all
            else StorageCategories.all.sortedByDescending { report.bytesOf(it.id) },
        )
    }
    LaunchedEffect(report.scanning) {
        if (!report.scanning && resort) {
            ordered = StorageCategories.all.sortedByDescending { report.bytesOf(it.id) }
        }
    }

    StorageSummary(report, busy) {
        resort = true
        revision++
    }

    val freeable = StorageCategories.all
        .filter { it.group == StorageGroup.CACHE && it.clearable }
    val freeableBytes = freeable.sumOf { report.bytesOf(it.id) }
    // The figure the button last offered, for it to keep while it folds away:
    // by then the live figure is the few bytes left over.
    val offered = remember { longArrayOf(0L) }
    if (freeableBytes >= FREE_UP_FLOOR) offered[0] = freeableBytes
    FoldAway(visible = freeableBytes >= FREE_UP_FLOOR, reduceMotion = reduceMotion) {
        SettingsGroup {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilledTonalButton(onClick = { confirmFreeUp = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Text(
                            stringResource(R.string.storage_free_up_action, formatBytes(offered[0])),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.storage_free_up_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // Everything the app downloads can come in from a file instead: the one
    // place to bring a language pack, a model or a GIF pack fetched elsewhere,
    // for a phone that cannot fetch them itself.
    SettingsGroup {
        item {
            OfflineImportRow(
                title = stringResource(R.string.offline_import_storage_title),
                subtitle = stringResource(R.string.offline_import_storage_subtitle),
                links = offlineSourceLinks(),
                onImported = {
                    StorageSnapshot.invalidate()
                    resort = true
                    revision++
                },
            )
        }
    }

    if (FeatureLibraryList.offered) SettingsGroup { item { FeatureLibrariesNavRow() } }

    var anyHidden = false
    for (group in StorageGroup.entries) {
        val inGroup = ordered.filter { it.group == group }
        val visible = inGroup.filter { showEmpty || report.bytesOf(it.id) > 0L || it.id in cleared }
        if (visible.size < inGroup.size) anyHidden = true
        if (visible.isEmpty()) continue
        GroupHeading(group, inGroup.sumOf { report.bytesOf(it.id) })
        StorageBar(
            slices = inGroup.map { StorageSlice(it.accent, report.bytesOf(it.id), key = it.id) },
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
        SettingsGroup {
            for (category in visible) {
                item {
                    StorageCategoryRow(
                        category = category,
                        bytes = report.bytesOf(category.id),
                        items = report.itemsOf(category.id),
                        enabled = !busy,
                        onOpen = { onOpenCategory(category.id) },
                        onClear = {
                            if (category.danger == Danger.RESET) confirmReset = true else confirm = category
                        },
                    )
                }
            }
        }
    }

    if (anyHidden || showEmpty) {
        TextButton(
            onClick = { showEmpty = !showEmpty },
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                stringResource(
                    if (showEmpty) R.string.storage_show_less_action else R.string.storage_show_all_action,
                ),
            )
        }
    }

    confirm?.let { category ->
        val bytes = report.bytesOf(category.id)
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_title, stringResource(category.title)),
            body = stringResource(bodyFor(category.danger), formatBytes(bytes)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                perform(listOf(category.id)) { env -> category.clear(env) }
            },
        )
    }

    if (confirmReset) {
        ResetSettingsDialog(
            bytes = report.bytesOf(SETTINGS_CATEGORY),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                StorageCategories.byId(SETTINGS_CATEGORY)?.let { category ->
                    perform(listOf(category.id)) { env -> category.clear(env) }
                }
            },
        )
    }

    if (confirmFreeUp) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_free_up_title),
            body = stringResource(R.string.storage_confirm_free_up_body, formatBytes(freeableBytes)),
            onDismiss = { confirmFreeUp = false },
            onConfirm = {
                confirmFreeUp = false
                perform(freeable.map { it.id }) { env -> freeable.forEach { it.clear(env) } }
            },
        )
    }
}

/** The ring, its legend, and what the device has left, on one card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSummary(report: StorageReport, busy: Boolean, onRefresh: () -> Unit) {
    val working = busy || report.scanning
    val reduceMotion = LocalReduceMotion.current
    // The scan reports one category at a time; the bar glides between them
    // rather than stepping. Forwards only: a new scan starts it again from
    // nothing at once, rather than draining the old one back to zero.
    val progress = remember { Animatable(report.progress) }
    LaunchedEffect(report.progress, reduceMotion) {
        if (reduceMotion || report.progress < progress.value) {
            progress.snapTo(report.progress)
        } else {
            progress.animateTo(report.progress, tween(PROGRESS_GLIDE_MS))
        }
    }
    SettingsGroup {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StorageRing(
                    report = report,
                    contentDescription = stringResource(
                        R.string.storage_ring_desc,
                        formatBytes(report.totalBytes),
                        formatBytes(report.appBytes),
                        formatBytes(report.userDataBytes),
                        formatBytes(report.cacheBytes),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StorageLegendChip(ringBucketColor(0), stringResource(R.string.storage_bucket_app), report.appBytes)
                    StorageLegendChip(ringBucketColor(1), stringResource(R.string.storage_bucket_data), report.userDataBytes)
                    StorageLegendChip(ringBucketColor(2), stringResource(R.string.storage_bucket_cache), report.cacheBytes)
                }
                if (report.deviceBytes > 0L) {
                    Text(
                        stringResource(
                            R.string.storage_device_caption,
                            formatBytes(report.deviceUsedBytes),
                            formatBytes(report.deviceBytes),
                            formatBytes(report.freeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (!report.exact) {
                    Text(
                        stringResource(R.string.storage_estimate_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // Only while something is actually running. A measure that
                // follows a cached paint takes about a second, and this is the
                // only sign of it — the numbers above deliberately hold still.
                // The progress and the Refresh button hand over to each other
                // rather than swapping in a frame.
                AnimatedContent(
                    targetState = working,
                    transitionSpec = { stateSwapTransform(reduceMotion) },
                    contentAlignment = Alignment.TopCenter,
                    label = "storageWorking",
                ) { running ->
                    if (running) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(
                                    if (busy) R.string.storage_deleting_label else R.string.storage_measuring_label,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (busy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { progress.value },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        // Guarded on the live state: while it fades in over a
                        // scan that has just restarted it must not start
                        // another one.
                        TextButton(
                            onClick = { if (!working) onRefresh() },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text(
                                stringResource(R.string.storage_refresh_action),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A group's heading, its total counting to its size as the rows below do. On
 * its own so the count recomposes the heading and not the screen.
 */
@Composable
private fun GroupHeading(group: StorageGroup, bytes: Long) {
    val shown = rememberCount(bytes)
    SectionHeader(stringResource(group.title), trailing = formatBytes(shown.value))
}

/**
 * [content] folding open and shut with [visible], where an `if` would make it
 * appear and vanish in a frame and jump everything under it.
 */
@Composable
private fun FoldAway(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    if (reduceMotion) {
        if (visible) content()
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            label = "storageFold",
        ) { content() }
    }
}

@Composable
private fun StorageCategoryRow(
    category: StorageCategory,
    bytes: Long,
    items: Int,
    enabled: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val deleteDesc = stringResource(R.string.storage_delete_all_desc, stringResource(category.title))
    WmRow(
        title = stringResource(category.title),
        icon = category.icon,
        accent = category.accent,
        highlightKey = category.title,
        flightTo = storageRoute(category.id),
        // One block rather than a subtitle *and* a supporting line: WmRow draws
        // whichever of the two it is given and prefers supporting, so a row with
        // a note used to show the note in place of its size. On a storage screen
        // the size is the row, so it goes first and the note sits under it.
        supporting = {
            Column {
                CategorySummary(bytes, items)
                if (category.note != 0) {
                    Text(
                        stringResource(category.note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NOTE_ALPHA),
                    )
                }
            }
        },
        trailing = {
            if (category.clearable) {
                IconButton(onClick = onClear, enabled = enabled && bytes > 0L) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = deleteDesc,
                        tint = if (enabled && bytes > 0L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                    )
                }
            }
        },
        onClick = onOpen,
    )
}

/**
 * A category's size, and how many things make it up. Counts up with the rest
 * of the screen, and down to "Empty" when the category is cleared — empty
 * only once the count has got there, so a cleared row says its old size on
 * the way down rather than "Empty" from the first frame.
 */
@Composable
private fun CategorySummary(bytes: Long, items: Int) {
    val shown = rememberCount(bytes).value
    Text(
        when {
            bytes <= 0L && shown <= 0L -> stringResource(R.string.storage_empty_label)
            items <= 0 -> formatBytes(shown)
            else -> stringResource(
                R.string.storage_row_summary,
                formatBytes(shown),
                pluralStringResource(R.plurals.storage_item_count, items, items),
            )
        },
    )
}

/**
 * [lock] names this dialog's confirm button in the fingerprint lock's
 * registry. The check runs after the dialog, so the order the user sees is
 * "are you sure" and then "prove it", which puts the fingerprint immediately
 * before the deletion rather than in front of a dialog they may cancel.
 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String = stringResource(CommonR.string.common_delete),
    lock: LockTarget? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val guarded = rememberLockGuard(lock, onConfirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = guarded) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Two dialogs rather than one. Clearing the settings store is the only action
 * on this screen a user cannot undo *and* cannot see the shape of beforehand —
 * every theme, layout and mode they ever made goes with it — so it costs a
 * second, deliberate press rather than one mis-aimed tap.
 */
@Composable
private fun ResetSettingsDialog(bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var second by remember { mutableStateOf(false) }
    if (!second) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_reset_title),
            body = stringResource(R.string.storage_confirm_reset_body),
            onDismiss = onDismiss,
            onConfirm = { second = true },
        )
    } else {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_reset_again_title),
            body = stringResource(R.string.storage_confirm_reset_again_body, formatBytes(bytes)),
            // On the second dialog only, so the sequence reads: are you sure,
            // are you certain, prove it, gone. The fingerprint stays the last
            // step rather than becoming the first of three.
            lock = AppLockTargets["action_factory_reset"],
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

private fun bodyFor(danger: Danger): Int = when (danger) {
    Danger.NONE -> R.string.storage_confirm_free_body
    Danger.REDOWNLOAD -> R.string.storage_confirm_redownload_body
    Danger.PERSONAL, Danger.RESET -> R.string.storage_confirm_personal_body
}

/** Below this the button is noise: the press costs more than the space it wins. */
private const val FREE_UP_FLOOR = 512L * 1024
private const val PROGRESS_GLIDE_MS = 300
private const val DISABLED_ALPHA = 0.38f
private const val NOTE_ALPHA = 0.75f
private const val SETTINGS_CATEGORY = "settings_data"

/**
 * Where the offline build's downloads are published, for **Get the files**:
 * this version's release page (its interface language packs) and the data
 * repository's feature libraries. Nothing on a build that can download.
 */
@Composable
private fun offlineSourceLinks(): List<OfflineLink> {
    if (InternetPermission.granted) return emptyList()
    val packs = stringResource(R.string.offline_import_language_packs_link)
    val libraries = stringResource(R.string.offline_import_natives_link)
    return remember(packs, libraries) {
        listOfNotNull(
            OfflineLink(packs, LanguagePackDownloads.releasePage()).takeIf { BuildConfig.LANGUAGE_PACKS },
            NativeDownloader.releasePage()?.takeIf { NativeDelivery.active }?.let { OfflineLink(libraries, it) },
        )
    }
}

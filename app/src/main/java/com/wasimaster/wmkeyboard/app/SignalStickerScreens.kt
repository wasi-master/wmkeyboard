package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.core.addons.ImportLink
import com.wasimaster.wmkeyboard.core.addons.SignalStickerDownloads
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.stickers.ApngFrames
import com.wasimaster.wmkeyboard.core.notify.BackgroundTasks
import com.wasimaster.wmkeyboard.core.notify.TaskState
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.signal.SignalPackManifest
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The Signal sticker packs screen. */
internal const val SIGNAL_STICKERS_ROUTE = "signal_stickers"

/**
 * The community's gallery of Signal packs. Opened in the browser and never
 * read by the app: its terms ask that it is not built into other software, and
 * Signal and Molly send their users to it the same way.
 */
private const val SIGNAL_GALLERY_URL = "https://signalstickers.org/"

/**
 * The preview of one Signal pack. Both halves are lowercase hex, so neither
 * needs encoding to sit in a route.
 */
internal fun signalPackRoute(packId: String, packKey: String): String = "signal_pack/$packId/$packKey"

/**
 * Signal sticker packs: what a Signal or Molly user taps "Install" on, brought
 * into the user's own packs.
 *
 * Signal has no sticker shop. A pack is public to whoever holds its link, and
 * the link is all there is: `signal.art/addstickers/#pack_id=…&pack_key=…`. So
 * the way in is a field to paste one into. The field starts empty and is never
 * filled from the clipboard, for the reason the link importer gives: this app
 * is a keyboard, and reading the clipboard to be helpful is the one thing it
 * must not do on its own.
 *
 * There is no list of packs of the app's own choosing, on purpose. The packs
 * Signal ships with are art Signal commissioned, and its terms keep every right
 * to it, so a keyboard has no business offering them. A pack gets here because
 * whoever holds its link brought it, like any picture they add to a pack.
 */
@Composable
internal fun SignalStickersScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var showLink by remember { mutableStateOf(false) }

    SettingsGroup(
        stringResource(R.string.import_signal_section_title),
        info = stringResource(R.string.import_signal_caption),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.import_signal_link_title),
                subtitle = stringResource(R.string.import_signal_link_subtitle),
                icon = Icons.Outlined.Link,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_signal_link_title,
                onClick = { showLink = true },
            )
        }
        // The gallery is looked through where it lives. Its own "Add to
        // Signal" button opens a sgnl://addstickers link, which this app
        // answers beside Signal (see ImportLinkActivity in the manifest), so
        // the way back from the browser is one press.
        item {
            WmRow(
                title = stringResource(R.string.import_signal_gallery_title),
                subtitle = stringResource(R.string.import_signal_gallery_subtitle),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_signal_gallery_title,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SIGNAL_GALLERY_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
    }

    if (showLink) {
        SignalLinkDialog(
            onDismiss = { showLink = false },
            onOpen = { pack ->
                showLink = false
                onNavigate(signalPackRoute(pack.packId, pack.packKey))
            },
        )
    }
}

@Composable
private fun SignalLinkDialog(onDismiss: () -> Unit, onOpen: (ImportLink.Target.SignalStickers) -> Unit) {
    var text by remember { mutableStateOf("") }
    val pack = remember(text) { ImportLink.signalStickers(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_signal_link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_signal_link_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_link_field_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank() && pack == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.import_signal_link_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { pack?.let(onOpen) }, enabled = pack != null) {
                Text(stringResource(R.string.import_signal_open_pack))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Where the preview has got to with the pack's manifest. */
private sealed interface PackLoad {
    data object Waiting : PackLoad
    data object Loading : PackLoad
    data class Loaded(val manifest: SignalPackManifest) : PackLoad
    data class Failed(val text: String) : PackLoad
}

/** An import in flight: which half of it, and how far along. */
/** The [BackgroundTasks] key of one pack's import (#1522). */
private fun signalImportTask(packId: String): String = "signal-stickers/$packId"

/**
 * One Signal pack, shown before it is added.
 *
 * Nothing is kept until the user presses Add. What the grid shows is the cache
 * [SignalStickerDownloads] keeps, which the import then reads instead of
 * downloading every picture a second time. Data saving is asked once, before
 * the first request, and the answer covers the pictures and the import too:
 * they are one download as far as the user is concerned.
 */
@Composable
internal fun SignalPackScreen(
    packId: String,
    packKey: String,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    val cacheDir = remember { context.cacheDir }

    var load by remember { mutableStateOf<PackLoad>(PackLoad.Waiting) }
    var askMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    // The import runs as a background task (#1522): leaving this page leaves
    // it running, and coming back shows where it is.
    val startTask = rememberTaskStarter()
    val taskKey = signalImportTask(packId)
    val task = rememberTaskState(taskKey)
    val progress = task as? TaskState.Running
    if (task is TaskState.Ended) {
        LaunchedEffect(task) {
            revision++
            message = task.message
            BackgroundTasks.dismiss(taskKey)
        }
    }
    val mine = remember(revision) {
        store.packs().firstOrNull { it.source == StickerPack.signalSource(packId) }
    }
    // The sticker ids ticked for adding (#965): all of them once the pack
    // loads, and only those are downloaded and converted.
    var chosen by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val notFound = stringResource(R.string.import_signal_error_not_found)
    val badKey = stringResource(R.string.import_signal_error_bad_key)

    fun fetch() {
        load = PackLoad.Loading
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SignalStickerDownloads.trimCache(cacheDir)
                SignalStickerDownloads.manifest(packId, packKey, cacheDir)
            }
            load = when (outcome) {
                is SignalStickerDownloads.ManifestOutcome.Ok -> {
                    chosen = outcome.manifest.stickers.mapTo(HashSet()) { it.id }
                    PackLoad.Loaded(outcome.manifest)
                }
                SignalStickerDownloads.ManifestOutcome.NotFound -> PackLoad.Failed(notFound)
                SignalStickerDownloads.ManifestOutcome.BadKey -> PackLoad.Failed(badKey)
                is SignalStickerDownloads.ManifestOutcome.Failed ->
                    PackLoad.Failed(ToolHttp.friendlyMessage(context, outcome.error))
            }
        }
    }

    LaunchedEffect(packId, packKey) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> fetch()
            MeteredDecision.ASK -> askMetered = true
            MeteredDecision.BLOCKED -> blockedMetered = true
        }
    }

    fun add(manifest: SignalPackManifest) {
        val app = context.applicationContext
        val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
        val only = chosen
        startTask(
            taskKey,
            context.getString(R.string.import_stickers_task_title, manifest.title.ifBlank { fallbackName }),
            StickerImportResult.Failed.describe(context),
        ) { report ->
            report.update(0, only.size.toLong(), app.getString(R.string.import_signal_downloading, 0, only.size))
            try {
                val result = SignalStickerDownloads.import(
                    store, packId, packKey, manifest, fallbackName, cacheDir, only,
                ) { phase, done, total ->
                    val line = when (phase) {
                        SignalStickerDownloads.Phase.DOWNLOADING -> R.string.import_signal_downloading
                        SignalStickerDownloads.Phase.ADDING -> R.string.import_signal_adding
                    }
                    report.update(done.toLong(), total.toLong(), app.getString(line, done, total))
                }
                BackgroundTasks.Outcome(result.describe(app), ok = result is StickerImportResult.Imported)
            } catch (e: IOException) {
                BackgroundTasks.Outcome(ToolHttp.friendlyMessage(app, e), ok = false)
            }
        }
    }

    when (val current = load) {
        PackLoad.Waiting, PackLoad.Loading -> SettingsGroup {
            item { WmRow(title = stringResource(R.string.import_signal_loading)) }
        }

        is PackLoad.Failed -> SettingsGroup {
            item { WmRow(title = current.text) }
            item {
                WmRow(
                    title = stringResource(CommonR.string.common_retry),
                    onClick = { fetch() },
                )
            }
        }

        is PackLoad.Loaded -> LoadedPack(
            manifest = current.manifest,
            packId = packId,
            packKey = packKey,
            cacheDir = cacheDir,
            mine = mine,
            progress = progress,
            chosen = chosen,
            onChosenChange = { chosen = it },
            onAdd = { add(current.manifest) },
            onOpenMine = { mine?.let { onNavigate(stickerPackRoute(it.id)) } },
        )
    }

    if (blockedMetered) {
        MeteredBlockedDialog {
            blockedMetered = false
            onBack()
        }
    }
    if (askMetered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                askMetered = false
                fetch()
            },
            onDismiss = {
                askMetered = false
                onBack()
            },
        )
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
}

@Composable
private fun LoadedPack(
    manifest: SignalPackManifest,
    packId: String,
    packKey: String,
    cacheDir: File,
    mine: StickerPack?,
    progress: TaskState.Running?,
    chosen: Set<Int>,
    onChosenChange: (Set<Int>) -> Unit,
    onAdd: () -> Unit,
    onOpenMine: () -> Unit,
) {
    val count = manifest.stickers.size
    // Ticking is for a pack that is not in yet and not on its way in.
    val picking = mine == null && progress == null
    SettingsGroup(manifest.title.ifBlank { stringResource(R.string.import_signal_untitled) }) {
        item {
            WmRow(
                title = pluralStringResource(R.plurals.import_sticker_count, count, count),
                subtitle = manifest.author.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.import_signal_author, it) },
            )
        }
    }

    SettingsGroup {
        item {
            when {
                progress != null -> TaskProgressRow(
                    title = progress.text.orEmpty(),
                    task = progress.copy(text = null),
                    icon = Icons.Outlined.Add,
                    accent = routeAccent("sticker_packs"),
                )

                mine != null -> WmRow(
                    title = stringResource(R.string.import_signal_added_title),
                    icon = SettingsRowIcons[R.string.import_signal_added_title],
                    subtitle = stringResource(R.string.import_signal_added_subtitle, mine.name),
                    accent = routeAccent("sticker_packs"),
                    onClick = onOpenMine,
                )

                else -> WmRow(
                    title = if (chosen.size == count) {
                        stringResource(R.string.import_signal_add_title)
                    } else {
                        pluralStringResource(R.plurals.import_stickers_add_chosen, chosen.size, chosen.size)
                    },
                    subtitle = stringResource(R.string.import_signal_add_subtitle),
                    icon = Icons.Outlined.Add,
                    accent = routeAccent("sticker_packs"),
                    enabled = chosen.isNotEmpty(),
                    onClick = onAdd,
                )
            }
        }
    }

    if (picking && count > 0) {
        StickerPickBar(
            chosen = chosen.size,
            total = count,
            onAll = { onChosenChange(manifest.stickers.mapTo(HashSet()) { it.id }) },
            onNone = { onChosenChange(emptySet()) },
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        )
    }

    val loader = rememberMediaImageLoader()
    val unnamed = stringResource(R.string.import_sticker_desc_fallback)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(((count + 2) / 3 * 104).coerceAtMost(640).dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(manifest.stickers, key = { it.id }) { sticker ->
            val preview by produceState<PreviewSticker?>(null, packId, sticker.id) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        SignalStickerDownloads.sticker(packId, packKey, sticker.id, cacheDir)?.let { file ->
                            PreviewSticker(file, animated = ApngFrames.isAnimated(file.readBytes()))
                        }
                    }.getOrNull()
                }
            }
            StickerPickCell(
                checked = if (picking) sticker.id in chosen else null,
                onClick = if (picking) ({ onChosenChange(chosen.withToggled(sticker.id)) }) else null,
            ) {
                val shown = preview
                val label = sticker.emoji.ifBlank { unnamed }
                if (shown != null && shown.animated) {
                    AnimatedPngPreview(shown.file, label)
                } else {
                    AsyncImage(
                        model = shown?.file,
                        contentDescription = label,
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** A sticker of the pack being looked at: the decrypted file, and whether it moves. */
private class PreviewSticker(val file: File, val animated: Boolean)

/**
 * An animated Signal sticker, playing.
 *
 * It is an animated PNG until it has been added (that is when it becomes an
 * animated WebP), and nothing on Android plays one: an image loader draws its
 * first frame, which made a pack of animations look like a pack of stills and
 * left no way to tell which a pack was before adding it. So a preview cell
 * that holds one hands it to the decoder library's own drawable, in a plain
 * `ImageView`, which starts and stops it with the cell.
 */
@Composable
private fun AnimatedPngPreview(file: File, label: String) {
    val drawable = remember(file) { runCatching { ApngFrames.drawable(file) }.getOrNull() }
    AndroidView(
        factory = { context ->
            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        },
        update = { view ->
            view.contentDescription = label
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
        },
        // Lets go of the drawable, which is what stops its decoder thread.
        onRelease = { view -> view.setImageDrawable(null) },
        modifier = Modifier.fillMaxSize(),
    )
}

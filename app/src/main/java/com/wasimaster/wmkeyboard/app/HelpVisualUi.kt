package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpFrame
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpTheme
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisual
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisualKind
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisualStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.settings.HelpPictures
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How the help pictures arrive, and the way to change it. Published for the
 * whole settings graph, so the dialog behind any "?" can put the first-time
 * question and act on the answer without the row being handed anything.
 */
@Immutable
internal class HelpPicturesControl(val mode: HelpPictures, val change: (HelpPictures) -> Unit)

/** Null outside the settings graph (onboarding, a preview), where a "?" shows its words alone. */
internal val LocalHelpPictures = compositionLocalOf<HelpPicturesControl?> { null }

/** Whether the row around the caller has a picture its "?" would show. */
@Composable
internal fun rowHasHelpPicture(): Boolean {
    val control = LocalHelpPictures.current ?: return false
    if (control.mode == HelpPictures.OFF) return false
    return HelpVisuals.of(LocalRowLinkKey.current?.key ?: 0) != null
}

/** The tallest a help picture is drawn in the dialog; a taller one is scaled to fit. */
private val MaxPictureHeight = 300.dp

/** An off/on pair this much wider than tall goes one above the other; a narrower one sits side by side. */
private const val STACK_ASPECT = 1.7f

private val PictureShape = RoundedCornerShape(12.dp)

private const val LITERAL_LABEL = "="

/**
 * The picture, clip or comparison that belongs to the setting titled [key],
 * drawn above the words in its "?" dialog. Draws nothing for a setting with
 * no picture, or while pictures are switched off.
 *
 * The first time a dialog with a picture is opened it asks how the pictures
 * should arrive, in the place the picture will go. After that the place holds
 * the picture, the room for it while it loads, or one line saying why it did
 * not come with a way to try again.
 */
@Composable
internal fun HelpVisualSlot(@StringRes key: Int, title: String) {
    val control = LocalHelpPictures.current ?: return
    val loaded = HelpVisuals.loaded ?: return
    val visual = loaded.byTitle[key] ?: return
    if (control.mode == HelpPictures.OFF) return
    val store = loaded.store
    val appTheme = HelpTheme.of(dark = isSystemInDarkTheme())
    // Read so that a file arriving redraws this.
    val generation = HelpVisuals.generation
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        if (control.mode == HelpPictures.ASK) {
            AskCard(visual, appTheme, control)
            return@Column
        }
        // Someone who kept the other look's pictures sees those, everywhere.
        val declined = remember(generation, appTheme) { store.packDeclined(appTheme) }
        val theme = if (control.mode == HelpPictures.ALL && declined) appTheme.other else appTheme
        val shown = remember(generation, visual, theme) { visual.frames.map { store.resolve(it, theme) } }
        val complete = remember(generation, visual, theme) { store.complete(visual, theme) }
        val packHere = remember(generation, theme) { store.packInstalled(theme) }
        val single = HelpVisuals.singles[HelpVisuals.fetchKey(visual, theme)]
        val pack = HelpVisuals.packs[theme]
        // With a look's pack on the phone, a picture a later version added is
        // fetched alone: the pack is the bulk, single files are the top-up.
        val fetchesAlone = control.mode == HelpPictures.ONE_BY_ONE || packHere
        if (!complete && fetchesAlone && single == null && !offlineBuild) {
            LaunchedEffect(visual, theme) { HelpVisuals.fetch(visual, theme) }
        }
        when {
            shown.all { it != null } -> VisualFrames(loaded, visual, shown.filterNotNull().map { it.file }, title)
            single is HelpVisuals.Fetch.Running || pack is HelpVisuals.Fetch.Running -> LoadingFrames(visual)
            else -> Unit
        }
        when {
            complete -> Unit
            offlineBuild -> OfflineNote(theme, control, asking = false)
            pack is HelpVisuals.Fetch.Running -> PackProgress(pack.progress)
            pack is HelpVisuals.Fetch.Failed -> FailedLine(pack.message) { HelpVisuals.fetchPack(theme) }
            single is HelpVisuals.Fetch.Failed -> FailedLine(single.message) { HelpVisuals.fetch(visual, theme) }
            control.mode == HelpPictures.ALL && !packHere -> PackOffer(theme, store, control)
        }
    }
}

/** The first-time question, in the place the picture will go. */
@Composable
private fun AskCard(visual: HelpVisual, theme: HelpTheme, control: HelpPicturesControl) {
    val context = LocalContext.current
    val pack = HelpVisuals.loaded?.index?.pack(theme)
    Surface(
        shape = PictureShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.help_pictures_ask_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (offlineBuild) {
                OfflineNote(theme, control, asking = true)
                return@Column
            }
            val all = pack?.let { Formatter.formatShortFileSize(context, it.bytes) }
            val one = Formatter.formatShortFileSize(context, visual.bytes(theme).coerceAtLeast(1L))
            Text(
                if (all != null) {
                    stringResource(
                        if (theme == HelpTheme.DARK) R.string.help_pictures_ask_body_dark else R.string.help_pictures_ask_body_light,
                        all,
                        one,
                    )
                } else {
                    stringResource(R.string.help_pictures_ask_body_single, one)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (all != null) {
                FilledTonalButton(
                    onClick = {
                        control.change(HelpPictures.ALL)
                        HelpVisuals.fetchPack(theme)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.help_pictures_ask_all, all)) }
            }
            TextButton(
                onClick = { control.change(HelpPictures.ONE_BY_ONE) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.help_pictures_ask_one)) }
            TextButton(
                onClick = { control.change(HelpPictures.OFF) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.help_pictures_ask_off)) }
            Text(
                stringResource(R.string.help_pictures_ask_footer),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Pictures were asked for as a pack and this look's pack is not here: the
 * light and dark pictures are separate downloads, so opening the app in the
 * other look for the first time lands here, as does a restored backup.
 */
@Composable
private fun PackOffer(theme: HelpTheme, store: HelpVisualStore, control: HelpPicturesControl) {
    val context = LocalContext.current
    val pack = HelpVisuals.loaded?.index?.pack(theme) ?: return
    val size = Formatter.formatShortFileSize(context, pack.bytes)
    val otherHere = remember(HelpVisuals.generation, theme) { store.packInstalled(theme.other) }
    Text(
        stringResource(
            if (theme == HelpTheme.DARK) R.string.help_pictures_pack_offer_dark else R.string.help_pictures_pack_offer_light,
            size,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { HelpVisuals.fetchPack(theme) }) {
            Text(stringResource(CommonR.string.common_download))
        }
        if (otherHere) {
            // The other look's pictures are here and are already what is drawn.
            TextButton(onClick = {
                store.declinePack(theme)
                HelpVisuals.packs.remove(theme)
                // Nothing arrived, but what the dialog draws changed.
                HelpVisuals.refresh()
            }) {
                Text(
                    stringResource(
                        if (theme == HelpTheme.DARK) R.string.help_pictures_keep_light else R.string.help_pictures_keep_dark,
                    ),
                )
            }
        } else {
            TextButton(onClick = { control.change(HelpPictures.ONE_BY_ONE) }) {
                Text(stringResource(R.string.help_pictures_ask_one))
            }
        }
    }
}

/**
 * The build with no internet permission: pictures come only as a pack brought
 * over as a file. Says where the pack is published and takes it from the file
 * picker. [asking] adds the way to turn pictures off, for the first-time card.
 */
@Composable
private fun OfflineNote(theme: HelpTheme, control: HelpPicturesControl, asking: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.help_pictures_import_wrong)
    val none = stringResource(R.string.help_pictures_import_none)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.orEmpty()
            val added = HelpVisuals.import(context, uri, name)
            result = when {
                added == null -> wrong
                added == 0 -> none
                else -> context.resources.getQuantityString(R.plurals.help_pictures_imported, added, added)
            }
            if (added != null && control.mode != HelpPictures.ALL) control.change(HelpPictures.ALL)
        }
    }
    val pack = HelpVisuals.loaded?.index?.pack(theme)
    Text(
        stringResource(R.string.help_pictures_offline_body, pack?.fileName.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
    )
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
            Text(stringResource(CommonR.string.common_import))
        }
        val address = HelpVisuals.packAddress(theme)
        if (address != null) {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("help pictures", address))
            }) { Text(stringResource(R.string.help_pictures_copy_address)) }
        }
    }
    if (asking) {
        TextButton(onClick = { control.change(HelpPictures.OFF) }) {
            Text(stringResource(R.string.help_pictures_ask_off))
        }
    }
}

@Composable
private fun PackProgress(progress: Float?) {
    val percent = ((progress ?: 0f) * 100).toInt()
    Text(
        stringResource(R.string.help_pictures_pack_progress, percent),
        style = MaterialTheme.typography.bodySmall,
    )
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedLine(@StringRes message: Int, retry: () -> Unit) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
    // Trying again cannot help where there is no permission to connect.
    if (!offlineBuild) {
        TextButton(onClick = retry) { Text(stringResource(CommonR.string.common_retry)) }
    }
}

/** The room the pictures will take, with a spinner, so the words under them do not jump when they arrive. */
@Composable
private fun LoadingFrames(visual: HelpVisual) {
    val frame = visual.frames.first()
    val description = stringResource(R.string.help_pictures_loading)
    PictureBox(frame.aspect, Modifier.semantics { contentDescription = description }) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun VisualFrames(loaded: HelpVisuals.Loaded, visual: HelpVisual, files: List<File>, title: String) {
    var zoomed by remember { mutableStateOf<Int?>(null) }
    val description = stringResource(
        if (visual.kind == HelpVisualKind.CLIP) R.string.help_pictures_clip_desc else R.string.help_pictures_picture_desc,
        title,
    )
    val frames = visual.frames
    when (visual.kind) {
        HelpVisualKind.STILL -> HelpPicture(files[0], frames[0], description) { zoomed = 0 }
        HelpVisualKind.CLIP -> HelpClip(files[0], frames[0], description)
        HelpVisualKind.COMPARE -> {
            val stacked = frames[0].aspect >= STACK_ASPECT
            val fallback = listOf(CommonR.string.common_off, CommonR.string.common_on)
            if (stacked) {
                frames.forEachIndexed { i, frame ->
                    Labelled(labelOf(loaded, frame, fallback.getOrNull(i))) {
                        HelpPicture(files[i], frame, description) { zoomed = i }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    frames.forEachIndexed { i, frame ->
                        Column(modifier = Modifier.weight(1f)) {
                            Labelled(labelOf(loaded, frame, fallback.getOrNull(i))) {
                                HelpPicture(files[i], frame, description) { zoomed = i }
                            }
                        }
                    }
                }
            }
        }
        HelpVisualKind.GALLERY -> Gallery(loaded, frames, files, description) { zoomed = it }
    }
    zoomed?.let { index -> ZoomedPicture(files[index], description) { zoomed = null } }
}

@Composable
private fun labelOf(loaded: HelpVisuals.Loaded, frame: HelpFrame, @StringRes fallback: Int?): String? {
    // A label behind "=" is the words themselves: a value such as "40 dp",
    // which reads the same in every language and has no resource.
    frame.label?.takeIf { it.startsWith(LITERAL_LABEL) }?.let { return it.drop(1) }
    val id = loaded.label(frame.label).takeIf { it != 0 } ?: fallback ?: return null
    return stringResource(id)
}

@Composable
private fun Labelled(label: String?, content: @Composable () -> Unit) {
    if (label != null) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    content()
}

/** One picture per option or step, swiped through, each under its label, with dots saying how many there are. */
@Composable
private fun Gallery(
    loaded: HelpVisuals.Loaded,
    frames: List<HelpFrame>,
    files: List<File>,
    description: String,
    onZoom: (Int) -> Unit,
) {
    val pager = rememberPagerState { frames.size }
    val narrowest = frames.minOf { it.aspect }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // One height for every page, the tallest picture's, so the words under
        // the pager hold still as the pages change.
        val height = (maxWidth / narrowest).coerceAtMost(MaxPictureHeight)
        Column {
            Text(
                labelOf(loaded, frames[pager.currentPage], null)
                    ?: stringResource(R.string.help_pictures_page, pager.currentPage + 1, frames.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            HorizontalPager(state = pager, pageSpacing = 8.dp, modifier = Modifier.height(height)) { page ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    HelpPicture(files[page], frames[page], description, maxHeight = height) { onZoom(page) }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                repeat(frames.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
        }
    }
}

/** The frame every picture sits in: its own shape, a hairline, and the room it needs before it is decoded. */
@Composable
private fun PictureBox(
    aspect: Float,
    modifier: Modifier = Modifier,
    maxHeight: Dp = MaxPictureHeight,
    content: @Composable () -> Unit,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                // No taller than the cap: a tall picture is narrowed, not cropped.
                .widthIn(max = maxHeight * aspect)
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(PictureShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PictureShape),
        ) { content() }
    }
}

@Composable
private fun rememberPicture(file: File): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

/** One still picture; a tap opens it at the size of the screen. */
@Composable
private fun HelpPicture(
    file: File,
    frame: HelpFrame,
    description: String,
    maxHeight: Dp = MaxPictureHeight,
    onZoom: () -> Unit,
) {
    val bitmap = rememberPicture(file)
    val zoom = stringResource(R.string.help_pictures_zoom)
    PictureBox(
        frame.aspect,
        Modifier.clickable(onClickLabel = zoom, role = Role.Image, onClick = onZoom),
        maxHeight,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A few seconds of a feature in use, as an animated WebP. Plays as soon as it
 * is shown and loops, unless motion is reduced, where it waits on its first
 * frame for a tap. A tap pauses and resumes it either way. Android 8.1 and
 * older cannot play one, and show the first frame.
 */
@Composable
private fun HelpClip(file: File, frame: HelpFrame, description: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        val bitmap = rememberPicture(file)
        PictureBox(frame.aspect) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        return
    }
    var playing by remember(file) { mutableStateOf(true) }
    val still = LocalReduceMotion.current
    LaunchedEffect(file, still) { if (still) playing = false }
    val label = stringResource(if (playing) R.string.help_pictures_pause else R.string.help_pictures_play)
    PictureBox(
        frame.aspect,
        Modifier
            .clickable(onClickLabel = label, role = Role.Button) { playing = !playing }
            .semantics { contentDescription = description },
    ) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                if (view.tag != file.path) {
                    view.tag = file.path
                    view.setImageDrawable(
                        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull(),
                    )
                }
                val clip = view.drawable as? AnimatedImageDrawable ?: return@AndroidView
                clip.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (playing && !clip.isRunning) clip.start()
                if (!playing && clip.isRunning) clip.stop()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!playing) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
    }
}

/** A picture at the width of the screen, over a scrim; a tap anywhere closes it. */
@Composable
private fun ZoomedPicture(file: File, description: String, onDismiss: () -> Unit) {
    val bitmap = rememberPicture(file)
    val close = stringResource(CommonR.string.common_close)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClickLabel = close, onClick = onDismiss)
                .padding(12.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * About › App: how the help pictures arrive, and the button that deletes the
 * ones already downloaded.
 */
@Composable
internal fun HelpPicturesChoice() {
    val control = LocalHelpPictures.current ?: return
    val options = listOf(
        HelpPictures.ASK to stringResource(R.string.help_pictures_mode_ask),
        HelpPictures.ALL to stringResource(R.string.help_pictures_mode_all),
        HelpPictures.ONE_BY_ONE to stringResource(R.string.help_pictures_mode_one),
        HelpPictures.OFF to stringResource(R.string.help_pictures_mode_off),
    )
    ChoiceSetting(
        title = R.string.help_pictures_title,
        subtitle = stringResource(R.string.help_pictures_subtitle),
        info = stringResource(R.string.help_pictures_info),
        options = options,
        selected = control.mode,
        default = HelpPictures.ASK,
        detail = { ChoiceDetail(stringResource(helpPicturesDescription(it))) },
    ) { control.change(it) }
}

@StringRes
private fun helpPicturesDescription(mode: HelpPictures): Int = when (mode) {
    HelpPictures.ASK -> R.string.help_pictures_mode_ask_desc
    HelpPictures.ALL -> R.string.help_pictures_mode_all_desc
    HelpPictures.ONE_BY_ONE -> R.string.help_pictures_mode_one_desc
    HelpPictures.OFF -> R.string.help_pictures_mode_off_desc
}

/** The size of the pictures on the phone and the button that deletes them; draws nothing while there are none. */
@Composable
internal fun HelpPicturesDelete() {
    val loaded = HelpVisuals.loaded ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generation = HelpVisuals.generation
    val bytes by produceState(0L, generation, loaded) {
        value = withContext(Dispatchers.IO) { loaded.store.bytesOnDisk() }
    }
    if (bytes <= 0L) return
    ActionRow(
        title = R.string.help_pictures_delete_title,
        subtitle = Formatter.formatShortFileSize(context, bytes),
        action = stringResource(CommonR.string.common_delete),
        confirm = stringResource(R.string.help_pictures_delete_confirm),
    ) { scope.launch { HelpVisuals.clear(context) } }
}

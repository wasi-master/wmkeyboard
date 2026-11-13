package com.wasimaster.wmkeyboard.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as CropRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.stickers.OutlineSpec
import com.wasimaster.wmkeyboard.core.stickers.ProcessedSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerAddResult
import com.wasimaster.wmkeyboard.core.stickers.StickerEditState
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.stickers.StickerOutline
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.SubjectCutout
import com.wasimaster.wmkeyboard.core.upscale.UpscaleKind
import com.wasimaster.wmkeyboard.core.upscale.Upscaler
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixNormal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BorderStyle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Crop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore

/** Settings route hosting the sticker editor. */
internal const val STICKER_EDITOR_ROUTE = "sticker_editor"

/**
 * What the editor is about to edit, and where the result goes.
 *
 * Both entry points reduce to bytes: the photo picker has already read them,
 * and a re-edit reads the stored WebP. One code path, and no `content://`
 * permission to keep alive across a navigation.
 */
internal class StickerEditRequest(
    val packId: String,
    val bytes: ByteArray,
    /** Non-null when replacing an existing sticker in place. */
    val stickerId: String?,
)

/**
 * The pending edit. Process-level for the same reason [PhotoSelection] is: a
 * route argument is a string in a URL, this is a megabyte of image, and the
 * app carries no view models. The screen captures it once, so clearing it on
 * the way out cannot make the destination pop itself twice.
 */
internal object StickerEditHandoff {
    @Volatile
    var current: StickerEditRequest? = null
}

/**
 * What one open of the editor starts from: the picture, and the edit that was
 * last saved over it, if there was one.
 *
 * Read off the disk in one place so the screen never has to think about which
 * of the three files exist. A mask without a state, or a state whose mask has
 * gone, simply restores what is there.
 */
private class OpenedEdit(
    val bitmap: Bitmap,
    val edit: StickerEditState?,
    val mask: Bitmap?,
    /** True when the picture came from the kept original, not the sticker. */
    val fromOriginal: Boolean,
)

private fun openFor(store: StickerPackStore, request: StickerEditRequest): OpenedEdit? {
    val bitmap = runCatching { StickerImage.decodeForEditing(request.bytes) }.getOrNull()
        ?: return null
    val stickerId = request.stickerId ?: return OpenedEdit(bitmap, null, null, false)
    val fromOriginal = store.originalFor(stickerId) != null
    // Only a picture that is the edit's own source can carry the edit: after
    // an original is lost the sticker re-opens as itself, already cropped and
    // already erased, and replaying the crop on top would crop it twice.
    if (!fromOriginal) return OpenedEdit(bitmap, null, null, false)
    val edit = store.editStateFor(stickerId)
    val mask = if (edit?.hasMask == true) {
        store.maskFor(stickerId)?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    } else {
        null
    }
    return OpenedEdit(bitmap, edit?.copy(hasMask = mask != null), mask, true)
}

/** How deep undo goes. Each step is one alpha snapshot, up to 1 MB. */
private const val UNDO_DEPTH = 12

private const val MIN_BRUSH_PX = 8f
private const val MAX_BRUSH_PX = 96f

/** How long the brush ring stays up after the slider stops moving. */
private const val BRUSH_PREVIEW_MS = 1100L

/** What the editor is doing to the picture right now. */
private enum class EditorMode { CROP, ERASE, RESTORE, BORDER }

/**
 * The controls under the canvas, which is coarser than [EditorMode]: Erase and
 * Restore share one brush slider, and switching between them must not fade the
 * slider out and back in under the thumb that is about to use it.
 */
private enum class ToolPanel { CROP, BRUSH, BORDER }

private fun toolPanelOf(mode: EditorMode): ToolPanel = when (mode) {
    EditorMode.CROP -> ToolPanel.CROP
    EditorMode.ERASE, EditorMode.RESTORE -> ToolPanel.BRUSH
    EditorMode.BORDER -> ToolPanel.BORDER
}

/** How long the picture before a cut-out or an undo takes to fade off the canvas. */
private const val DissolveMs = 220

/**
 * The picture as it was a moment ago, fading off the top of the canvas.
 *
 * The editor's bitmaps are changed in place, so once a cut-out or an undo has
 * run there is no "before" left to fade from. The screen takes a copy just
 * ahead of the change and hands it here; the canvas draws it over the new
 * picture at [fade] until it has gone. What the change took away melts out of
 * the picture rather than vanishing from one frame to the next, which is the
 * difference between seeing the background go and wondering whether it did.
 *
 * Read in the canvas's draw pass only, so the fade is a redraw a frame.
 */
@Stable
private class CanvasDissolve {
    var from by mutableStateOf<ImageBitmap?>(null)
        private set
    val fade = Animatable(0f)

    suspend fun play(before: ImageBitmap) {
        from = before
        try {
            fade.snapTo(1f)
            fade.animateTo(0f, tween(DissolveMs, easing = FastOutSlowInEasing))
        } finally {
            // A second change interrupting this one has already put its own
            // picture up; only the dissolve still showing its picture clears it.
            if (from === before) from = null
        }
    }
}

/**
 * Crop, background removal and a border, for one sticker.
 *
 * Everything after the crop happens on the crop itself, pixel for pixel out
 * of the decoded source and in whatever shape the frame was dragged to, so a
 * brush pixel, a mask pixel and an output pixel are the same pixel and nothing
 * has to be projected between coordinate systems. The sticker keeps that
 * shape; only a send to an app that wants a 512 square letterboxes it, at
 * send time (see `StickerImage.squareCopy`).
 *
 * The mask is a bitmap and not a list of strokes because it has two authors —
 * the brush and the segmenter — and they have to compose. Undo covers raster
 * work only: the border is a parameter, so putting it back is its own undo.
 *
 * Within a session an edit is destructive: the crop is baked into [base] and a
 * second one throws the mask away, which is why it asks first and why an empty
 * segmentation mask is never applied. Across sessions it is not — the store
 * keeps the picture the sticker was made from, so re-opening a sticker starts
 * from the photo rather than from what the last edit left behind.
 */
@Composable
internal fun StickerEditorScreen(request: StickerEditRequest, onDone: () -> Unit) {
    RegisterHidingHeader()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }

    val opened by produceState<OpenedEdit?>(initialValue = null, request) {
        value = withContext(Dispatchers.IO) { openFor(store, request) }
    }
    val loaded = opened
    if (loaded == null) {
        CaptionText(stringResource(CommonR.string.common_loading))
        return
    }

    val state = remember(loaded) { EditorState(loaded.bitmap, loaded.edit, loaded.mask) }
    DisposableEffect(state) { onDispose { state.recycle() } }
    val reduceMotion = LocalReduceMotion.current
    val dissolve = remember(state) { CanvasDissolve() }

    // A resumed edit opens on the brush rather than on Crop: the crop is
    // already made, and the one gesture that would silently undo the erasing
    // is a pan in the crop step.
    var mode by remember(state) {
        mutableStateOf(if (loaded.edit?.hasMask == true) EditorMode.ERASE else EditorMode.CROP)
    }
    var brushPx by remember(state) {
        mutableFloatStateOf(loaded.edit?.brushPx ?: StickerEditState.DEFAULT_BRUSH_PX)
    }
    var recropAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // The upscale under way, for its Cancel button; null when none is.
    var upscaling by remember { mutableStateOf<Job?>(null) }

    // Whether the one-tap cutout is even a thing in this build. False on F-Droid,
    // where the brushes are the whole story and a disabled button would only
    // raise a question the screen cannot answer.
    val cutoutSupported = SubjectCutout.supported
    var modelReady by remember { mutableStateOf(false) }
    LaunchedEffect(cutoutSupported) {
        if (cutoutSupported) {
            // Only the Play services engine is ML Kit. Where the app runs its
            // own model there is nothing of Google's to wake up.
            if (SubjectCutout.downloadsFromPlayServices(context)) {
                MlKitInit.ensure(context.applicationContext)
            }
            modelReady = SubjectCutout.modelReady(context)
        }
    }

    // The brush ring: shown on the way into a brush tab and while the slider
    // moves, then out of the way. A size you can only find out by painting is
    // a size you have to undo.
    var showBrush by remember { mutableStateOf(false) }
    LaunchedEffect(mode, brushPx) {
        if (mode != EditorMode.ERASE && mode != EditorMode.RESTORE) return@LaunchedEffect
        showBrush = true
        delay(BRUSH_PREVIEW_MS)
        showBrush = false
    }

    /**
     * Runs [change] on the picture and lets what it replaced dissolve away.
     * For the whole-canvas changes only — a cut-out, an undo — never for a
     * brush stroke, which the finger is already watching happen.
     */
    fun dissolving(change: () -> Unit) {
        if (reduceMotion || mode == EditorMode.CROP) {
            change()
            return
        }
        val before = state.snapshot().asImageBitmap()
        change()
        scope.launch { dissolve.play(before) }
    }

    fun enterMode(next: EditorMode) {
        if (next == EditorMode.CROP && mode != EditorMode.CROP && state.masked) {
            recropAsk = true
        } else {
            if (mode == EditorMode.CROP && next != EditorMode.CROP) state.applyCrop()
            mode = next
        }
    }

    fun runCutout() {
        val message = context.getString(R.string.import_sticker_editor_cutout_progress)
        scope.launch {
            error = null
            if (!modelReady) {
                busy = context.getString(R.string.import_sticker_editor_cutout_download_progress)
                downloadProgress = 0f
                val installed = SubjectCutout.ensureModel(context) { downloadProgress = it }
                downloadProgress = -1f
                busy = null
                if (!installed) {
                    error = context.getString(R.string.import_sticker_editor_cutout_error)
                    return@launch
                }
                modelReady = true
            }
            busy = message
            val result = SubjectCutout.cutOut(context, state.base)
            busy = null
            when (result) {
                is SubjectCutout.Result.Ok -> {
                    dissolving {
                        state.pushUndo()
                        state.applyAlphaMask(result.alpha)
                    }
                    result.alpha.recycle()
                }
                SubjectCutout.Result.NoSubject ->
                    error = context.getString(R.string.import_sticker_editor_cutout_none_error)
                else -> error = context.getString(R.string.import_sticker_editor_cutout_error)
            }
        }
    }

    /**
     * Enlarges the crop with the upscaler (see Upscaler): the network's
     * picture replaces the crop, the erased parts are scaled to match, and
     * the upscaled picture becomes this sticker's source from here on.
     */
    fun runUpscale(factor: Int) {
        upscaling = scope.launch {
            error = null
            busy = context.getString(R.string.import_sticker_editor_upscale_progress)
            downloadProgress = 0f
            var failed = false
            try {
                val result = Upscaler.upscale(context, state.base, UpscaleKind.ART, factor) { event ->
                    when (event) {
                        is Upscaler.Event.Downloading -> {
                            busy = context.getString(R.string.import_sticker_editor_upscale_download_progress)
                            downloadProgress = if (event.total > 0) event.bytes.toFloat() / event.total else 0f
                        }
                        is Upscaler.Event.Working -> {
                            busy = context.getString(R.string.import_sticker_editor_upscale_progress)
                            downloadProgress = event.done.toFloat() / event.total.coerceAtLeast(1)
                        }
                        is Upscaler.Event.Failed -> failed = true
                        is Upscaler.Event.Done -> Unit
                    }
                }
                if (result == null) {
                    if (failed) error = context.getString(R.string.import_sticker_editor_upscale_error)
                } else {
                    dissolving { state.adoptUpscaled(result) }
                }
            } finally {
                busy = null
                downloadProgress = -1f
                upscaling = null
            }
        }
    }

    fun save() {
        saving = true
        // A pan or a pinch that was never followed by a tab change has not
        // been baked into the canvas yet, and Save must not drop it.
        if (mode == EditorMode.CROP) state.applyCrop()
        val edit = state.exportState(brushPx)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // An upscaled crop is the picture this sticker comes from now:
                // a later edit has to start from it, not from the small one.
                val upscaledOriginal = if (state.upscaled) StickerImage.encodeOriginal(state.source) else null
                val flat = state.render()
                val processed: ProcessedSticker? = StickerImage.encodeStill(flat)
                flat.recycle()
                val saved = processed?.let {
                    if (request.stickerId == null) {
                        // What the editor was handed is the picture this
                        // sticker came from: keep it, so a later edit starts
                        // from the photo rather than from the cut-out.
                        store.addSticker(
                            request.packId,
                            it,
                            original = upscaledOriginal ?: StickerImage.encodeOriginal(request.bytes),
                        )
                    } else {
                        // A re-edit already has an original on file — see
                        // StickerPackStore.replaceStickerImage for the one
                        // that gets promoted when it does not.
                        store.replaceStickerImage(request.packId, request.stickerId, it, original = upscaledOriginal)
                    }
                }
                // Beside the picture, so opening this sticker again resumes
                // the edit instead of starting it over.
                if (saved is StickerAddResult.Added) {
                    store.writeEditState(saved.sticker.id, edit, state.maskPng())
                }
                saved
            }
            saving = false
            when (result) {
                is StickerAddResult.Added -> onDone()
                StickerAddResult.PackFull ->
                    error = context.getString(R.string.import_sticker_pack_full)
                else -> error = context.getString(R.string.import_sticker_editor_save_error)
            }
        }
    }

    // Say which picture is on the canvas: a re-edit that quietly reverted the
    // last cut-out would read as the editor having lost the work.
    CaptionText(
        stringResource(
            when {
                loaded.edit?.meaningful == true ->
                    R.string.import_sticker_editor_resumed_caption
                loaded.fromOriginal -> R.string.import_sticker_editor_original_caption
                else -> R.string.import_sticker_editor_shape_caption
            },
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Into and out of the crop step the canvas hands over rather than
        // swapping in a frame: the framed photo and the cut-out are the same
        // picture, and a cross-fade says so. The brush tabs share one canvas,
        // so moving between them changes nothing here.
        AnimatedContent(
            targetState = mode == EditorMode.CROP,
            transitionSpec = { stateSwapTransform(reduceMotion, animateSize = false) },
            label = "stickerEditorCanvas",
        ) { cropping ->
            // The canvas on its way out is still composed, and would still
            // take a pan or a stroke: each one listens only while its step is
            // the current one.
            if (cropping) {
                CropCanvas(state, active = mode == EditorMode.CROP)
            } else {
                EditorCanvas(state, mode, brushPx, showBrush, dissolve)
            }
        }
        if (busy != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (downloadProgress >= 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    )
                } else {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(8.dp))
                Text(busy.orEmpty(), style = MaterialTheme.typography.labelLarge)
                upscaling?.let { job ->
                    TextButton(onClick = { job.cancel() }) { Text(stringResource(CommonR.string.common_cancel)) }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    ChoiceControl(
        options = buildList {
            add(EditorMode.CROP to stringResource(R.string.import_sticker_editor_crop_action))
            add(EditorMode.ERASE to stringResource(R.string.import_sticker_editor_erase_action))
            add(EditorMode.RESTORE to stringResource(R.string.import_sticker_editor_restore_action))
            add(EditorMode.BORDER to stringResource(R.string.import_sticker_editor_border_action))
        },
        selected = mode,
        modifier = Modifier.padding(horizontal = 16.dp),
        detail = { editorMode ->
            ChoiceDetail(
                icon = when (editorMode) {
                    EditorMode.CROP -> Icons.Outlined.Crop
                    EditorMode.ERASE -> Icons.Outlined.AutoFixNormal
                    EditorMode.RESTORE -> Icons.Outlined.Restore
                    EditorMode.BORDER -> Icons.Outlined.BorderStyle
                },
            )
        },
    ) { enterMode(it) }

    // Each tool's own controls hand over to the next tool's, the box between
    // them growing or shrinking to fit rather than the page jumping.
    AnimatedContent(
        targetState = toolPanelOf(mode),
        transitionSpec = { stateSwapTransform(reduceMotion) },
        label = "stickerEditorTool",
    ) { panel ->
        Column {
            when (panel) {
                ToolPanel.CROP -> CaptionText(stringResource(R.string.import_sticker_editor_frame_hint))
                ToolPanel.BRUSH -> {
                    SliderRow(
                        title = R.string.import_sticker_editor_brush_size_label,
                        value = brushPx,
                        range = MIN_BRUSH_PX..MAX_BRUSH_PX,
                        display = { "${it.roundToInt()}" },
                    ) { brushPx = it }
                }
                ToolPanel.BORDER -> {
                    SliderRow(
                        title = R.string.import_sticker_editor_border_width_label,
                        value = state.border.widthPx,
                        range = 0f..OutlineSpec.MAX_WIDTH_PX,
                        display = { "${it.roundToInt()}" },
                    ) { state.changeBorder(state.border.copy(widthPx = it)) }
                    ChoiceControl(
                        options = listOf(
                            AndroidColor.WHITE to stringResource(R.string.import_sticker_editor_border_white_option),
                            AndroidColor.BLACK to stringResource(R.string.import_sticker_editor_border_black_option),
                        ),
                        selected = state.border.color,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        label = stringResource(R.string.import_sticker_editor_border_color_label),
                    ) { state.changeBorder(state.border.copy(color = it)) }
                }
            }
        }
    }

    // Read so a crop or an upscale that changed the picture's size redraws the offer.
    @Suppress("UNUSED_EXPRESSION")
    state.version
    val upscaleFactor = upscaleFactorFor(state.base.width, state.base.height)
    if (cutoutSupported || upscaleFactor > 0) {
        // Leaves with the crop step's arrival and comes back after it, rather
        // than blinking: these are the rows on the screen that come and go.
        AnimatedVisibility(
            visible = mode != EditorMode.CROP,
            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            SettingsGroup {
                if (cutoutSupported) item {
                    WmRow(
                        title = stringResource(R.string.import_sticker_editor_cutout_action),
                        icon = SettingsRowIcons[R.string.import_sticker_editor_cutout_action],
                        subtitle = stringResource(
                            when {
                                modelReady -> R.string.import_sticker_editor_cutout_subtitle
                                SubjectCutout.downloadsFromPlayServices(context) ->
                                    R.string.import_sticker_editor_cutout_download_subtitle
                                // No Play services to ask, so the app gets its own
                                // model, and can say how much it is about to fetch.
                                else -> R.string.import_sticker_editor_cutout_download_own_subtitle
                            },
                        ),
                        enabled = busy == null,
                        // Still drawn for a moment as the crop step opens, and
                        // a cut-out there would run on a crop not yet made.
                        onClick = { if (mode != EditorMode.CROP) runCutout() },
                    )
                }
                if (upscaleFactor > 0) item {
                    val width = state.base.width
                    val height = state.base.height
                    val size = stringResource(
                        R.string.import_sticker_editor_upscale_size,
                        width, height, width * upscaleFactor, height * upscaleFactor,
                    )
                    val download = Upscaler.downloadBytes(context, UpscaleKind.ART)
                    WmRow(
                        title = stringResource(R.string.import_sticker_editor_upscale_action),
                        icon = SettingsRowIcons[R.string.import_sticker_editor_upscale_action],
                        subtitle = if (download > 0L) {
                            stringResource(
                                R.string.import_sticker_editor_upscale_download_subtitle,
                                size,
                                android.text.format.Formatter.formatShortFileSize(context, download),
                            )
                        } else {
                            stringResource(R.string.import_sticker_editor_upscale_subtitle, upscaleFactor, size)
                        },
                        enabled = busy == null,
                        onClick = { if (mode != EditorMode.CROP) runUpscale(upscaleFactor) },
                    )
                }
            }
        }
    }

    error?.let { CaptionText(it, error = true) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { dissolving { state.undo() } },
            enabled = state.undoDepth > 0 && busy == null,
        ) { Text(stringResource(R.string.import_sticker_editor_undo_action)) }
        Spacer(Modifier.fillMaxWidth().weight(1f))
        TextButton(onClick = onDone) { Text(stringResource(CommonR.string.common_cancel)) }
        Button(onClick = { save() }, enabled = !saving && busy == null) {
            Text(stringResource(CommonR.string.common_save))
        }
    }

    if (recropAsk) {
        AlertDialog(
            onDismissRequest = { recropAsk = false },
            title = { Text(stringResource(R.string.import_sticker_editor_recrop_title)) },
            text = { Text(stringResource(R.string.import_sticker_editor_recrop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    recropAsk = false
                    state.resetMask()
                    mode = EditorMode.CROP
                }) { Text(stringResource(CommonR.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { recropAsk = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * What Upscale offers for a crop of [width] by [height]: ×4 for one 256
 * pixels or less on its long side, ×2 up to [UPSCALE_MAX_EDGE], nothing for
 * larger ones, which are already as large as a sticker is kept
 * (StickerImage.MAX_STILL_SIZE) or near it.
 */
private fun upscaleFactorFor(width: Int, height: Int): Int {
    val long = maxOf(width, height)
    return when {
        long <= UPSCALE_X4_EDGE -> 4
        long <= UPSCALE_MAX_EDGE -> 2
        else -> 0
    }
}

private const val UPSCALE_X4_EDGE = 256
private const val UPSCALE_MAX_EDGE = 640

/** How far outside the crop frame, in dp, a touch still takes hold of an edge. */
private const val CROP_GRAB_DP = 24

/** The smallest crop, in source pixels: a few pixels is not a sticker. */
private const val MIN_CROP_PX = 32f

/**
 * Where a [width] × [height] picture sits in [box]: as large as fits whole,
 * centred. The crop step and the brush canvas both draw this way, so the
 * whole picture is always on screen whatever shape it is.
 */
private fun fitInto(box: IntSize, width: Int, height: Int): CropRect {
    if (box.width <= 0 || box.height <= 0 || width <= 0 || height <= 0) return CropRect.Zero
    val scale = min(box.width / width.toFloat(), box.height / height.toFloat())
    val shownWidth = width * scale
    val shownHeight = height * scale
    val left = (box.width - shownWidth) / 2f
    val top = (box.height - shownHeight) / 2f
    return CropRect(left, top, left + shownWidth, top + shownHeight)
}

/** Which sides of the crop frame a drag holds. None of them means it moves the frame. */
private class CropGrip(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    val moves: Boolean get() = !left && !top && !right && !bottom
}

/**
 * What a touch at [at] takes hold of on [frame], or null when it is nowhere
 * near it and the page should have it. An edge is reached [reach] outside it,
 * but only a third of the frame inside it, so even a small frame keeps a
 * middle to move it by.
 */
private fun gripAt(frame: CropRect, at: Offset, reach: Float): CropGrip? {
    if (at.x !in frame.left - reach..frame.right + reach) return null
    if (at.y !in frame.top - reach..frame.bottom + reach) return null
    val innerX = min(reach, frame.width / 3f)
    val innerY = min(reach, frame.height / 3f)
    fun near(position: Float, edge: Float, outward: Float, inner: Float): Boolean {
        val outside = (position - edge) * outward
        return if (outside >= 0f) outside <= reach else -outside <= inner
    }
    val left = near(at.x, frame.left, -1f, innerX)
    val right = !left && near(at.x, frame.right, 1f, innerX)
    val top = near(at.y, frame.top, -1f, innerY)
    val bottom = !top && near(at.y, frame.bottom, 1f, innerY)
    val grip = CropGrip(left, top, right, bottom)
    return if (grip.moves && !frame.contains(at)) null else grip
}

/** [crop] after a drag of [delta] source pixels on [grip], kept inside the picture. */
private fun dragged(crop: CropRect, grip: CropGrip, delta: Offset, width: Float, height: Float): CropRect {
    if (grip.moves) {
        return crop.translate(
            delta.x.coerceIn(-crop.left, width - crop.right),
            delta.y.coerceIn(-crop.top, height - crop.bottom),
        )
    }
    val smallest = min(MIN_CROP_PX, min(width, height))
    return CropRect(
        left = if (grip.left) (crop.left + delta.x).coerceIn(0f, crop.right - smallest) else crop.left,
        top = if (grip.top) (crop.top + delta.y).coerceIn(0f, crop.bottom - smallest) else crop.top,
        right = if (grip.right) (crop.right + delta.x).coerceIn(crop.left + smallest, width) else crop.right,
        bottom = if (grip.bottom) (crop.bottom + delta.y).coerceIn(crop.top + smallest, height) else crop.bottom,
    )
}

/** [crop], in source pixels, where it is drawn over a picture shown at [shown]. */
private fun CropRect.onScreen(shown: CropRect, scale: Float) = CropRect(
    shown.left + left * scale,
    shown.top + top * scale,
    shown.left + right * scale,
    shown.top + bottom * scale,
)

/**
 * The crop step: the whole picture, with a frame over it whose edges and
 * corners drag. The frame takes any shape, because the sticker does: what is
 * inside it is kept, in its own proportions.
 */
@Composable
private fun CropCanvas(state: EditorState, active: Boolean) {
    val source = state.source
    val image = remember(source) { source.asImageBitmap() }
    val reach = with(LocalDensity.current) { CROP_GRAB_DP.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (!active) {
                    Modifier
                } else {
                    // Taken on the Initial pass, like the brush: the screen
                    // scrolls, and a drag on the frame must not scroll it. A
                    // touch away from the frame is left for the page.
                    Modifier.pointerInput(source, reach) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val shown = fitInto(size, source.width, source.height)
                            if (shown.width <= 0f) return@awaitEachGesture
                            val scale = shown.width / source.width
                            val grip = gripAt(state.crop.onScreen(shown, scale), down.position, reach)
                                ?: return@awaitEachGesture
                            down.consume()
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val delta = (change.position - last) / scale
                                last = change.position
                                state.crop = dragged(
                                    state.crop,
                                    grip,
                                    delta,
                                    source.width.toFloat(),
                                    source.height.toFloat(),
                                )
                                change.consume()
                            }
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val shown = fitInto(
                IntSize(size.width.roundToInt(), size.height.roundToInt()),
                source.width,
                source.height,
            )
            if (shown.width <= 0f) return@Canvas
            drawImage(
                image = image,
                dstOffset = IntOffset(shown.left.roundToInt(), shown.top.roundToInt()),
                dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
            )
            val frame = state.crop.onScreen(shown, shown.width / source.width)
            // What is cut off stays visible, only dimmed: a crop you cannot
            // see past is a crop you have to guess at.
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(shown.left, shown.top), Size(shown.width, frame.top - shown.top))
            drawRect(shade, Offset(shown.left, frame.bottom), Size(shown.width, shown.bottom - frame.bottom))
            drawRect(shade, Offset(shown.left, frame.top), Size(frame.left - shown.left, frame.height))
            drawRect(shade, Offset(frame.right, frame.top), Size(shown.right - frame.right, frame.height))
            drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(width = 2.dp.toPx()))
            val handle = 6.dp.toPx()
            for (corner in listOf(frame.topLeft, frame.topRight, frame.bottomLeft, frame.bottomRight)) {
                drawCircle(Color.Black.copy(alpha = 0.5f), radius = handle + 1.5f, center = corner)
                drawCircle(Color.White, radius = handle, center = corner)
            }
        }
    }
}

/**
 * The cut-out picture over a checkerboard, with the brush on top.
 *
 * Strokes are consumed on the Initial pass, the way the handwriting panel
 * does it: the screen scrolls, and a brush that let the drag through would
 * scroll the page instead of erasing anything.
 */
@Composable
private fun EditorCanvas(
    state: EditorState,
    mode: EditorMode,
    brushPx: Float,
    showBrush: Boolean,
    dissolve: CanvasDissolve,
) {
    val erasing = mode == EditorMode.ERASE
    val painting = mode == EditorMode.ERASE || mode == EditorMode.RESTORE

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (!painting) Modifier else Modifier.pointerInput(mode, brushPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        down.consume()
                        val shown = fitInto(size, state.base.width, state.base.height)
                        if (shown.width <= 0f) return@awaitEachGesture
                        val scale = state.base.width / shown.width
                        fun toCanvas(position: Offset) = (position - shown.topLeft) * scale
                        state.pushUndo()
                        var last = toCanvas(down.position)
                        state.paint(last, last, brushPx, erasing)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val next = toCanvas(change.position)
                            state.paint(last, next, brushPx, erasing)
                            last = next
                            change.consume()
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            // Read the counter so a mutation of the bitmaps in place still
            // redraws; Compose cannot see inside them.
            @Suppress("UNUSED_EXPRESSION")
            state.version
            val shown = fitInto(
                IntSize(size.width.roundToInt(), size.height.roundToInt()),
                state.base.width,
                state.base.height,
            )
            if (shown.width <= 0f) return@Canvas
            // Transparency has to look like transparency, or an erased
            // background reads as a white one. Only under the picture, so
            // where the sticker ends is plain to see.
            val cell = size.width / 16f
            var row = 0
            while (shown.top + row * cell < shown.bottom) {
                var column = 0
                while (shown.left + column * cell < shown.right) {
                    val x = shown.left + column * cell
                    val y = shown.top + row * cell
                    drawRect(
                        color = if ((row + column) % 2 == 0) Color(0xFFE0E0E0) else Color(0xFFF5F5F5),
                        topLeft = Offset(x, y),
                        size = Size(min(cell, shown.right - x), min(cell, shown.bottom - y)),
                    )
                    column++
                }
                row++
            }
            val offset = IntOffset(shown.left.roundToInt(), shown.top.roundToInt())
            val target = IntSize(shown.width.roundToInt(), shown.height.roundToInt())
            state.outline?.let {
                drawImage(it.asImageBitmap(), dstOffset = offset, dstSize = target)
            }
            drawImage(state.subject.asImageBitmap(), dstOffset = offset, dstSize = target)
            // The picture before a cut-out or an undo, fading off the new one.
            dissolve.from?.let { before ->
                drawImage(before, dstOffset = offset, dstSize = target, alpha = dissolve.fade.value)
            }
            if (!showBrush) return@Canvas
            // Two rings, dark under light: the brush has to read on a white
            // subject, on a black one and on the checkerboard behind both.
            val radius = brushPx * state.unit / 2f * (shown.width / state.base.width)
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = radius,
                center = shown.center,
                style = Stroke(width = 3f),
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center = shown.center,
                style = Stroke(width = 1.5f),
            )
        }
    }
}

private fun canvasBitmap(width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

/**
 * The editor's pixels: the decoded source, the part of it the crop keeps, the
 * mask over that and the border under it.
 *
 * The bitmaps are mutated in place, so Compose cannot diff them; [version] is
 * the signal instead, read inside every draw and bumped by every change.
 */
private class EditorState(
    source: Bitmap,
    restored: StickerEditState? = null,
    restoredMask: Bitmap? = null,
) {

    /** The picture the crop is taken from: the decoded one, or what Upscale made of the crop. */
    var source by mutableStateOf(source)
        private set

    /** Whether [source] is an upscale, which the save keeps as the sticker's original. */
    var upscaled = false
        private set

    /** The part of [source] to keep, in its pixels. The crop step moves it. */
    var crop by mutableStateOf(CropRect(0f, 0f, source.width.toFloat(), source.height.toFloat()))

    /** The cropped picture, pixel for pixel out of [source]. */
    var base: Bitmap
        private set

    /** Opaque white keeps a pixel; cleared pixels are erased. */
    private var mask: Bitmap

    /** [base] with [mask] applied — what the user is looking at. */
    var subject: Bitmap
        private set

    /** The border layer, drawn behind [subject]; null when there is none. */
    var outline: Bitmap? = null
        private set

    var version by mutableIntStateOf(0)
        private set

    /** In pixels of the 512 canvas, as the slider shows it; see [unit]. */
    var border by mutableStateOf(OutlineSpec())
        private set

    var undoDepth by mutableIntStateOf(0)
        private set

    /** Whether anything has been erased yet; a re-crop would throw it away. */
    var masked = false
        private set

    /** The rectangle [applyCrop] last kept, in source pixels. */
    private var croppedFrom: Rect = Rect(0, 0, source.width, source.height)

    private val undo = ArrayDeque<Bitmap>()

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val restorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = AndroidColor.WHITE
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    /**
     * Canvas pixels to one pixel of the 512 canvas. The brush and the border
     * are sized on that canvas, as they were when every sticker was 512
     * square, so a slider value and a kept edit mean the same on a sticker of
     * any size.
     */
    val unit: Float
        get() = maxOf(base.width, base.height) / StickerImage.TARGET_SIZE.toFloat()

    init {
        restored?.let { edit ->
            border = edit.border
            val left = (edit.cropLeft * source.width).coerceIn(0f, source.width - 1f)
            val top = (edit.cropTop * source.height).coerceIn(0f, source.height - 1f)
            crop = CropRect(
                left,
                top,
                ((edit.cropLeft + edit.cropWidth) * source.width).coerceIn(left + 1f, source.width.toFloat()),
                ((edit.cropTop + edit.cropHeight) * source.height).coerceIn(top + 1f, source.height.toFloat()),
            )
        }
        val first = cropRect()
        base = canvasBitmap(first.width(), first.height())
        mask = canvasBitmap(first.width(), first.height())
        subject = canvasBitmap(first.width(), first.height())
        resetMask()
        restoredMask?.let {
            // Cleared first, and not drawn over the white [resetMask] just
            // laid down: a kept mask says what to erase with holes in it, and
            // holes drawn SRC_OVER onto opaque white are no holes at all. That
            // is the whole of a restored cut-out coming back uncut.
            val canvas = AndroidCanvas(mask)
            canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // A mask kept before stickers took their own shape is the crop
            // letterboxed into a 512 square; the crop sits in the middle of
            // it. A mask kept since is the crop's own shape, and this is all
            // of it.
            val within = fitInto(IntSize(it.width, it.height), mask.width, mask.height)
            val from = Rect(
                within.left.roundToInt(),
                within.top.roundToInt(),
                within.right.roundToInt(),
                within.bottom.roundToInt(),
            )
            canvas.drawBitmap(it, from, Rect(0, 0, mask.width, mask.height), null)
            masked = true
        }
        applyCrop()
    }

    /** [crop] in whole source pixels, never empty. */
    private fun cropRect(): Rect {
        val left = crop.left.roundToInt().coerceIn(0, source.width - 1)
        val top = crop.top.roundToInt().coerceIn(0, source.height - 1)
        return Rect(
            left,
            top,
            crop.right.roundToInt().coerceIn(left + 1, source.width),
            crop.bottom.roundToInt().coerceIn(top + 1, source.height),
        )
    }

    /** Bakes [crop] into [base], which takes the crop's own size. */
    fun applyCrop() {
        val src = cropRect()
        // A new size only ever comes out of the crop step, and the crop step
        // is only reached with nothing erased (or after agreeing to lose it),
        // so the mask has nothing in it to keep.
        if (src.width() != base.width || src.height() != base.height) {
            base.recycle()
            mask.recycle()
            subject.recycle()
            base = canvasBitmap(src.width(), src.height())
            mask = canvasBitmap(src.width(), src.height())
            subject = canvasBitmap(src.width(), src.height())
            resetMask()
        }
        croppedFrom = src
        val canvas = AndroidCanvas(base)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(source, src, Rect(0, 0, base.width, base.height), null)
        recompose()
    }

    /** The edit as it would be resumed later: crop, border and brush. */
    fun exportState(brushPx: Float) = StickerEditState(
        cropLeft = croppedFrom.left.toFloat() / source.width,
        cropTop = croppedFrom.top.toFloat() / source.height,
        cropWidth = croppedFrom.width().toFloat() / source.width,
        cropHeight = croppedFrom.height().toFloat() / source.height,
        borderWidthPx = border.widthPx,
        borderColor = border.color,
        brushPx = brushPx,
        hasMask = masked,
    )

    /**
     * The mask as a lossless PNG, or null when nothing was erased. Lossless
     * because a JPEG-ish edge on a mask is a halo of half-erased pixels the
     * next session would inherit and could not see to fix.
     */
    fun maskPng(): ByteArray? {
        if (!masked) return null
        val out = ByteArrayOutputStream()
        return if (mask.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }

    /** Clears every erased pixel and the undo history with it. */
    fun resetMask() {
        AndroidCanvas(mask).drawColor(AndroidColor.WHITE, PorterDuff.Mode.SRC)
        masked = false
        undo.forEach { it.recycle() }
        undo.clear()
        undoDepth = 0
        recompose()
    }

    /** One undo step per stroke, and one before a cutout. */
    fun pushUndo() {
        undo.addLast(mask.extractAlpha())
        if (undo.size > UNDO_DEPTH) undo.removeFirst().recycle()
        undoDepth = undo.size
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        undoDepth = undo.size
        val canvas = AndroidCanvas(mask)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(previous, 0f, 0f, Paint().apply { color = AndroidColor.WHITE })
        previous.recycle()
        masked = undo.isNotEmpty() || masked
        recompose()
    }

    /** Draws one segment of a stroke into the mask; [brushPx] is on the 512 canvas. */
    fun paint(from: Offset, to: Offset, brushPx: Float, erase: Boolean) {
        val paint = if (erase) erasePaint else restorePaint
        paint.strokeWidth = brushPx * unit
        AndroidCanvas(mask).drawLine(from.x, from.y, to.x, to.y, paint)
        if (erase) masked = true
        recompose()
    }

    /**
     * Makes [up], the upscaled crop, the picture: the source, uncropped, with
     * the erased parts scaled to its size. The undo steps go, being masks of
     * the old size.
     */
    fun adoptUpscaled(up: Bitmap) {
        val scaledMask = Bitmap.createScaledBitmap(mask, up.width, up.height, true)
        val newMask = if (scaledMask.isMutable) scaledMask else scaledMask.copy(Bitmap.Config.ARGB_8888, true).also { scaledMask.recycle() }
        base.recycle()
        mask.recycle()
        subject.recycle()
        undo.forEach { it.recycle() }
        undo.clear()
        undoDepth = 0
        source = up
        crop = CropRect(0f, 0f, up.width.toFloat(), up.height.toFloat())
        croppedFrom = Rect(0, 0, up.width, up.height)
        base = up.copy(Bitmap.Config.ARGB_8888, true)
        mask = newMask
        subject = canvasBitmap(up.width, up.height)
        upscaled = true
        recompose()
    }

    /** Replaces the mask with a segmenter's own, keeping nothing else. */
    fun applyAlphaMask(alpha: Bitmap) {
        val canvas = AndroidCanvas(mask)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(alpha, null, Rect(0, 0, mask.width, mask.height), Paint().apply {
            color = AndroidColor.WHITE
        })
        masked = true
        recompose()
    }

    fun changeBorder(spec: OutlineSpec) {
        border = spec
        recompose()
    }

    /** [border] in this canvas's pixels. */
    private fun scaledBorder(): OutlineSpec = border.copy(widthPx = border.widthPx * unit)

    /** The flattened sticker: border under subject, on transparency. */
    fun render(): Bitmap = StickerOutline.render(subject, scaledBorder())

    /**
     * What the canvas is showing right now, border and all, as a picture of
     * its own: the "before" a dissolve fades from. Left to the collector
     * rather than recycled, because the frame that last drew it may still be
     * on its way to the screen when the dissolve lets go of it.
     */
    fun snapshot(): Bitmap {
        val out = canvasBitmap(subject.width, subject.height)
        val canvas = AndroidCanvas(out)
        outline?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.drawBitmap(subject, 0f, 0f, null)
        return out
    }

    fun recycle() {
        base.recycle()
        mask.recycle()
        subject.recycle()
        outline?.recycle()
        undo.forEach { it.recycle() }
        undo.clear()
    }

    /** Rebuilds what is drawn: the masked subject, then its border. */
    private fun recompose() {
        val canvas = AndroidCanvas(subject)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(base, 0f, 0f, null)
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        outline?.recycle()
        outline = StickerOutline.renderLayer(subject, scaledBorder())
        version++
    }
}

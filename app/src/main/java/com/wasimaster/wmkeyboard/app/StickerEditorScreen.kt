package com.wasimaster.wmkeyboard.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Restore

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

/** The sticker canvas, and so the size everything in the editor works at. */
private const val CANVAS = StickerImage.TARGET_SIZE

/** As far as the crop step zooms in. */
private const val MAX_CROP_ZOOM = 6f

/** How deep undo goes. Each step is one alpha snapshot, 256 KB. */
private const val UNDO_DEPTH = 12

private const val MIN_BRUSH_PX = 8f
private const val MAX_BRUSH_PX = 96f

/** How long the brush ring stays up after the slider stops moving. */
private const val BRUSH_PREVIEW_MS = 1100L

/** What the editor is doing to the picture right now. */
private enum class EditorMode { CROP, ERASE, RESTORE, BORDER }

/**
 * Crop, background removal and a border, for one sticker.
 *
 * Everything happens at 512×512, the sticker canvas: the crop is applied
 * first and the result letterboxed into that square, so from then on a brush
 * pixel, a mask pixel and an output pixel are the same pixel and nothing has
 * to be projected between coordinate systems.
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

    // Whether the one-tap cutout is even a thing in this build. False in lite,
    // where the brushes are the whole story and a disabled button would only
    // raise a question the screen cannot answer.
    val cutoutSupported = SubjectCutout.supported
    var modelReady by remember { mutableStateOf(false) }
    LaunchedEffect(cutoutSupported) {
        if (cutoutSupported) {
            MlKitInit.ensure(context.applicationContext)
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
                    state.pushUndo()
                    state.applyAlphaMask(result.alpha)
                    result.alpha.recycle()
                }
                SubjectCutout.Result.NoSubject ->
                    error = context.getString(R.string.import_sticker_editor_cutout_none_error)
                else -> error = context.getString(R.string.import_sticker_editor_cutout_error)
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
                            original = StickerImage.encodeOriginal(request.bytes),
                        )
                    } else {
                        // A re-edit already has an original on file — see
                        // StickerPackStore.replaceStickerImage for the one
                        // that gets promoted when it does not.
                        store.replaceStickerImage(request.packId, request.stickerId, it)
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
                else -> R.string.import_sticker_editor_caption
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
        if (mode == EditorMode.CROP) {
            CropCanvas(state)
        } else {
            EditorCanvas(state, mode, brushPx, showBrush)
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

    when (mode) {
        EditorMode.CROP -> CaptionText(stringResource(R.string.import_sticker_editor_crop_hint))
        EditorMode.ERASE, EditorMode.RESTORE -> {
            SliderRow(
                title = stringResource(R.string.import_sticker_editor_brush_size_label),
                value = brushPx,
                range = MIN_BRUSH_PX..MAX_BRUSH_PX,
                display = { "${it.roundToInt()}" },
            ) { brushPx = it }
        }
        EditorMode.BORDER -> {
            SliderRow(
                title = stringResource(R.string.import_sticker_editor_border_width_label),
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

    if (cutoutSupported && mode != EditorMode.CROP) {
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.import_sticker_editor_cutout_action),
                    subtitle = if (modelReady) {
                        stringResource(R.string.import_sticker_editor_cutout_subtitle)
                    } else {
                        stringResource(R.string.import_sticker_editor_cutout_download_subtitle)
                    },
                    enabled = busy == null,
                    onClick = { runCutout() },
                )
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
            onClick = { state.undo() },
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

/** The crop step: the picture pans and zooms under a square frame. */
@Composable
private fun CropCanvas(state: EditorState) {
    val source = state.source
    var frame by remember { mutableStateOf(IntSize.Zero) }

    fun coverScale(): Float =
        if (frame == IntSize.Zero) 1f
        else max(frame.width / source.width.toFloat(), frame.height / source.height.toFloat())

    fun clamp(offset: Offset, zoom: Float): Offset {
        val scale = coverScale() * zoom
        val maxX = ((source.width * scale - frame.width) / 2f).coerceAtLeast(0f)
        val maxY = ((source.height * scale - frame.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned {
                if (frame != it.size) {
                    frame = it.size
                    state.onFrame(it.size)
                    state.cropOffset = clamp(state.cropOffset, state.cropZoom)
                }
            }
            .pointerInput(source) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    state.cropZoom = (state.cropZoom * gestureZoom).coerceIn(1f, MAX_CROP_ZOOM)
                    state.cropOffset = clamp(state.cropOffset + pan, state.cropZoom)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val scale = coverScale() * state.cropZoom
            val width = (source.width * scale).roundToInt()
            val height = (source.height * scale).roundToInt()
            drawImage(
                image = source.asImageBitmap(),
                dstOffset = IntOffset(
                    ((size.width - width) / 2f + state.cropOffset.x).roundToInt(),
                    ((size.height - height) / 2f + state.cropOffset.y).roundToInt(),
                ),
                dstSize = IntSize(width, height),
            )
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
) {
    val erasing = mode == EditorMode.ERASE
    val painting = mode == EditorMode.ERASE || mode == EditorMode.RESTORE
    var side by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { side = it.size.width }
            .then(
                if (!painting) Modifier else Modifier.pointerInput(mode, brushPx, side) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        down.consume()
                        if (side <= 0) return@awaitEachGesture
                        val scale = CANVAS.toFloat() / side
                        state.pushUndo()
                        var last = down.position * scale
                        state.paint(last, last, brushPx, erasing)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val next = change.position * scale
                            state.paint(last, next, brushPx, erasing)
                            last = next
                            change.consume()
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            // Transparency has to look like transparency, or an erased
            // background reads as a white one.
            val cell = size.width / 16f
            var row = 0
            while (row * cell < size.height) {
                var column = 0
                while (column * cell < size.width) {
                    val dark = (row + column) % 2 == 0
                    drawRect(
                        color = if (dark) Color(0xFFE0E0E0) else Color(0xFFF5F5F5),
                        topLeft = Offset(column * cell, row * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                    column++
                }
                row++
            }
            // Read the counter so a mutation of the bitmaps in place still
            // redraws; Compose cannot see inside them.
            @Suppress("UNUSED_EXPRESSION")
            state.version
            val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
            state.outline?.let {
                drawImage(it.asImageBitmap(), dstOffset = IntOffset.Zero, dstSize = target)
            }
            drawImage(
                state.subject.asImageBitmap(),
                dstOffset = IntOffset.Zero,
                dstSize = target,
            )
            if (!showBrush) return@Canvas
            // Two rings, dark under light: the brush has to read on a white
            // subject, on a black one and on the checkerboard behind both.
            val radius = brushPx / 2f * (size.width / CANVAS)
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )
        }
    }
}

/**
 * The editor's pixels: the decoded source, the square it is cropped into, the
 * mask over it and the border under it.
 *
 * The bitmaps are mutated in place, so Compose cannot diff them; [version] is
 * the signal instead, read inside every draw and bumped by every change.
 */
private class EditorState(
    val source: Bitmap,
    restored: StickerEditState? = null,
    restoredMask: Bitmap? = null,
) {

    /** The cropped picture, letterboxed into the sticker canvas. */
    val base: Bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)

    /** Opaque white keeps a pixel; cleared pixels are erased. */
    private val mask: Bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)

    /** [base] with [mask] applied — what the user is looking at. */
    val subject: Bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)

    /** The border layer, drawn behind [subject]; null when there is none. */
    var outline: Bitmap? = null
        private set

    var version by mutableIntStateOf(0)
        private set

    var border by mutableStateOf(OutlineSpec())
        private set

    var undoDepth by mutableIntStateOf(0)
        private set

    /** Whether anything has been erased yet; a re-crop would throw it away. */
    var masked = false
        private set

    var cropZoom by mutableFloatStateOf(1f)
    var cropOffset by mutableStateOf(Offset.Zero)
    var cropFrame: IntSize = IntSize.Zero

    /**
     * The crop a resumed edit arrived with, in source pixels, until a measured
     * frame can turn it back into a pan and a zoom the crop step can show.
     * [applyCrop] honours it in the meantime, so the picture is right from the
     * first frame rather than after one.
     */
    private var restoreRect: Rect? = null

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

    init {
        resetMask()
        restored?.let { edit ->
            border = edit.border
            restoreRect = Rect(
                (edit.cropLeft * source.width).roundToInt().coerceIn(0, source.width - 1),
                (edit.cropTop * source.height).roundToInt().coerceIn(0, source.height - 1),
                ((edit.cropLeft + edit.cropWidth) * source.width).roundToInt()
                    .coerceIn(1, source.width),
                ((edit.cropTop + edit.cropHeight) * source.height).roundToInt()
                    .coerceIn(1, source.height),
            )
        }
        restoredMask?.let {
            // Cleared first, and not drawn over the white [resetMask] just
            // laid down: a kept mask says what to erase with holes in it, and
            // holes drawn SRC_OVER onto opaque white are no holes at all. That
            // is the whole of a restored cut-out coming back uncut.
            val canvas = AndroidCanvas(mask)
            canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(it, null, Rect(0, 0, CANVAS, CANVAS), null)
            masked = true
        }
        applyCrop()
    }

    /**
     * Bakes the current pan and zoom into [base], or the crop a resumed edit
     * arrived with while the crop step has not been measured yet.
     */
    fun applyCrop() {
        val frame = cropFrame
        val canvas = AndroidCanvas(base)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val pending = restoreRect
        val src = if (pending != null) {
            pending
        } else if (frame == IntSize.Zero) {
            Rect(0, 0, source.width, source.height)
        } else {
            val scale = max(
                frame.width / source.width.toFloat(),
                frame.height / source.height.toFloat(),
            ) * cropZoom
            val width = (frame.width / scale).roundToInt().coerceIn(1, source.width)
            val height = (frame.height / scale).roundToInt().coerceIn(1, source.height)
            val left = ((source.width - width) / 2f - cropOffset.x / scale)
                .roundToInt().coerceIn(0, source.width - width)
            val top = ((source.height - height) / 2f - cropOffset.y / scale)
                .roundToInt().coerceIn(0, source.height - height)
            Rect(left, top, left + width, top + height)
        }
        croppedFrom = src
        // Letterboxed, not stretched: a crop frame is square but a source
        // that was never cropped is whatever shape it arrived in.
        val scale = CANVAS.toFloat() / maxOf(src.width(), src.height())
        val outWidth = (src.width() * scale).roundToInt().coerceIn(1, CANVAS)
        val outHeight = (src.height() * scale).roundToInt().coerceIn(1, CANVAS)
        val left = (CANVAS - outWidth) / 2
        val top = (CANVAS - outHeight) / 2
        canvas.drawBitmap(
            source,
            src,
            Rect(left, top, left + outWidth, top + outHeight),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        recompose()
    }

    /**
     * Takes the crop step's measured frame, and turns a resumed edit's crop
     * rectangle back into the pan and zoom that frame would express it with.
     * Until this runs the crop step has no pixel size to work in, which is why
     * the rectangle is carried rather than converted up front.
     */
    fun onFrame(size: IntSize) {
        cropFrame = size
        val rect = restoreRect ?: return
        restoreRect = null
        if (size.width <= 0 || rect.width() <= 0) return
        val cover = max(
            size.width / source.width.toFloat(),
            size.height / source.height.toFloat(),
        )
        cropZoom = (size.width / rect.width().toFloat() / cover).coerceIn(1f, MAX_CROP_ZOOM)
        val applied = cover * cropZoom
        cropOffset = Offset(
            ((source.width - size.width / applied) / 2f - rect.left) * applied,
            ((source.height - size.height / applied) / 2f - rect.top) * applied,
        )
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

    /** Draws one segment of a stroke into the mask. */
    fun paint(from: Offset, to: Offset, widthPx: Float, erase: Boolean) {
        val paint = if (erase) erasePaint else restorePaint
        paint.strokeWidth = widthPx
        AndroidCanvas(mask).drawLine(from.x, from.y, to.x, to.y, paint)
        if (erase) masked = true
        recompose()
    }

    /** Replaces the mask with a segmenter's own, keeping nothing else. */
    fun applyAlphaMask(alpha: Bitmap) {
        val canvas = AndroidCanvas(mask)
        canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(alpha, null, Rect(0, 0, CANVAS, CANVAS), Paint().apply {
            color = AndroidColor.WHITE
        })
        masked = true
        recompose()
    }

    fun changeBorder(spec: OutlineSpec) {
        border = spec
        recompose()
    }

    /** The flattened sticker: border under subject, on transparency. */
    fun render(): Bitmap = StickerOutline.render(subject, border)

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
        outline = StickerOutline.renderLayer(subject, border)
        version++
    }
}

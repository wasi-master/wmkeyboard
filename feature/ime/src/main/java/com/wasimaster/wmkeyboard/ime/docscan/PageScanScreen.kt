package com.wasimaster.wmkeyboard.ime.docscan

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.text.format.Formatter
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.docscan.DocScanNative
import com.wasimaster.wmkeyboard.core.docscan.DocScanner
import com.wasimaster.wmkeyboard.core.docscan.PageCorners
import com.wasimaster.wmkeyboard.core.docscan.PageFilter
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CropFree
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RotateRight
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.app.DocScanPages
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.awaitCapture
import com.wasimaster.wmkeyboard.ime.ui.hasCameraPermission
import com.wasimaster.wmkeyboard.ime.ui.hasCameraSafe
import com.wasimaster.wmkeyboard.ime.ui.toFramedBitmap
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The longest side a photo is scanned at: about 5 MP, A4 at some 220 dpi,
 * which keeps print sharp and three full-size bitmaps of it (photo, page,
 * filtered page) well inside an app's heap.
 */
private const val MAX_PHOTO_SIDE = 2560

/** How often the viewfinder looks for the page, at most. */
private const val LIVE_INTERVAL_MS = 120L

/** A photo taken or picked, and where the page sits in it. */
private class Shot(val photo: Bitmap, val detected: PageCorners?, val corners: PageCorners)

private sealed interface Stage {
    data object Camera : Stage

    class Adjust(val shot: Shot) : Stage

    class Review(val shot: Shot, val page: Bitmap) : Stage
}

/**
 * The built-in document scanner, full screen: the camera with the page it
 * sees outlined, then the four corners to drag where the detector missed,
 * then the flattened page with its clean-up and a turn, then another page
 * or done. [onDone] gets every kept page as a JPEG in [DocScanPages.dir];
 * the keyboard sends them the way it sends Google's scanner's.
 *
 * Runs on OpenCV (libwmdocscan) with no Play services. When this build has
 * to fetch that library first, the screen offers the download, asking the
 * way [downloads] says data saver wants.
 */
@Composable
internal fun PageScanScreen(
    downloads: MeteredDecision,
    onDone: (List<File>) -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val module by DocScanNative.moduleState.collectAsState()
    val ready by produceState<Boolean?>(null, module) {
        value = withContext(Dispatchers.IO) { DocScanNative.available }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        when (ready) {
            null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            false -> LibraryPrompt(module, downloads, onOpenSettings, onCancel)
            true -> Scanner(onDone, onCancel)
        }
    }
}

@Composable
private fun Scanner(onDone: (List<File>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Camera) }
    val pages = remember { mutableStateListOf<File>() }
    var busy by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    fun take(photo: Bitmap) {
        busy = true
        scope.launch {
            val found = withContext(Dispatchers.Default) { DocScanner.detect(photo) }?.first
            val inset = min(photo.width, photo.height) * 0.06f
            val corners = found ?: PageCorners.whole(photo.width, photo.height, inset)
            stage = Stage.Adjust(Shot(photo, found, corners))
            busy = false
        }
    }

    fun keep(page: Bitmap, then: () -> Unit) {
        busy = true
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    File(DocScanPages.dir(context), "SCAN_${System.currentTimeMillis()}_${pages.size}.jpg").also { f ->
                        f.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    }
                }.getOrNull()
            }
            busy = false
            if (file != null) {
                pages += file
                then()
            } else {
                Toast.makeText(context, R.string.ime_docscan_save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun discardAll() {
        val gone = pages.toList()
        pages.clear()
        scope.launch(Dispatchers.IO) { gone.forEach { it.delete() } }
        onCancel()
    }

    BackHandler {
        when (val s = stage) {
            is Stage.Review -> stage = Stage.Adjust(s.shot)
            is Stage.Adjust -> stage = Stage.Camera
            Stage.Camera -> if (pages.isEmpty()) onCancel() else askDiscard = true
        }
    }

    when (val s = stage) {
        Stage.Camera -> CameraStage(
            pageCount = pages.size,
            busy = busy,
            onPhoto = ::take,
            onDone = { onDone(pages.toList()) },
            onClose = { if (pages.isEmpty()) onCancel() else askDiscard = true },
        )
        is Stage.Adjust -> AdjustStage(
            shot = s.shot,
            busy = busy,
            onCorners = { stage = Stage.Adjust(Shot(s.shot.photo, s.shot.detected, it)) },
            onRetake = { stage = Stage.Camera },
            onNext = {
                busy = true
                scope.launch {
                    val page = withContext(Dispatchers.Default) {
                        DocScanner.flatten(s.shot.photo, s.shot.corners, MAX_PHOTO_SIDE)
                    }
                    busy = false
                    if (page != null) {
                        stage = Stage.Review(s.shot, page)
                    } else {
                        Toast.makeText(context, R.string.ime_docscan_crop_error, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        is Stage.Review -> ReviewStage(
            page = s.page,
            pageNumber = pages.size + 1,
            busy = busy,
            onBack = { stage = Stage.Adjust(s.shot) },
            onAddPage = { shown -> keep(shown) { stage = Stage.Camera } },
            onDone = { shown -> keep(shown) { onDone(pages.toList()) } },
        )
    }

    if (busy) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.ime_docscan_discard_title)) },
            text = {
                Text(
                    context.resources.getQuantityString(R.plurals.ime_docscan_discard_body, pages.size, pages.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    onDone(pages.toList())
                }) { Text(stringResource(R.string.ime_docscan_send)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askDiscard = false
                    discardAll()
                }) { Text(stringResource(R.string.ime_docscan_discard)) }
            },
        )
    }
}

/** The page the viewfinder sees, in the upright frame's pixels. */
private class LiveQuad(val corners: PageCorners, val width: Int, val height: Int)

@Composable
private fun CameraStage(
    pageCount: Int,
    busy: Boolean,
    onPhoto: (Bitmap) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(hasCameraPermission(context)) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) askCamera.launch(Manifest.permission.CAMERA) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val photo = withContext(Dispatchers.IO) { MediaPrep.decode(context, uri, MAX_PHOTO_SIDE) }
            if (photo != null) {
                onPhoto(photo)
            } else {
                Toast.makeText(context, R.string.ime_docscan_photo_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var live by remember { mutableStateOf<LiveQuad?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // One 4:3 shape for all three streams, so the viewfinder, the frames the
    // outline is found in and the photo all frame the same scene.
    val fourThree = remember {
        AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(fourThree)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(MAX_PHOTO_SIDE, MAX_PHOTO_SIDE * 3 / 4),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    val provider by produceState<ProcessCameraProvider?>(null, permitted) {
        if (permitted) value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }

    DisposableEffect(provider) {
        val cameraProvider = provider
        val executor = Executors.newSingleThreadExecutor()
        if (cameraProvider != null && cameraProvider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)) {
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourThree)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            var last = 0L
            var misses = 0
            analysis.setAnalyzer(executor) { proxy ->
                proxy.use {
                    val now = System.currentTimeMillis()
                    if (now - last < LIVE_INTERVAL_MS) return@use
                    last = now
                    val frame = it.toBitmap()
                    val rotation = it.imageInfo.rotationDegrees
                    val found = DocScanner.detect(frame)?.first
                    val (w, h) = if (rotation % 180 == 0) frame.width to frame.height else frame.height to frame.width
                    if (found != null) {
                        misses = 0
                        live = LiveQuad(found.rotated(rotation, frame.width, frame.height), w, h)
                    } else if (++misses >= 3) {
                        // A frame or two without it is a hand shaking, not the page gone.
                        live = null
                    }
                    frame.recycle()
                }
            }
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis,
                )
            }.getOrNull()
        }
        onDispose {
            camera = null
            live = null
            runCatching { cameraProvider?.unbindAll() }
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (permitted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                LiveOutline(live)
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.ime_docscan_camera_permission),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.ime_docscan_allow_camera))
                    }
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, stringResource(CommonR.string.common_close), tint = Color.White)
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(
                        if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        stringResource(R.string.ime_docscan_torch_desc),
                        tint = Color.White,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                IconButton(onClick = pickPhoto, enabled = !busy) {
                    Icon(Icons.Outlined.PhotoLibrary, stringResource(R.string.ime_docscan_pick_photo), tint = Color.White)
                }
            }
            // The shutter: a white ring, as in every camera.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .padding(4.dp)
                    .background(Color.Black, CircleShape)
                    .padding(3.dp)
                    .background(if (permitted && !busy) Color.White else Color.Gray, CircleShape)
                    .clickable(enabled = permitted && !busy) {
                        scope.launch {
                            val photo = runCatching {
                                val proxy = imageCapture.awaitCapture(context)
                                withContext(Dispatchers.Default) { proxy.use { p -> p.toFramedBitmap(false) } }
                            }.getOrNull()
                            if (photo != null) {
                                onPhoto(photo)
                            } else {
                                Toast.makeText(context, R.string.ime_docscan_capture_error, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (pageCount > 0) {
                    Button(onClick = onDone, enabled = !busy) {
                        Text(stringResource(R.string.ime_docscan_done_count, pageCount))
                    }
                }
            }
        }
    }
}

/**
 * The page the viewfinder sees, drawn over it. The preview fills the box
 * (FILL_CENTER) from the same 4:3 frame the outline was found in, so one
 * scale and centring offset map the one onto the other.
 */
@Composable
private fun LiveOutline(live: LiveQuad?) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val quad = live ?: return@Canvas
        val s = max(size.width / quad.width, size.height / quad.height)
        val ox = (size.width - quad.width * s) / 2f
        val oy = (size.height - quad.height * s) / 2f
        val path = Path().apply {
            for (i in 0 until 4) {
                val x = ox + quad.corners.x(i) * s
                val y = oy + quad.corners.y(i) * s
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, accent.copy(alpha = 0.22f))
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun AdjustStage(
    shot: Shot,
    busy: Boolean,
    onCorners: (PageCorners) -> Unit,
    onRetake: () -> Unit,
    onNext: () -> Unit,
) {
    val photo = shot.photo
    val image = remember(photo) { photo.asImageBitmap() }
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val corners by rememberUpdatedState(shot.corners)
    val moveTo by rememberUpdatedState(onCorners)
    var dragging by remember { mutableIntStateOf(-1) }
    val whole = remember(photo) { PageCorners.whole(photo.width, photo.height) }
    val showingWhole = shot.corners.toArray().contentEquals(whole.toArray())

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(
                if (shot.detected != null) R.string.ime_docscan_adjust_hint else R.string.ime_docscan_adjust_not_found,
            ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp, vertical = 8.dp)) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val s = min(boxW / photo.width, boxH / photo.height)
            val ox = (boxW - photo.width * s) / 2f
            val oy = (boxH - photo.height * s) / 2f
            val grab = with(density) { 44.dp.toPx() }

            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(s, ox, oy) {
                        detectDragGestures(
                            onDragStart = { at ->
                                val c = corners
                                dragging = (0 until 4)
                                    .minByOrNull { hypot(ox + c.x(it) * s - at.x, oy + c.y(it) * s - at.y) }
                                    ?.takeIf { hypot(ox + c.x(it) * s - at.x, oy + c.y(it) * s - at.y) <= grab }
                                    ?: -1
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                            onDrag = { change, drag ->
                                val i = dragging
                                if (i >= 0) {
                                    change.consume()
                                    val c = corners
                                    val x = (c.x(i) + drag.x / s).coerceIn(0f, photo.width - 1f)
                                    val y = (c.y(i) + drag.y / s).coerceIn(0f, photo.height - 1f)
                                    moveTo(c.moved(i, x, y))
                                }
                            },
                        )
                    },
            ) {
                val c = shot.corners
                val points = (0 until 4).map { Offset(ox + c.x(it) * s, oy + c.y(it) * s) }
                val quad = Path().apply {
                    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                    close()
                }
                // Everything outside the page dimmed, the page left bright.
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(ox, oy, ox + photo.width * s, oy + photo.height * s))
                    addPath(quad)
                }
                drawPath(outside, Color.Black.copy(alpha = 0.5f))
                val ok = c.convex
                val line = if (ok) accent else Color(0xFFE57373)
                drawPath(quad, line, style = Stroke(width = 2.dp.toPx()))
                points.forEachIndexed { i, p ->
                    drawCircle(Color.White, radius = (if (i == dragging) 14 else 11).dp.toPx(), center = p)
                    drawCircle(line, radius = (if (i == dragging) 11 else 8).dp.toPx(), center = p)
                }
                // A loupe while a corner moves: the finger covers exactly
                // the spot it is placing.
                val i = dragging
                if (i >= 0) {
                    val loupe = 120.dp.toPx()
                    val zoom = 2.5f
                    val half = (loupe / 2f / zoom / s).roundToInt().coerceAtLeast(1)
                    val srcW = min(half * 2, photo.width)
                    val srcH = min(half * 2, photo.height)
                    val left = (c.x(i).roundToInt() - half).coerceIn(0, photo.width - srcW)
                    val top = (c.y(i).roundToInt() - half).coerceIn(0, photo.height - srcH)
                    // Opposite the finger, so the loupe never sits under it.
                    val lx = if (points[i].x > size.width / 2) 12.dp.toPx() else size.width - loupe - 12.dp.toPx()
                    val ly = 12.dp.toPx()
                    val circle = Path().apply { addOval(Rect(lx, ly, lx + loupe, ly + loupe)) }
                    clipPath(circle) {
                        drawImage(
                            image,
                            srcOffset = IntOffset(left, top),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()),
                            dstSize = IntSize(loupe.roundToInt(), loupe.roundToInt()),
                        )
                    }
                    drawPath(circle, Color.White, style = Stroke(width = 2.dp.toPx()))
                    val cx = lx + (c.x(i) - left) / srcW * loupe
                    val cy = ly + (c.y(i) - top) / srcH * loupe
                    drawLine(line, Offset(cx - 10.dp.toPx(), cy), Offset(cx + 10.dp.toPx(), cy), 1.5.dp.toPx())
                    drawLine(line, Offset(cx, cy - 10.dp.toPx()), Offset(cx, cy + 10.dp.toPx()), 1.5.dp.toPx())
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRetake, enabled = !busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.ime_docscan_retake))
            }
            Spacer(Modifier.weight(1f))
            // The detector's corners or the whole picture, whichever is not showing.
            val detected = shot.detected
            if (detected != null && showingWhole) {
                IconButton(onClick = { onCorners(detected) }) {
                    Icon(Icons.Outlined.DocumentScanner, stringResource(R.string.ime_docscan_detected), tint = Color.White)
                }
            } else if (!showingWhole) {
                IconButton(onClick = { onCorners(whole) }) {
                    Icon(Icons.Outlined.CropFree, stringResource(R.string.ime_docscan_whole), tint = Color.White)
                }
            }
            FilledIconButton(onClick = onNext, enabled = !busy && shot.corners.convex) {
                Icon(Icons.Outlined.Check, stringResource(R.string.ime_docscan_next))
            }
        }
    }
}

@Composable
private fun ReviewStage(
    page: Bitmap,
    pageNumber: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onAddPage: (Bitmap) -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    var filter by remember(page) { mutableStateOf(PageFilter.COLOUR) }
    var turns by remember(page) { mutableIntStateOf(0) }
    val shown by produceState<Bitmap?>(null, page, filter, turns) {
        value = withContext(Dispatchers.Default) { turned(DocScanner.filtered(page, filter), turns) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.ime_docscan_page_number, pageNumber),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            val current = shown
            if (current == null) {
                CircularProgressIndicator()
            } else {
                val image = remember(current) { current.asImageBitmap() }
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(stringResource(filterLabel(option))) },
                )
            }
            IconButton(onClick = { turns = (turns + 1) % 4 }) {
                Icon(Icons.Outlined.RotateRight, stringResource(R.string.ime_docscan_rotate_desc), tint = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack, enabled = !busy) {
                Text(stringResource(CommonR.string.common_back))
            }
            Spacer(Modifier.weight(1f))
            val current = shown
            OutlinedButton(onClick = { current?.let(onAddPage) }, enabled = !busy && current != null) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.ime_docscan_add_page))
            }
            Button(onClick = { current?.let(onDone) }, enabled = !busy && current != null) {
                Text(stringResource(CommonR.string.common_done))
            }
        }
    }
}

private fun filterLabel(filter: PageFilter): Int = when (filter) {
    PageFilter.ORIGINAL -> R.string.ime_docscan_filter_original
    PageFilter.COLOUR -> R.string.ime_docscan_filter_colour
    PageFilter.GREY -> R.string.ime_docscan_filter_grey
    PageFilter.BLACK_WHITE -> R.string.ime_docscan_filter_bw
}

/** [page] turned a quarter clockwise [turns] times. */
private fun turned(page: Bitmap, turns: Int): Bitmap {
    if (turns % 4 == 0) return page
    val matrix = Matrix().apply { postRotate(90f * turns) }
    return Bitmap.createBitmap(page, 0, 0, page.width, page.height, matrix, true)
}

/**
 * The library is not here: on its way, waiting for the download opt-in,
 * held by data saver, or never coming on this phone. The same choices as
 * the text scanner's library prompt in the keyboard.
 */
@Composable
private fun LibraryPrompt(
    module: ModuleState,
    downloads: MeteredDecision,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var meteredAsked by remember { mutableStateOf(false) }
    val gate = FeatureModules.docscan
    val size = (gate as? DownloadedModuleGate)?.missingBytes ?: 0L

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        val missing = module as? ModuleState.Missing
        val text = when {
            module is ModuleState.Installing -> stringResource(R.string.ime_docscan_library_downloading)
            module == ModuleState.Installed -> stringResource(R.string.ime_docscan_library_unavailable)
            missing?.consent == NativeConsent.IMPORT -> stringResource(R.string.ime_scanner_library_import)
            missing?.consent != null -> stringResource(R.string.ime_scanner_library_consent)
            downloads == MeteredDecision.BLOCKED -> stringResource(R.string.ime_metered_off_body)
            meteredAsked -> stringResource(R.string.ime_metered_ask_body)
            missing?.failed == true -> stringResource(OfflineBuild.library(R.string.ime_docscan_library_failed))
            size > 0L -> stringResource(R.string.ime_docscan_library_body_sized, Formatter.formatShortFileSize(context, size))
            else -> stringResource(R.string.ime_docscan_library_body)
        }
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        when {
            module is ModuleState.Installing -> {
                if (module.totalBytes > 0L) {
                    CircularProgressIndicator(progress = { (module.bytes.toFloat() / module.totalBytes).coerceIn(0f, 1f) })
                } else {
                    CircularProgressIndicator()
                }
            }
            module == ModuleState.Installed || downloads == MeteredDecision.BLOCKED -> Unit
            missing?.consent != null -> Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.ime_docscan_open_settings))
            }
            else -> Button(onClick = {
                if (downloads == MeteredDecision.ASK && !meteredAsked) meteredAsked = true else gate.requestInstall()
            }) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(
                        when {
                            meteredAsked -> R.string.ime_metered_allow_action
                            missing?.failed == true -> CommonR.string.common_retry
                            else -> CommonR.string.common_download
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_close)) }
    }
}


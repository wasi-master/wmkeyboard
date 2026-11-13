package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.app.DocScanPages
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.MainActivityContract
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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RotateRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.docscan.CornerEditor
import com.wasimaster.wmkeyboard.ime.docscan.LiveOutline
import com.wasimaster.wmkeyboard.ime.docscan.LiveQuad
import com.wasimaster.wmkeyboard.ime.docscan.MAX_PHOTO_SIDE
import com.wasimaster.wmkeyboard.ime.docscan.Shot
import com.wasimaster.wmkeyboard.ime.docscan.filterLabel
import com.wasimaster.wmkeyboard.ime.docscan.savePage
import com.wasimaster.wmkeyboard.ime.docscan.scanAnalysis
import com.wasimaster.wmkeyboard.ime.docscan.scanImageCapture
import com.wasimaster.wmkeyboard.ime.docscan.scanPreview
import com.wasimaster.wmkeyboard.ime.docscan.shotOf
import com.wasimaster.wmkeyboard.ime.docscan.turned
import com.wasimaster.wmkeyboard.ime.files.FilePickRequest
import com.wasimaster.wmkeyboard.ime.files.FilePicks
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the keyboard's document scanner hands its finished pages: the service,
 * which sends them into the field the panel is open over. Provided at the
 * root, the way [LocalScreenCapture] is.
 */
internal val LocalDocScanPages = staticCompositionLocalOf<(List<File>) -> Unit> { {} }

/**
 * The pages of the scan under way in the keyboard. Kept out of the panel's
 * composition: picking a photo through the keyboard's own file manager swaps
 * the panel out and back, and the pages already taken must still be there.
 * Only the service's process and main thread touch it.
 */
internal object InlineDocScan {
    val pages = mutableStateListOf<File>()

    /** The pages, handed over and forgotten. */
    fun take(): List<File> = pages.toList().also { pages.clear() }

    /** The pages thrown away, files and all. */
    fun discard() {
        val gone = take()
        if (gone.isNotEmpty()) Thread { gone.forEach { it.delete() } }.start()
    }
}

private sealed interface DocStage {
    data object Camera : DocStage

    class Adjust(val shot: Shot) : DocStage

    class Review(val shot: Shot, val page: Bitmap) : DocStage
}

/**
 * The built-in document scanner in the keyboard, in place of the keys: the
 * camera with the page it sees outlined, then the corners to drag, then the
 * flattened page with its clean-up and a turn, then another page or done.
 * The same steps and the same OpenCV library as the full-screen scanner
 * ([com.wasimaster.wmkeyboard.app.PageScanActivity]), so the field the user
 * is typing in never loses the keyboard; the pages go to it through
 * [LocalDocScanPages] the moment Done is pressed.
 *
 * [cameraFrame] wraps the viewfinder stage only, for the camera's mode rail:
 * over the corner editor and the page it would sit on the handles.
 */
@Composable
internal fun DocScanPanel(
    state: KeyboardUiState,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    cameraFrame: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    val height = keyRowsHeight(state) + fullBleedHiddenRows(state)
    val module by DocScanNative.moduleState.collectAsState()
    val ready by produceState<Boolean?>(null, module) {
        value = withContext(Dispatchers.IO) { DocScanNative.available }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.Black),
    ) {
        when (ready) {
            null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            false -> {
                DocLibraryPrompt(state, module)
                BackChip(onClose, Modifier.align(Alignment.TopStart))
            }
            true -> DocScanSession(onRequestPermission, onClose, cameraFrame)
        }
    }
}

@Composable
private fun DocScanSession(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    cameraFrame: @Composable (@Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalKeyPressFeedback.current
    val sendPages = LocalDocScanPages.current
    val pages = InlineDocScan.pages
    var stage by remember { mutableStateOf<DocStage>(DocStage.Camera) }
    var busy by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    // Scans the keyboard never got to send pile up only if the process died
    // mid-send; a scan under way keeps every page it has.
    LaunchedEffect(Unit) {
        if (pages.isEmpty()) withContext(Dispatchers.IO) { DocScanPages.prune(context) }
    }

    fun take(photo: Bitmap) {
        busy = true
        scope.launch {
            stage = DocStage.Adjust(shotOf(photo))
            busy = false
        }
    }

    fun keep(page: Bitmap, then: () -> Unit) {
        busy = true
        scope.launch {
            val file = savePage(context, page, pages.size)
            busy = false
            if (file != null) {
                pages += file
                then()
            } else {
                Toast.makeText(context, R.string.ime_docscan_save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun finish() {
        feedback()
        stage = DocStage.Camera
        sendPages(InlineDocScan.take())
    }

    fun close() {
        feedback()
        if (pages.isEmpty()) onClose() else askDiscard = true
    }

    // A photo picked from the gallery, read once the picker hands the keyboard back.
    var picked by remember { mutableStateOf<Uri?>(null) }
    PermissionRecheck { GalleryPickActivity.consumeDoc()?.let { picked = it } }
    LaunchedEffect(picked) {
        val uri = picked ?: return@LaunchedEffect
        picked = null
        busy = true
        val photo = withContext(Dispatchers.IO) { MediaPrep.decode(context, uri, MAX_PHOTO_SIDE) }
        busy = false
        if (photo != null) {
            take(photo)
        } else {
            Toast.makeText(context, R.string.ime_docscan_photo_error, Toast.LENGTH_SHORT).show()
        }
    }

    when (val s = stage) {
        DocStage.Camera -> cameraFrame {
            DocCameraStage(
                pageCount = pages.size,
                busy = busy,
                onRequestPermission = onRequestPermission,
                onPhoto = ::take,
                onPick = {
                    feedback()
                    runCatching { FilePicks.open(context, FilePickRequest.DocPicture) }
                },
                onDone = ::finish,
                onClose = ::close,
            )
        }
        is DocStage.Adjust -> DocAdjustStage(
            shot = s.shot,
            busy = busy,
            onCorners = { stage = DocStage.Adjust(s.shot.withCorners(it)) },
            onRetake = {
                feedback()
                stage = DocStage.Camera
            },
            onNext = {
                feedback()
                busy = true
                scope.launch {
                    val page = withContext(Dispatchers.Default) {
                        DocScanner.flatten(s.shot.photo, s.shot.corners, MAX_PHOTO_SIDE)
                    }
                    busy = false
                    if (page != null) {
                        stage = DocStage.Review(s.shot, page)
                    } else {
                        Toast.makeText(context, R.string.ime_docscan_crop_error, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        is DocStage.Review -> DocReviewStage(
            page = s.page,
            pageNumber = pages.size + 1,
            busy = busy,
            onBack = {
                feedback()
                stage = DocStage.Adjust(s.shot)
            },
            onAddPage = { shown ->
                feedback()
                keep(shown) { stage = DocStage.Camera }
            },
            onDone = { shown -> keep(shown) { finish() } },
        )
    }

    if (busy) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
    }

    if (askDiscard) {
        DiscardCard(
            count = pages.size,
            onSend = {
                askDiscard = false
                finish()
            },
            onDiscard = {
                feedback()
                askDiscard = false
                InlineDocScan.discard()
                onClose()
            },
            onDismiss = { askDiscard = false },
        )
    }
}

@Composable
private fun DocCameraStage(
    pageCount: Int,
    busy: Boolean,
    onRequestPermission: () -> Unit,
    onPhoto: (Bitmap) -> Unit,
    onPick: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val feedback = LocalKeyPressFeedback.current

    var permitted by remember { mutableStateOf(hasCameraPermission(context)) }
    PermissionRecheck { permitted = hasCameraPermission(context) }
    var live by remember { mutableStateOf<LiveQuad?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // As in the other camera panels: a SurfaceView ignores Compose's
            // bounds in the input method's window; a TextureView does not.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember { scanImageCapture() }
    val provider by produceState<ProcessCameraProvider?>(null, permitted) {
        if (permitted) value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }

    DisposableEffect(provider) {
        val cameraProvider = provider
        val executor = Executors.newSingleThreadExecutor()
        val selector = cameraProvider?.let(::backOrFrontSelector)
        if (cameraProvider != null && selector != null) {
            val preview = scanPreview().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = scanAnalysis(executor) { live = it }
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
            }.getOrNull()
        }
        onDispose {
            camera = null
            live = null
            runCatching { cameraProvider?.unbindAll() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val activeProvider = provider
        when {
            !permitted -> CameraPermissionPrompt(stringResource(R.string.ime_docscan_camera_permission), onRequestPermission)
            activeProvider != null && backOrFrontSelector(activeProvider) == null ->
                PanelCenteredMessage(stringResource(R.string.ime_scanner_no_camera))
            else -> {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                LiveOutline(live, kb.accent)
            }
        }
        // Which page this one will be, so a scan of several always says where it is.
        HintPill(stringResource(R.string.ime_docscan_page_number, pageCount + 1), Modifier.align(Alignment.TopCenter))
        BackChip(onClose, Modifier.align(Alignment.TopStart))
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A photo of a page already on the phone; needs no camera at all.
            CameraChipButton(
                icon = Icons.Outlined.PhotoLibrary,
                description = stringResource(R.string.ime_docscan_pick_photo),
                active = false,
                onClick = onPick,
            )
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                CameraChipButton(
                    icon = if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    description = stringResource(R.string.ime_docscan_torch_desc),
                    active = torch,
                ) {
                    feedback()
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }
            }
        }
        if (permitted) {
            // The shutter, the same as the text scanner's.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(54.dp)
                    .alpha(if (busy || camera == null) 0.5f else 1f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .pointerInput(busy, camera) {
                        detectTapGestures {
                            if (busy || camera == null) return@detectTapGestures
                            feedback()
                            scope.launch {
                                val photo = runCatching {
                                    val proxy = imageCapture.awaitCapture(context)
                                    withContext(Dispatchers.Default) { proxy.use { p -> p.toFramedBitmap(mirror = false) } }
                                }.getOrNull()
                                if (photo != null) {
                                    onPhoto(photo)
                                } else {
                                    Toast.makeText(context, R.string.ime_docscan_capture_error, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
            )
        }
        if (pageCount > 0) {
            CaptureActionButton(
                icon = Icons.Outlined.Check,
                label = stringResource(R.string.ime_docscan_done_count, pageCount),
                accent = true,
                // Left of the rail's column, which sits over the right edge's middle.
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun DocAdjustStage(
    shot: Shot,
    busy: Boolean,
    onCorners: (PageCorners) -> Unit,
    onRetake: () -> Unit,
    onNext: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val photo = shot.photo
    val whole = remember(photo) { PageCorners.whole(photo.width, photo.height) }
    val showingWhole = shot.corners.toArray().contentEquals(whole.toArray())

    // Side columns rather than bars: the panel is wide and short, and every
    // row of height a bar took would come off the page being placed.
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_docscan_retake),
                active = false,
                onClick = onRetake,
            )
            // The detector's corners or the whole picture, whichever is not showing.
            val detected = shot.detected
            if (detected != null && showingWhole) {
                CameraChipButton(
                    icon = Icons.Outlined.DocumentScanner,
                    description = stringResource(R.string.ime_docscan_detected),
                    active = false,
                ) {
                    feedback()
                    onCorners(detected)
                }
            } else if (!showingWhole) {
                CameraChipButton(
                    icon = Icons.Outlined.CropFree,
                    description = stringResource(R.string.ime_docscan_whole),
                    active = false,
                ) {
                    feedback()
                    onCorners(whole)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CornerEditor(
                shot = shot,
                accent = kb.accent,
                onCorners = onCorners,
                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            )
            HintPill(
                stringResource(
                    if (shot.detected != null) R.string.ime_docscan_adjust_hint else R.string.ime_docscan_adjust_not_found,
                ),
                Modifier.align(Alignment.BottomCenter),
            )
        }
        Box(Modifier.fillMaxHeight().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
            val ok = !busy && shot.corners.convex
            CaptureActionButton(
                icon = Icons.Outlined.Check,
                label = stringResource(R.string.ime_docscan_next),
                accent = true,
                modifier = Modifier.alpha(if (ok) 1f else 0.5f),
            ) {
                if (ok) onNext()
            }
        }
    }
}

@Composable
private fun DocReviewStage(
    page: Bitmap,
    pageNumber: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onAddPage: (Bitmap) -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    var filter by remember(page) { mutableStateOf(PageFilter.COLOUR) }
    var turns by remember(page) { mutableIntStateOf(0) }
    val shown by produceState<Bitmap?>(null, page, filter, turns) {
        value = withContext(Dispatchers.Default) { turned(DocScanner.filtered(page, filter), turns) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(CommonR.string.common_back),
                active = false,
                onClick = onBack,
            )
            CameraChipButton(
                icon = Icons.Outlined.RotateRight,
                description = stringResource(R.string.ime_docscan_rotate_desc),
                active = false,
            ) {
                feedback()
                turns = (turns + 1) % 4
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                val current = shown
                if (current == null) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Image(
                        remember(current) { current.asImageBitmap() },
                        contentDescription = stringResource(R.string.ime_docscan_page_number, pageNumber),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                HintPill(stringResource(R.string.ime_docscan_page_number, pageNumber), Modifier.align(Alignment.TopCenter))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                PageFilter.entries.forEach { option ->
                    val on = filter == option
                    Text(
                        stringResource(filterLabel(option)),
                        color = if (on) kb.toolCircleActiveIcon else Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) kb.toolCircleActive else Color.White.copy(alpha = 0.15f))
                            .clickable {
                                feedback()
                                filter = option
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxHeight().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            horizontalAlignment = Alignment.End,
        ) {
            val current = shown
            val ok = !busy && current != null
            CaptureActionButton(
                icon = Icons.Outlined.Add,
                label = stringResource(R.string.ime_docscan_add_page),
                accent = false,
                modifier = Modifier.alpha(if (ok) 1f else 0.5f),
            ) {
                if (ok) current?.let(onAddPage)
            }
            CaptureActionButton(
                icon = Icons.Outlined.Check,
                label = stringResource(R.string.ime_docscan_send),
                accent = true,
                modifier = Modifier.alpha(if (ok) 1f else 0.5f),
            ) {
                if (ok) current?.let(onDone)
            }
        }
    }
}

/** Closing a scan that has pages: send them or throw them away, asked over the panel (no dialogs in a keyboard). */
@Composable
private fun DiscardCard(count: Int, onSend: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.ime_docscan_discard_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.ime_docscan_discard_body, count, count),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptureActionButton(
                    icon = Icons.Outlined.Close,
                    label = stringResource(R.string.ime_docscan_discard),
                    accent = false,
                    onClick = onDiscard,
                )
                CaptureActionButton(
                    icon = Icons.Outlined.Check,
                    label = stringResource(R.string.ime_docscan_send),
                    accent = true,
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun BackChip(onClose: () -> Unit, modifier: Modifier) {
    Box(modifier.padding(8.dp)) {
        CameraChipButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            description = stringResource(CommonR.string.common_close),
            active = false,
            onClick = onClose,
        )
    }
}

/** A line of white text on a dark pill, over a photo. */
@Composable
private fun HintPill(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = modifier
            .padding(top = 10.dp, bottom = 6.dp, start = 56.dp, end = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The scanner's library is not here: on its way, waiting for the download
 * opt-in, held by data saver, or never coming on this phone. The full-screen
 * scanner's prompt in the keyboard's colours, data saver asked on the button.
 */
@Composable
private fun DocLibraryPrompt(state: KeyboardUiState, module: ModuleState) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    val gate = FeatureModules.docscan
    val size = (gate as? DownloadedModuleGate)?.missingBytes ?: 0L
    var meteredAsked by remember { mutableStateOf(false) }
    val downloads = state.dataSaver.decide(MeteredFeature.DOWNLOADS)
    val missing = module as? ModuleState.Missing

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (module is ModuleState.Installing) {
            if (module.totalBytes > 0L) {
                CircularProgressIndicator(
                    progress = { (module.bytes.toFloat() / module.totalBytes).coerceIn(0f, 1f) },
                    color = kb.accent,
                )
            } else {
                CircularProgressIndicator(color = kb.accent)
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            when {
                module is ModuleState.Installing -> stringResource(R.string.ime_docscan_library_downloading)
                module == ModuleState.Installed -> stringResource(R.string.ime_docscan_library_unavailable)
                missing?.consent == NativeConsent.IMPORT -> stringResource(R.string.ime_scanner_library_import)
                missing?.consent != null -> stringResource(R.string.ime_scanner_library_consent)
                downloads == MeteredDecision.BLOCKED -> stringResource(R.string.ime_metered_off_body)
                meteredAsked -> stringResource(R.string.ime_metered_ask_body)
                missing?.failed == true -> stringResource(OfflineBuild.library(R.string.ime_docscan_library_failed))
                size > 0L -> stringResource(R.string.ime_docscan_library_body_sized, Formatter.formatShortFileSize(context, size))
                else -> stringResource(R.string.ime_docscan_library_body)
            },
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        when {
            module is ModuleState.Installing || module == ModuleState.Installed || downloads == MeteredDecision.BLOCKED -> Unit
            missing?.consent != null -> ScannerPromptButton(Icons.Outlined.Settings, stringResource(R.string.ime_docscan_open_settings)) {
                feedback()
                runCatching {
                    context.startActivity(
                        MainActivityContract.intent(context)
                            .putExtra(MainActivityContract.EXTRA_OPEN_SETTING, "privacy_native_downloads_title")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            else -> ScannerPromptButton(
                Icons.Outlined.FileDownload,
                stringResource(
                    when {
                        meteredAsked -> R.string.ime_metered_allow_action
                        missing?.failed == true -> CommonR.string.common_retry
                        else -> CommonR.string.common_download
                    },
                ),
            ) {
                feedback()
                if (downloads == MeteredDecision.ASK && !meteredAsked) meteredAsked = true else gate.requestInstall()
            }
        }
    }
}

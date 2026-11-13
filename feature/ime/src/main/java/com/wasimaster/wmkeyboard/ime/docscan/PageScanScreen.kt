package com.wasimaster.wmkeyboard.ime.docscan

import android.Manifest
import android.graphics.Bitmap
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            stage = Stage.Adjust(shotOf(photo))
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
            onCorners = { stage = Stage.Adjust(s.shot.withCorners(it)) },
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
    val imageCapture = remember { scanImageCapture() }
    val provider by produceState<ProcessCameraProvider?>(null, permitted) {
        if (permitted) value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }

    DisposableEffect(provider) {
        val cameraProvider = provider
        val executor = Executors.newSingleThreadExecutor()
        if (cameraProvider != null && cameraProvider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)) {
            val preview = scanPreview().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = scanAnalysis(executor) { live = it }
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
                LiveOutline(live, MaterialTheme.colorScheme.primary)
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

@Composable
private fun AdjustStage(
    shot: Shot,
    busy: Boolean,
    onCorners: (PageCorners) -> Unit,
    onRetake: () -> Unit,
    onNext: () -> Unit,
) {
    val photo = shot.photo
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
        CornerEditor(
            shot = shot,
            accent = MaterialTheme.colorScheme.primary,
            onCorners = onCorners,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp, vertical = 8.dp),
        )
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


package com.wasimaster.wmkeyboard.ime.ui

import android.util.Size
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.LinkPreview
import com.wasimaster.wmkeyboard.core.tools.EggLinks
import com.wasimaster.wmkeyboard.core.tools.LinkPreviewClient
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.ime.files.FilePickRequest
import com.wasimaster.wmkeyboard.ime.files.FilePicks
import android.net.Uri
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.ime.scan.BarcodeReader
import com.wasimaster.wmkeyboard.ime.scan.ScannedBarcode
import com.wasimaster.wmkeyboard.ime.scan.barcodeReader
import com.wasimaster.wmkeyboard.ime.scan.labelRes

/** A decoded barcode frozen on screen, awaiting insert/copy/rescan. */
private class ScannedCode(val value: String, @StringRes val formatLabelRes: Int)

/**
 * Barcode & QR scanner: a live viewfinder that decodes continuously, on
 * the device, with ML Kit or zxing-cpp ([barcodeReader]); the first hit
 * freezes into a result card with insert and copy actions. Insert types the
 * barcode's text into the editor, like any other key press. The button in the bottom-left corner
 * reads a code off a saved picture instead, picked with Android's photo
 * picker, and lands on the same card.
 */
@Composable
internal fun QrScanPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
) {
    // Full-bleed like the OCR tool: the toolbar hides, the viewfinder
    // absorbs its height and the back button sits at the very top instead
    // of below a blank strip.
    val height = keyRowsHeight(state) + fullBleedHiddenRows(state)
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    PermissionRecheck { hasPermission = hasCameraPermission(context) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val reader = remember(state.settings.scanner.qrZxing) { barcodeReader(state.settings.scanner.qrZxing) }
        DisposableEffect(reader) { onDispose { reader.close() } }
        val module by reader.gate.state.collectAsState()
        val libraryReady = remember(reader, module) { reader.ready() }
        if (hasPermission && !libraryReady) {
            ScannerLibraryPrompt(state, reader.gate)
        } else if (hasPermission) {
            QrScanContent(
                state = state,
                reader = reader,
                onInsert = onInsert,
                onShare = onShare,
                onOpenUrl = onOpenUrl,
                haptics = state.settings.scanner.qrScanHaptics,
                autoInsert = state.settings.scanner.qrScanAutoInsert,
                linkPreviews = state.settings.scanner.qrScanLinkPreviews,
            )
        } else {
            CameraPermissionPrompt(
                stringResource(R.string.ime_scanner_qr_permission_body),
                onRequestPermission,
            )
        }
        val feedback = LocalKeyPressFeedback.current
        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_qr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
        }
    }
}

@Composable
private fun QrScanContent(
    state: KeyboardUiState,
    reader: BarcodeReader,
    onInsert: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    haptics: Boolean,
    autoInsert: Boolean,
    linkPreviews: Boolean,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedback = LocalKeyPressFeedback.current
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<ScannedCode?>(null) }
    // A computer's pairing code (WMK Desktop): pairs straight away rather
    // than typing the link. The name it shows while that happens.
    var pairingWith by remember { mutableStateOf<String?>(null) }
    // A picked picture being decoded and read.
    var readingPicture by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    // Current zoom, driven by pinch and shown as a pill. Zooming into a
    // small or distant code is often the difference between a decode and
    // not. Resets to 1x whenever the camera rebinds (e.g. after Rescan).
    var zoomRatio by remember(camera) { mutableFloatStateOf(1f) }

    val provider by produceState<ProcessCameraProvider?>(null) {
        value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // A decoded code, from the camera or a picture: freeze into the result
    // card either way. With auto-insert the text is already committed and
    // the card shows what went in (Rescan re-arms).
    fun found(hit: ScannedBarcode) {
        val raw = hit.rawValue
        if (raw.isEmpty() || result != null) return
        if (haptics) feedback()
        result = ScannedCode(raw, hit.kind.labelRes())
        val pair = KdePairCode.parse(raw)
        if (pair != null) {
            KdeConnectHub.attach(context)
            if (KdeConnectHub.pairByCode(raw)) pairingWith = pair.name
        } else if (autoInsert) {
            onInsert(raw)
        }
    }

    // A picture back from the photo picker. Collected on every resume: the
    // picker is its own activity, so the keyboard comes back with the pick.
    fun readPicture(uri: Uri) {
        readingPicture = true
        scope.launch {
            val hit = withContext(Dispatchers.Default) {
                val bitmap = MediaPrep.decode(context, uri, QR_PICTURE_MAX_SIDE) ?: return@withContext null
                runCancellable { reader.readPicture(bitmap) }.getOrNull()
            }
            readingPicture = false
            if (hit == null) {
                Toast.makeText(context, R.string.ime_scanner_qr_picture_none, Toast.LENGTH_SHORT).show()
            } else {
                found(hit)
            }
        }
    }
    PermissionRecheck { GalleryPickActivity.consumeQr()?.let(::readPicture) }

    // Decode frames only while there's no result on screen. A frame is
    // skipped when the previous one is still being decoded (busy flag)
    // instead of queueing — barcodes don't need every frame. zxing-cpp
    // answers on the analysis thread; the result card and the insert belong
    // on the main one, where ML Kit's answers already arrive.
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val scanning = result == null
    DisposableEffect(provider, scanning, reader) {
        val cameraProvider = provider
        var busy = false
        if (cameraProvider != null && scanning) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                if (busy || result != null) {
                    proxy.close()
                    return@setAnalyzer
                }
                busy = true
                reader.readFrame(proxy) { hit ->
                    mainExecutor.execute {
                        hit?.let(::found)
                        busy = false
                    }
                }
            }
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    backOrFrontSelector(cameraProvider) ?: return@runCatching null,
                    preview,
                    analysis,
                )
            }.getOrNull()
            camera?.cameraControl?.enableTorch(torchOn)
        }
        onDispose {
            camera = null
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val code = result
        if (code != null) {
            // A scanned code that is a bare URL gets an Open button and,
            // when link previews are on, its page title/description below.
            val url = remember(code) { ClipLinks.asUrl(code.value) }
            var preview by remember(code) { mutableStateOf<LinkPreview?>(null) }
            if (linkPreviews && url != null) {
                LaunchedEffect(url) {
                    preview = withContext(Dispatchers.IO) { LinkPreviewClient.fetch(url) }
                }
            }
            // The ring lives on this result card only; the viewfinder below
            // publishes nothing. Open leads when the code is a URL.
            PanelFocusTarget(
                panel = PanelMode.QR_SCAN,
                region = FocusRegion.ACTIONS,
                count = if (url != null) 5 else 4,
                columns = 5,
            ) { index ->
                val slot = if (url != null) index else index + 1
                when (slot) {
                    0 -> url?.let { feedback(); onOpenUrl(it) }
                    1 -> { feedback(); result = null; pairingWith = null }
                    2 -> {
                        feedback()
                        copyPlainText(context, code.value)
                        Toast.makeText(
                            context,
                            R.string.ime_scanner_copied_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    3 -> { feedback(); onShare(code.value) }
                    4 -> onInsert(code.value)
                }
            }
            val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
                ?.let { if (url != null) it else it + 1 }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LinkPreviewCard(linkPreviews && url != null && pairingWith == null, preview)
                pairingWith?.let { name ->
                    Text(
                        stringResource(R.string.ime_kde_pairing_by_code, name.ifEmpty { stringResource(R.string.ime_kde_pairing_by_code_unnamed) }),
                        color = kb.suggestionText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Easter egg: the one link every QR scanner eventually meets.
                // Matched on the raw payload, not on `url` — ClipLinks.asUrl
                // only accepts a bare URL and a rickroll deserves recognition
                // even when it travels with company.
                if (remember(code) { EggLinks.isRickroll(code.value) }) {
                    Text(
                        stringResource(R.string.ime_qr_rickroll_info),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    code.value,
                    color = kb.suggestionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(code.formatLabelRes),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                // Open (URLs only) gets its own row above the rest, so the
                // primary action isn't crowded in with Rescan/Copy/Insert.
                if (url != null) {
                    CaptureActionButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        label = stringResource(R.string.ime_scanner_qr_open_action),
                        accent = false,
                        focused = focusedAction == 0,
                    ) {
                        feedback()
                        onOpenUrl(url)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(
                        12.dp, Alignment.CenterHorizontally,
                    ),
                ) {
                    CaptureActionButton(
                        icon = Icons.Outlined.Refresh,
                        label = stringResource(R.string.ime_scanner_scan_again_action),
                        accent = false,
                        focused = focusedAction == 1,
                    ) {
                        feedback()
                        result = null
                        pairingWith = null
                    }
                    CaptureActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = stringResource(CommonR.string.common_copy),
                        accent = false,
                        focused = focusedAction == 2,
                    ) {
                        feedback()
                        copyPlainText(context, code.value)
                        Toast.makeText(
                            context,
                            R.string.ime_scanner_copied_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    CaptureActionButton(
                        icon = Icons.Outlined.Share,
                        label = stringResource(CommonR.string.common_share),
                        accent = false,
                        focused = focusedAction == 3,
                    ) {
                        feedback()
                        onShare(code.value)
                    }
                    CaptureActionButton(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        label = stringResource(R.string.ime_scanner_insert_action),
                        accent = true,
                        focused = focusedAction == 4,
                    ) { onInsert(code.value) }
                }
            }
            return@Box
        }

        val activeProvider = provider
        if (activeProvider != null && backOrFrontSelector(activeProvider) == null) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_no_camera))
            return@Box
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                // Pinch to zoom, clamped to what the lens supports.
                .pointerInput(camera) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val info = cam.cameraInfo.zoomState.value
                        val minZoom = info?.minZoomRatio ?: 1f
                        val maxZoom = info?.maxZoomRatio ?: 1f
                        val current = info?.zoomRatio ?: zoomRatio
                        val next = (current * gestureZoom).coerceIn(minZoom, maxZoom)
                        cam.cameraControl.setZoomRatio(next)
                        zoomRatio = next
                    }
                },
        )

        if (zoomRatio > 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.ime_scanner_qr_zoom_label, zoomRatio),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            stringResource(R.string.ime_scanner_qr_hint),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Clear of the picture button in the corner.
                .padding(start = 60.dp, end = 60.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            CameraChipButton(
                icon = Icons.Outlined.PhotoLibrary,
                description = stringResource(R.string.ime_scanner_qr_picture_desc),
                active = false,
            ) {
                feedback()
                runCatching { FilePicks.open(context, FilePickRequest.QrPicture) }
            }
        }
        if (readingPicture) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                CameraChipButton(
                    icon = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    description = stringResource(R.string.ime_scanner_torch_desc),
                    active = torchOn,
                ) {
                    feedback()
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                }
            }
        }
    }
}

/** The longest side a picked picture is read at: plenty for a code, and never the full camera frame. */
private const val QR_PICTURE_MAX_SIDE = 2048

/**
 * The fetched page title/site/description for a scanned link, shown above
 * the raw URL. A small spinner stands in while the fetch is in flight;
 * a failed or empty fetch renders nothing (the URL alone still shows).
 */
@Composable
private fun LinkPreviewCard(enabled: Boolean, preview: LinkPreview?) {
    if (!enabled) return
    val kb = LocalKbTheme.current
    if (preview == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                color = kb.secondaryText,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.ime_scanner_qr_link_progress),
                color = kb.secondaryText,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        return
    }
    if (preview.failed || preview.isEmpty) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (preview.title.isNotBlank()) {
            Text(
                preview.title,
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.siteName.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(preview.siteName, color = kb.secondaryText, fontSize = 11.sp)
        }
        if (preview.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                preview.description,
                color = kb.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.tools.OnlineOcr
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.ime.files.FilePickRequest
import com.wasimaster.wmkeyboard.ime.files.FilePicks
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Deselect
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcr
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.ocr.TesseractException
import com.wasimaster.wmkeyboard.core.ocr.TesseractOcr
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.ime.ocr.MlKitOcr
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- OCR (text recognition) tool ----

/** One recognized word, tappable in the result view. [id] is global. */
private class OcrWord(val id: Int, val text: String)

/**
 * A frozen capture with its recognized text, as lines of words. [errorRes]
 * is set when recognition failed, which is not the same as finding no text.
 */
private class OcrResult(
    val bitmap: Bitmap,
    val lines: List<List<OcrWord>>,
    @StringRes val errorRes: Int? = null,
) {
    val wordCount = lines.sumOf { it.size }
}

private sealed interface OcrStage {
    data object Viewfinder : OcrStage
    data class Recognizing(val bitmap: Bitmap) : OcrStage
    data class Done(val result: OcrResult) : OcrStage
}

/**
 * OCR tool: point the camera at printed text, capture, and get the words
 * back as tappable chips — deselect the parts you don't want, then insert
 * or copy just the rest. Reads with ML Kit's on-device recognizer for the
 * text's script (Latin, Chinese, Japanese, Korean or Devanagari), with
 * PaddleOCR for Chinese and Japanese where ML Kit has no recognizer for them
 * (#757), or with Tesseract, which covers most other scripts; both of those
 * once their data is downloaded. The setting and the text's language pick
 * which ([OcrLanguages.offlinePack], [OcrLanguages.mlKitRecognizer]). Either
 * way the photo never leaves the phone, unless the setting is the online
 * reader.
 *
 * In every build: ML Kit is reached only through [MlKitOcr], which F-Droid's
 * build stands in for. There PaddleOCR reads Chinese, Japanese and Latin
 * script on MNN, and a language nothing here reads says so and points at the
 * online reader ([OcrLanguages.readable]).
 *
 * Unlike the other tool panels this one covers the toolbar too
 * ([keyRowsHeight] + [TopBarHeight]): reading text off a photo needs all the
 * room the keyboard has.
 *
 * Opened from an image clip's Extract text (#371), it reads that picture
 * ([KeyboardUiState.ocrImage]) instead: no camera and no permission, the
 * picture where the viewfinder would be, and the read starts on its own.
 *
 * A picture already on the phone can be read too (#869): the gallery button
 * over the viewfinder picks one, which is then read the way a capture is.
 *
 * [onTranslate] opens the Translate tool on the words picked (#807).
 */
@Composable
internal fun OcrPanel(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    onTranslate: (String) -> Unit = {},
) {
    val height = keyRowsHeight(state) + fullBleedHiddenRows(state)
    val context = LocalContext.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    PermissionRecheck { hasPermission = hasCameraPermission(context) }

    // A picture from the clipboard needs no camera, so no camera permission.
    val canRead = hasPermission || state.ocrImage != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (canRead) {
            OcrContent(
                state = state,
                onInsert = onInsert,
                onTranslate = onTranslate,
                onClose = onClose,
                autoSelect = state.settings.scanner.ocrAutoSelectWords,
            )
        } else {
            CameraPermissionPrompt(
                stringResource(R.string.ime_scanner_ocr_permission_body),
                onRequestPermission,
            )
        }
        if (!canRead) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                    onClick = onClose,
                )
            }
        }
    }
}

@Composable
private fun OcrContent(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onTranslate: (String) -> Unit,
    onClose: () -> Unit,
    autoSelect: Boolean,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val feedback = LocalKeyPressFeedback.current

    var stage by remember { mutableStateOf<OcrStage>(OcrStage.Viewfinder) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // A picture picked from the gallery (#869) is being read, or was last:
    // shown whole, like a clip's, rather than cropped to the viewfinder.
    var fromGallery by remember { mutableStateOf(false) }

    // A clip's picture (#371) stands in for the camera, which is then never
    // bound at all. Null while it loads; a failure when it will not decode.
    val clipImage = state.ocrImage
    val clipBitmap by produceState<Result<Bitmap>?>(null, clipImage) {
        value = clipImage?.let { path ->
            withContext(Dispatchers.IO) { runCatching { decodeClipImage(File(path)) } }
        }
    }
    val provider by produceState<ProcessCameraProvider?>(null, clipImage) {
        if (clipImage == null) {
            value = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        }
    }
    // One ML Kit recognizer per script read so far, made on first use.
    val recognizers = remember { MlKitOcr.Readers() }
    DisposableEffect(Unit) {
        onDispose {
            recognizers.close()
            TesseractOcr.release()
            PaddleOcr.release()
        }
    }

    // The text's language, which picks the engine and, for Tesseract and
    // Paddle, the pack. Starts as the keyboard's language; the chip steps
    // through the other enabled languages without switching the keyboard.
    val filesDir = context.filesDir
    // ML Kit or Tesseract chosen in a build without it reads as Automatic.
    val engine = OcrLanguages.usable(state.settings.scanner.ocrEngine, TesseractOcr.available)
    val packStates by OcrPacks.states.collectAsState()
    // Which packs are on disk decides between Paddle and an older Tesseract
    // pack for Chinese and Japanese; packStates keys it so a finished
    // download is seen.
    val onDisk: (String) -> Boolean = remember(packStates) { { OcrPacks.isDownloaded(filesDir, it) } }
    val chipLanguages = remember(state.settings.enabledLanguages, engine, onDisk) {
        OcrLanguages.chipLanguages(engine, state.settings.enabledLanguages, TesseractOcr.available, onDisk)
    }
    var ocrLanguage by remember(chipLanguages) {
        mutableStateOf(
            if (OcrLanguages.packFor(state.language) != null &&
                OcrLanguages.readable(engine, state.language, TesseractOcr.available, onDisk)
            ) {
                state.language
            } else {
                chipLanguages.firstOrNull() ?: state.language
            }
        )
    }
    val pack = remember(engine, ocrLanguage, onDisk) {
        OcrLanguages.offlinePack(engine, ocrLanguage, TesseractOcr.available, onDisk)
    }
    val paddle = pack == PaddleOcrModel.PACK
    // F-Droid's build reads a language only with a pack (or online); one no
    // pack covers there gets a message instead of a viewfinder.
    val readable = remember(engine, ocrLanguage, onDisk) {
        OcrLanguages.readable(engine, ocrLanguage, TesseractOcr.available, onDisk)
    }
    LaunchedEffect(pack) { if (pack != null) OcrPacks.refresh(filesDir, listOf(pack)) }
    val packStatus = pack?.let { packStates[it] }
    // A pack on disk reads only once the library that reads it is here too
    // (Tesseract's own; for Paddle the LiteRT interpreter, or MNN in the
    // sticker generator's library); until then the prompt's download fetches it.
    val ocrModule by (if (paddle) PaddleOcr.moduleState else TesseractOcr.moduleState).collectAsState()
    // ML Kit's own Latin reader brings a library of its own, which a build with
    // native delivery fetches the first time; the pack prompt's place shows
    // that download instead.
    val scannerModule by FeatureModules.scanner.state.collectAsState()
    val needsLibrary = MlKitOcr.IN_BUILD && engine != OcrEngine.ONLINE && pack == null &&
        OcrLanguages.mlKitRecognizer(ocrLanguage) == MlKitScript.LATIN
    val libraryReady = remember(needsLibrary, scannerModule) {
        !needsLibrary || MlKitOcr.ready(MlKitScript.LATIN)
    }
    val packReady = remember(pack, packStatus, ocrModule, libraryReady, readable) {
        readable && libraryReady && (
            pack == null || (
                ocrModule == ModuleState.Installed && (
                    packStatus == OcrPacks.Status.Downloaded ||
                        (packStatus == null && OcrPacks.isDownloaded(filesDir, pack))
                    )
                )
            )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // See CameraPanel: SurfaceView ignores Compose bounds in the IME
            // window; TextureView composits like a normal view.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Small print needs more pixels than chat photos, but the full
            // sensor is still overkill and slow to process.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(2048, 2048),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .build()
    }

    // Bind only while the viewfinder is up; the frozen capture and its
    // words are the whole UI afterwards, so release the camera.
    val scanning = stage is OcrStage.Viewfinder && packReady && clipImage == null
    DisposableEffect(provider, scanning, viewSize) {
        val cameraProvider = provider
        if (cameraProvider != null && scanning && viewSize != IntSize.Zero) {
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            // Shared ViewPort = WYSIWYG: the capture is cropped to exactly
            // what the viewfinder framed.
            val viewPort = ViewPort.Builder(
                Rational(viewSize.width, viewSize.height),
                previewView.display?.rotation ?: Surface.ROTATION_0,
            ).build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .build()
            camera = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, backOrFrontSelector(cameraProvider) ?: return@runCatching null, group,
                )
            }.getOrNull()
            camera?.cameraControl?.enableTorch(torchOn)
        }
        onDispose {
            camera = null
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    fun capture(auto: Boolean = false, picked: android.net.Uri? = null) {
        if (stage !is OcrStage.Viewfinder || viewSize == IntSize.Zero || !packReady) return
        if (!auto) feedback()
        fromGallery = picked != null
        val readPack = pack
        val online = engine == OcrEngine.ONLINE
        val mlKit = if (!online && readPack == null) {
            OcrLanguages.mlKitRecognizer(ocrLanguage).also(recognizers::prepare)
        } else {
            null
        }
        // The online readers (#469, #727), or in pick mode the one the chooser is on.
        val readers = if (online) OnlineOcr.rows(state.settings, ProviderPicks.of(ProviderTool.OCR)) else emptyList()
        scope.launch {
            // Parenthesised so the `?:` covers every branch, not just the last.
            val bitmap = (if (picked != null) {
                withContext(Dispatchers.IO) { MediaPrep.decode(context, picked, CLIP_OCR_MAX_SIDE) }
                    ?: run {
                        Toast.makeText(context, R.string.ime_scanner_ocr_image_error, Toast.LENGTH_SHORT).show()
                        null
                    }
            } else if (clipImage != null) {
                clipBitmap?.getOrNull()
            } else {
                withContext(Dispatchers.IO) {
                    runCancellable {
                        imageCapture.awaitCapture(context).use { it.toFramedBitmap(mirror = false) }
                    }.getOrNull()
                }
            }) ?: return@launch
            stage = OcrStage.Recognizing(bitmap)
            val read = withContext(Dispatchers.Default) {
                runCancellable {
                    if (online) {
                        readOnline(readers, bitmap)
                    } else if (readPack == PaddleOcrModel.PACK) {
                        wordsOf(PaddleOcr.recognize(context, bitmap))
                    } else if (readPack != null) {
                        readWithTesseract(filesDir, readPack, bitmap)
                    } else {
                        var id = 0
                        recognizers.read(checkNotNull(mlKit), bitmap)
                            .map { line -> line.map { OcrWord(id++, it) } }
                    }
                }
            }
            // A failed read is an error on screen, never "no text found":
            // that is what hid a broken engine from the user.
            val failure = read.exceptionOrNull()
            stage = when {
                failure == null -> OcrStage.Done(OcrResult(bitmap, read.getOrThrow()))
                // The pack went missing or would not load (and was deleted):
                // back to the viewfinder, which now offers the download.
                failure is TesseractException && readPack != null &&
                    failure.reason == TesseractException.Reason.PACK_MISSING -> {
                    OcrPacks.refresh(filesDir, listOf(readPack))
                    OcrStage.Viewfinder
                }
                failure is TesseractException &&
                    failure.reason == TesseractException.Reason.PACK_BROKEN -> OcrStage.Viewfinder
                // The online reader names its own trouble: no address set, a
                // wrong key, a model that cannot see.
                online -> {
                    Log.w(OCR_LOG_TAG, "online text reader failed", failure)
                    OcrStage.Done(
                        OcrResult(
                            bitmap,
                            emptyList(),
                            if (readers.isEmpty()) {
                                R.string.ime_scanner_ocr_online_unset
                            } else {
                                R.string.ime_scanner_ocr_online_error
                            },
                        ),
                    )
                }
                // Play: the script's model is still on its way from Play services.
                MlKitOcr.isDownloading(failure) -> OcrStage.Done(
                    OcrResult(bitmap, emptyList(), R.string.ime_scanner_ocr_model_downloading),
                )
                else -> {
                    Log.w(OCR_LOG_TAG, "text recognition failed", failure)
                    OcrStage.Done(OcrResult(bitmap, emptyList(), R.string.ime_scanner_ocr_read_error))
                }
            }
        }
    }

    // The gallery's pick lands when the picker hands the keyboard back, and
    // is read once the language's data is there, by this composition's
    // capture (the resume observer keeps the first one it was given).
    var pickedPicture by remember { mutableStateOf<android.net.Uri?>(null) }
    PermissionRecheck { GalleryPickActivity.consumeOcr()?.let { pickedPicture = it } }
    LaunchedEffect(pickedPicture, packReady, viewSize) {
        val uri = pickedPicture ?: return@LaunchedEffect
        if (packReady && viewSize != IntSize.Zero && stage is OcrStage.Viewfinder) {
            pickedPicture = null
            capture(auto = true, picked = uri)
        }
    }

    // A clip's picture is read as soon as it and the language's data are
    // there, once: after that, Scan again waits for the shutter, so the
    // language can be changed first.
    var autoRead by remember(clipImage) { mutableStateOf(clipImage != null) }
    LaunchedEffect(clipBitmap, packReady, viewSize) {
        if (autoRead && clipBitmap?.isSuccess == true && packReady && viewSize != IntSize.Zero) {
            autoRead = false
            capture(auto = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        when (val current = stage) {
            is OcrStage.Done -> {
                OcrResultView(
                    state = state,
                    result = current.result,
                    autoSelect = autoSelect,
                    onInsert = onInsert,
                    onTranslate = onTranslate,
                    onRescan = { stage = OcrStage.Viewfinder },
                    onClose = onClose,
                )
                return@Box
            }
            is OcrStage.Recognizing -> {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_scanner_ocr_capture_desc),
                    modifier = Modifier.fillMaxSize(),
                    // A copied or picked picture is shown whole: it was not framed for this panel.
                    contentScale = if (clipImage != null || fromGallery) ContentScale.Fit else ContentScale.Crop,
                )
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ime_scanner_ocr_reading_progress),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
                return@Box
            }
            OcrStage.Viewfinder -> Unit
        }

        val activeProvider = provider
        if (clipImage == null && activeProvider != null && backOrFrontSelector(activeProvider) == null) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_no_camera))
            return@Box
        }
        if (clipImage != null && clipBitmap?.isFailure == true) {
            PanelCenteredMessage(stringResource(R.string.ime_scanner_ocr_image_error))
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                ) {
                    feedback()
                    onClose()
                }
            }
            return@Box
        }

        val nextLanguage: (() -> Unit)? = if (chipLanguages.size > 1) {
            {
                feedback()
                fun route(l: LanguageDef) = OcrLanguages.route(engine, l, TesseractOcr.available, onDisk)
                val at = chipLanguages.indexOfFirst { route(it) == route(ocrLanguage) }
                ocrLanguage = chipLanguages[(at + 1).mod(chipLanguages.size)]
            }
        } else {
            null
        }

        if (!packReady && (pack != null || needsLibrary || !readable)) {
            if (!readable) {
                PanelCenteredMessage(stringResource(R.string.ime_scanner_ocr_no_reader_body, ocrLanguageName(ocrLanguage)))
            } else if (pack != null) {
                OcrPackPrompt(state, ocrLanguage, pack, packStatus, filesDir)
            } else {
                ScannerLibraryPrompt(state)
            }
            Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                CameraChipButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = stringResource(R.string.ime_scanner_ocr_close_desc),
                    active = false,
                ) {
                    feedback()
                    onClose()
                }
            }
            nextLanguage?.let {
                OcrLanguageChip(ocrLanguage, it, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
            return@Box
        }

        if (clipImage != null) {
            clipBitmap?.getOrNull()?.let { picture ->
                Image(
                    bitmap = remember(picture) { picture.asImageBitmap() },
                    contentDescription = stringResource(R.string.ime_scanner_ocr_capture_desc),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        Text(
            stringResource(if (clipImage != null) R.string.ime_scanner_ocr_image_hint else R.string.ime_scanner_ocr_hint),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_ocr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A picture already on the phone instead of the camera (#869).
            if (clipImage == null) {
                CameraChipButton(
                    icon = Icons.Outlined.PhotoLibrary,
                    description = stringResource(R.string.ime_scanner_ocr_picture_desc),
                    active = false,
                ) {
                    feedback()
                    runCatching { FilePicks.open(context, FilePickRequest.OcrPicture) }
                }
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
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
        // Shutter, same look as the camera tool's.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f))
                .padding(5.dp)
                .clip(CircleShape)
                .background(Color.White)
                // Keyed on the language and pack: capture() reads both, and a
                // Unit key would keep the first language's capture after the
                // chip moved on.
                .pointerInput(ocrLanguage, pack, packReady) { detectTapGestures { capture() } },
        )
        nextLanguage?.let {
            OcrLanguageChip(ocrLanguage, it, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        if (engine == OcrEngine.ONLINE) {
            ProviderChooser(
                ProviderTool.OCR,
                state.settings,
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                overPhoto = true,
            )
        }
    }
}

/** The name of [language] in the app's own language, for the panel's text. */
private fun ocrLanguageName(language: LanguageDef): String =
    Locale.forLanguageTag(language.localeTag).displayLanguage.ifEmpty { language.englishName }

/** The text's language over the viewfinder; tapping steps to the next one. */
@Composable
private fun OcrLanguageChip(language: LanguageDef, onClick: () -> Unit, modifier: Modifier) {
    val name = ocrLanguageName(language)
    val description = stringResource(R.string.ime_scanner_ocr_language_desc, name)
    Text(
        name,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Stands in for the viewfinder while the language's Tesseract or Paddle data
 * is not on the phone: what it costs, the button that fetches it, then its progress.
 * Data saver is asked here, on the button, the way the translate panel does:
 * the first press explains, the second is the yes.
 */
@Composable
private fun OcrPackPrompt(
    state: KeyboardUiState,
    language: LanguageDef,
    pack: String,
    status: OcrPacks.Status?,
    filesDir: File,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    var meteredAsked by remember(pack) { mutableStateOf(false) }
    var meteredBlocked by remember(pack) { mutableStateOf(false) }
    val name = ocrLanguageName(language)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (status is OcrPacks.Status.Downloading) {
            if (status.total > 0L) {
                CircularProgressIndicator(
                    progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) },
                    color = kb.accent,
                )
            } else {
                CircularProgressIndicator(color = kb.accent)
            }
            Text(
                stringResource(R.string.ime_scanner_ocr_downloading_progress, name),
                color = kb.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (status.total > 0L) {
                Text(
                    stringResource(
                        R.string.ime_scanner_ocr_download_of_total_progress,
                        Formatter.formatShortFileSize(context, status.bytes),
                        Formatter.formatShortFileSize(context, status.total),
                    ),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
            }
            ScannerPromptButton(Icons.Outlined.Close, stringResource(CommonR.string.common_cancel)) {
                feedback()
                OcrPacks.cancel(pack)
            }
            return@Column
        }

        Text(
            when {
                !InternetPermission.granted -> stringResource(R.string.ime_scanner_ocr_import)
                meteredBlocked -> stringResource(R.string.ime_metered_off_body)
                meteredAsked -> stringResource(R.string.ime_metered_ask_body)
                status is OcrPacks.Status.Failed ->
                    if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                    else stringResource(status.messageRes, status.messageArg)
                else -> stringResource(
                    R.string.ime_scanner_ocr_need_pack_body,
                    name,
                    Formatter.formatShortFileSize(context, OcrLanguages.sizeOf(pack)),
                )
            },
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (!meteredBlocked && InternetPermission.granted) {
            ScannerPromptButton(
                Icons.Outlined.FileDownload,
                stringResource(
                    when {
                        meteredAsked -> R.string.ime_metered_allow_action
                        status is OcrPacks.Status.Failed -> CommonR.string.common_retry
                        else -> CommonR.string.common_download
                    },
                ),
            ) {
                feedback()
                when (state.dataSaver.decide(MeteredFeature.DOWNLOADS)) {
                    MeteredDecision.ALLOWED -> OcrPacks.start(filesDir, pack)
                    MeteredDecision.BLOCKED -> meteredBlocked = true
                    MeteredDecision.ASK ->
                        if (meteredAsked) OcrPacks.start(filesDir, pack) else meteredAsked = true
                }
            }
        }
    }
}

private val WHITESPACE = Regex("\\s+")

/** The longest side a clip's picture is read at: past it, more pixels only slow the read. */
private const val CLIP_OCR_MAX_SIDE = 4096

/**
 * A clip's picture (#371), halved until its longest side fits
 * [CLIP_OCR_MAX_SIDE]. Throws when the file is gone or is not a picture.
 */
private fun decodeClipImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > CLIP_OCR_MAX_SIDE) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalArgumentException("not a picture: ${file.name}")
}

private const val OCR_LOG_TAG = "WMKB-OCR"

/**
 * The online readers' text as the panel's lines of words (#469, #727): the
 * first of [readers] that reads it. The photo is scaled to
 * [ONLINE_OCR_MAX_SIDE] first: enough for small print, and a full-sensor
 * picture would only cost upload time and the model's tokens.
 */
private fun readOnline(readers: List<ResolvedProvider>, bitmap: Bitmap): List<List<OcrWord>> {
    if (readers.isEmpty()) throw OnlineOcr.NoReaderException()
    val scale = ONLINE_OCR_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else {
        bitmap
    }
    val jpeg = java.io.ByteArrayOutputStream().use { out ->
        sized.compress(Bitmap.CompressFormat.JPEG, ONLINE_OCR_JPEG_QUALITY, out)
        out.toByteArray()
    }
    if (sized !== bitmap) sized.recycle()
    val text = OnlineOcr.read(readers, jpeg)
    var id = 0
    return text.lines()
        .map { line -> line.split(WHITESPACE).filter { it.isNotEmpty() } }
        .filter { it.isNotEmpty() }
        .map { words -> words.map { OcrWord(id++, it) } }
}

private const val ONLINE_OCR_MAX_SIDE = 1600
private const val ONLINE_OCR_JPEG_QUALITY = 85

/**
 * Tesseract's text as the panel's lines of words. Throws
 * [TesseractException] when the read failed rather than found nothing.
 */
private suspend fun readWithTesseract(filesDir: File, pack: String, bitmap: Bitmap): List<List<OcrWord>> =
    wordsOf(TesseractOcr.recognize(filesDir, pack, bitmap))

/** Lines of space-separated words, as Tesseract and Paddle read them, as the panel's lines of words. */
private fun wordsOf(text: String): List<List<OcrWord>> {
    var id = 0
    return text.lines()
        .map { line -> line.split(WHITESPACE).filter { it.isNotEmpty() } }
        .filter { it.isNotEmpty() }
        .map { words -> words.map { OcrWord(id++, it) } }
}

/**
 * The recognized words as chips, grouped by line. With [autoSelect] on
 * (the default setting) everything starts selected and tapping a word
 * toggles it off, so copying "only that one number" is a couple of taps
 * instead of a fiddly text selection; with it off the words start blank
 * and get picked one by one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OcrResultView(
    state: KeyboardUiState,
    result: OcrResult,
    autoSelect: Boolean,
    onInsert: (String) -> Unit,
    onTranslate: (String) -> Unit,
    onRescan: () -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    var selected by remember(result, autoSelect) {
        mutableStateOf(
            if (autoSelect) result.lines.flatten().map { it.id }.toSet() else emptySet()
        )
    }

    fun selectedText(): String = result.lines
        .map { line -> line.filter { it.id in selected }.joinToString(" ") { it.text } }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    fun copySelected() {
        val text = selectedText()
        if (text.isNotEmpty()) {
            copyPlainText(context, text)
            Toast.makeText(context, R.string.ime_scanner_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    // The ring exists only on this frozen result — the viewfinder publishes
    // nothing. Fixed ACTIONS slots (Scan again, select-all toggle, Copy,
    // Translate, Insert; the last four no-op with no words), and the word
    // chips as RESULTS in reading order.
    val allWords = result.lines.flatten()
    PanelFocusTarget(
        panel = PanelMode.OCR,
        region = FocusRegion.ACTIONS,
        count = if (result.wordCount > 0) 5 else 1,
        columns = 5,
    ) { index ->
        when (index) {
            0 -> { feedback(); onRescan() }
            1 -> {
                feedback()
                selected = if (selected.size == result.wordCount) emptySet()
                else allWords.map { it.id }.toSet()
            }
            2 -> { feedback(); copySelected() }
            3 -> selectedText().takeIf { it.isNotEmpty() }?.let(onTranslate)
            4 -> selectedText().takeIf { it.isNotEmpty() }?.let(onInsert)
        }
    }
    PanelFocusTarget(
        panel = PanelMode.OCR,
        region = FocusRegion.RESULTS,
        count = allWords.size,
        columns = allWords.size.coerceAtLeast(1),
    ) { index ->
        allWords.getOrNull(index)?.let { word ->
            feedback()
            selected = if (word.id in selected) selected - word.id else selected + word.id
        }
    }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedWord = state.focusedIndex(FocusRegion.RESULTS)
        ?.let { allWords.getOrNull(it)?.id }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraChipButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.ime_scanner_ocr_close_desc),
                active = false,
            ) {
                feedback()
                onClose()
            }
            CameraChipButton(
                icon = Icons.Outlined.Refresh,
                description = stringResource(R.string.ime_scanner_scan_again_action),
                active = false,
                focused = focusedAction == 0,
            ) {
                feedback()
                onRescan()
            }
            Text(
                if (result.errorRes != null) {
                    stringResource(R.string.ime_scanner_ocr_failed_label)
                } else if (result.wordCount == 0) {
                    stringResource(R.string.ime_scanner_ocr_no_text_label)
                } else {
                    pluralStringResource(
                        R.plurals.ime_scanner_ocr_word_count,
                        result.wordCount,
                        selected.size,
                        result.wordCount,
                    )
                },
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (result.wordCount > 0) {
                val allSelected = selected.size == result.wordCount
                CameraChipButton(
                    icon = if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                    description = if (allSelected) {
                        stringResource(R.string.ime_scanner_ocr_deselect_all_desc)
                    } else {
                        stringResource(CommonR.string.common_select_all)
                    },
                    active = false,
                    focused = focusedAction == 1,
                ) {
                    feedback()
                    selected =
                        if (allSelected) emptySet()
                        else allWords.map { it.id }.toSet()
                }
            }
        }

        if (result.wordCount == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(result.errorRes ?: R.string.ime_scanner_ocr_empty),
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (line in result.lines) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (word in line) {
                            val on = word.id in selected
                            Text(
                                word.text,
                                color = if (on) kb.toolCircleActiveIcon else kb.suggestionText,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) kb.toolCircleActive else kb.chip)
                                    .focusRing(word.id == focusedWord)
                                    .pointerInput(word.id) {
                                        detectTapGestures {
                                            feedback()
                                            selected = if (on) selected - word.id else selected + word.id
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // Each pill takes no more than its share of the row, so a large
            // font shortens their labels rather than squeezing the last one
            // to a sliver (#945).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                CaptureActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(CommonR.string.common_copy),
                    accent = false,
                    focused = focusedAction == 2,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    feedback()
                    copySelected()
                }
                // The picked words in the Translate tool (#807), which then
                // reads them in the language it translates into.
                CaptureActionButton(
                    icon = Icons.Outlined.Translate,
                    label = stringResource(R.string.ime_scanner_translate_action),
                    accent = false,
                    focused = focusedAction == 3,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    val text = selectedText()
                    if (text.isNotEmpty()) onTranslate(text)
                }
                CaptureActionButton(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    label = stringResource(R.string.ime_scanner_insert_action),
                    accent = true,
                    focused = focusedAction == 4,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    val text = selectedText()
                    if (text.isNotEmpty()) onInsert(text)
                }
            }
        }
    }
}

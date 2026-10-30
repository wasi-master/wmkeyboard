package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.HandwritingStatus
import com.wasimaster.wmkeyboard.ime.HandwritingUi
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The handwriting panel's writing area (Gboard style), the
 * [PanelFieldKind.HANDWRITING_CANVAS] cell of its panel layout (#568): the
 * keys around it — delete, space, enter, back to the letters — are the
 * layout's own and go through the panel grid like any panel key. Completed
 * strokes come from the service via [KeyboardUiState.handwriting]; only the
 * stroke currently under the finger/stylus is local, so the canvas stays
 * smooth while recognition and clearing remain the service's call.
 *
 * S Pen / stylus support: stylus points are captured like finger points
 * (Compose reports them as pointer events). Palm rejection: once a stylus
 * has drawn recently, finger touches are ignored for a short window — and
 * entirely, when the stylus-only setting is on.
 */
@Composable
internal fun HandwritingCanvasField(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
    onUndoStroke: () -> Unit,
    onDownloadModel: () -> Unit,
    onLayoutSelect: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val hw = state.handwriting
    val feedback = LocalKeyPressFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp),
    ) {
        HandwritingStatusBody(state, onStroke, onDownloadModel)

        // Language chip: shows the active model, tap cycles through the
        // handwriting languages of the enabled modes.
        // Only languages a recogniser can actually read belong on the chip;
        // cycling onto one with no model would strand the panel on a
        // download that can never complete.
        val languages = state.settings.enabledLanguages
            .ifEmpty { listOf(LanguageRegistry.byId("en")) }
            .filter { HandwritingModels.tagFor(it) != null }
        val distinctTags = languages.mapNotNull { HandwritingModels.tagFor(it) }.distinct()
        if (distinctTags.size > 1) {
            Text(
                text = HandwritingModels.shortLabel(hw.languageTag),
                color = kb.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(kb.chipShape())
                    .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                    .background(kb.chip)
                    .chipBorder(kb, kb.chipShape())
                    .clickable {
                        feedback()
                        val next = distinctTags[
                            (distinctTags.indexOf(hw.languageTag) + 1).mod(distinctTags.size),
                        ]
                        val targetLang = languages.first { HandwritingModels.tagFor(it) == next }
                        val layoutId = targetLang.layoutIds.firstOrNull { it in state.settings.enabledLayoutIds }
                            ?: targetLang.layoutIds.firstOrNull()
                        if (layoutId != null) onLayoutSelect(layoutId)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Undo the last stroke while ink is still on the canvas.
        UndoStrokeButton(hw, onUndoStroke, Modifier.align(Alignment.BottomStart))

        // Blow the canvas up over the whole app (issue #386).
        if (hw.status == HandwritingStatus.READY) {
            val fullScreen = LocalHandwritingFullScreen.current
            Icon(
                Icons.Outlined.OpenInFull,
                contentDescription = stringResource(R.string.ime_handwriting_full_screen_desc),
                tint = kb.toolbarIcon,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .clickable {
                        feedback()
                        fullScreen.onToggle()
                    }
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
    }
}

/**
 * The writing area of a handwriting layout (issue #557): the panel's canvas
 * without its action rail, because the layout's own bottom row carries
 * delete, space and enter. Ink goes through the keyboard-handwriting path in
 * the service, which recognises it in the layout's language. No language
 * chip either: the globe key on that row is how the language changes.
 */
@Composable
internal fun HandwritingLayoutCanvas(
    state: KeyboardUiState,
    modifier: Modifier,
    onStroke: (HwStroke, IntSize) -> Unit,
    onUndoStroke: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        HandwritingStatusBody(state, onStroke, onDownloadModel)
        UndoStrokeButton(state.handwriting, onUndoStroke, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * What fills the writing area for the model's current state: the canvas once
 * it is ready, otherwise the check, the download or the offer to download.
 * Shared by the panel and the handwriting layouts.
 */
@Composable
internal fun HandwritingStatusBody(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
    onDownloadModel: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val hw = state.handwriting
    val feedback = LocalKeyPressFeedback.current
    val context = LocalContext.current
    when (hw.status) {
        HandwritingStatus.READY -> WritingCanvas(state, onStroke)
        HandwritingStatus.CHECKING ->
            StatusMessage(stringResource(R.string.ime_handwriting_checking_progress))
        HandwritingStatus.UNSUPPORTED ->
            StatusMessage(stringResource(R.string.ime_handwriting_unsupported_info))
        HandwritingStatus.DOWNLOADING -> DownloadingMessage(hw, onDownloadModel)
        HandwritingStatus.NEED_MODEL, HandwritingStatus.ERROR -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val language = HandwritingModels.displayName(hw.languageTag)
            Text(
                if (!InternetPermission.granted) stringResource(R.string.ime_handwriting_offline_build_body) else hw.errorMessage ?: if (hw.modelBytes > 0L) {
                    stringResource(
                        R.string.ime_handwriting_need_model_sized_body,
                        language,
                        Formatter.formatShortFileSize(context, hw.modelBytes),
                    )
                } else {
                    stringResource(R.string.ime_handwriting_need_model_body, language)
                },
                color = kb.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (InternetPermission.granted) Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                    .background(kb.toolCircleActive)
                    .clickable {
                        feedback()
                        onDownloadModel()
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = kb.toolCircleActiveIcon,
                )
                Text(
                    if (hw.status == HandwritingStatus.ERROR) {
                        stringResource(CommonR.string.common_retry)
                    } else {
                        stringResource(CommonR.string.common_download)
                    },
                    color = kb.toolCircleActiveIcon,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Undo the last stroke, while ink is still on the canvas. */
@Composable
internal fun UndoStrokeButton(hw: HandwritingUi, onUndoStroke: () -> Unit, modifier: Modifier) {
    if (hw.strokes.isEmpty() || hw.status != HandwritingStatus.READY) return
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    IconHint(stringResource(R.string.ime_handwriting_undo_stroke_desc)) {
        Icon(
            Icons.AutoMirrored.Outlined.Undo,
            contentDescription = stringResource(R.string.ime_handwriting_undo_stroke_desc),
            tint = kb.toolbarIcon,
            modifier = modifier
                .padding(4.dp)
                .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                .clickable {
                    feedback()
                    onUndoStroke()
                }
                .padding(8.dp)
                .size(20.dp),
        )
    }
}

/**
 * The ink canvas. Captures one stroke per gesture, following only the
 * first pointer that went down (any extra pointers — a resting palm — are
 * ignored). Stroke timestamps ride along for the recognizer's velocity
 * features.
 */
/** How long with nothing arriving before the panel admits it is stuck. */
private const val STALL_HINT_MS = 20_000L

/**
 * The panel while a recognition model is being fetched. ML Kit's API reports
 * no progress at all, so both numbers here are worked out rather than read:
 * the megabytes are measured as they land on disk, and the total comes from
 * the size manifest ML Kit ships as an asset. That makes the ring determinate
 * for every language those figures cover, and an honest spinner for the rest.
 * The same button that started the download calls it off, so the panel is
 * never a dead end.
 */
@Composable
private fun DownloadingMessage(hw: HandwritingUi, onCancel: () -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    val progress = hw.download
    val bytes = progress?.bytes ?: 0L
    val stalled = (progress?.stalledForMs ?: 0L) >= STALL_HINT_MS
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fraction = progress?.fraction
        if (fraction != null && bytes > 0L) {
            CircularProgressIndicator(progress = { fraction }, color = kb.accent)
        } else {
            CircularProgressIndicator(color = kb.accent)
        }
        Text(
            stringResource(
                // Before the first byte there is nothing to report but the
                // wait itself, and calling that "downloading" is what makes a
                // slow start look like a hang.
                if (bytes > 0L) {
                    R.string.ime_handwriting_downloading_progress
                } else {
                    R.string.ime_handwriting_preparing_progress
                },
                HandwritingModels.displayName(hw.languageTag),
            ),
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
        )
        if (bytes > 0L) {
            val total = progress?.totalBytes ?: 0L
            Text(
                if (total > 0L) {
                    stringResource(
                        R.string.ime_handwriting_download_of_total_progress,
                        Formatter.formatShortFileSize(context, bytes),
                        Formatter.formatShortFileSize(context, total),
                        Formatter.formatShortFileSize(context, progress?.bytesPerSecond ?: 0L),
                    )
                } else {
                    stringResource(
                        R.string.ime_handwriting_download_size_progress,
                        Formatter.formatShortFileSize(context, bytes),
                        Formatter.formatShortFileSize(context, progress?.bytesPerSecond ?: 0L),
                    )
                },
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (stalled) {
            Text(
                stringResource(R.string.ime_handwriting_download_stalled_info),
                color = kb.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            )
        }
        Text(
            stringResource(CommonR.string.common_cancel),
            color = kb.secondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
                .clickable {
                    feedback()
                    onCancel()
                }
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun WritingCanvas(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
    /** The "write here" line; off over an app, where it would sit on the app's own text. */
    showHint: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val hw = state.handwriting
    val stylusOnly = state.settings.handwritingStylusOnly

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeStroke by remember { mutableStateOf<List<HwPoint>>(emptyList()) }
    // Palm rejection: after stylus ink, finger touches are ignored briefly
    // so a knuckle or palm resting on the screen never draws.
    var lastStylusTime by remember { mutableLongStateOf(0L) }
    val dissolve = rememberInkDissolve(hw)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(stylusOnly) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val isStylus = down.type == PointerType.Stylus
                    if (isStylus) lastStylusTime = down.uptimeMillis
                    val rejectFinger = !isStylus &&
                        (stylusOnly || down.uptimeMillis - lastStylusTime < STYLUS_PRIORITY_MS)
                    if (rejectFinger) return@awaitEachGesture
                    down.consume()
                    val points = ArrayList<HwPoint>()
                    points.add(HwPoint(down.position.x, down.position.y, down.uptimeMillis))
                    activeStroke = points.toList()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (isStylus) lastStylusTime = change.uptimeMillis
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        // Clamp to the canvas so a stroke sliding off the
                        // edge doesn't feed wild coordinates to the model.
                        val x = change.position.x.coerceIn(0f, size.width.toFloat())
                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                        points.add(HwPoint(x, y, change.uptimeMillis))
                        activeStroke = points.toList()
                        change.consume()
                    }
                    if (points.size == 1) {
                        // A tap is still ink — a period, a dot on an i. Give
                        // the recognizer a two-point stroke so it registers.
                        points.add(points.first().copy(t = points.first().t + 1))
                    }
                    onStroke(HwStroke(points.toList()), canvasSize)
                    activeStroke = emptyList()
                }
            },
    ) {
        if (showHint && hw.strokes.isEmpty() && activeStroke.isEmpty() && !hw.recognizing) {
            Text(
                stringResource(R.string.ime_handwriting_canvas_hint),
                color = kb.secondaryText.copy(alpha = 0.45f),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeStyle = Stroke(
                width = 4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val inkColor = if (hw.recognizing) kb.accent.copy(alpha = 0.45f) else kb.accent
            drawDissolvingInk(dissolve, kb.accent.copy(alpha = 0.45f), strokeStyle)
            for (stroke in hw.strokes) {
                drawPath(pathOf(stroke.points), color = inkColor, style = strokeStyle)
            }
            if (activeStroke.size > 1) {
                drawPath(pathOf(activeStroke), color = kb.accent, style = strokeStyle)
            }
        }
    }
}

internal fun pathOf(points: List<HwPoint>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (point in points.drop(1)) path.lineTo(point.x, point.y)
    return path
}

/** Finger strokes are ignored for this long after the stylus last drew. */
private const val STYLUS_PRIORITY_MS = 800L

@Composable
private fun StatusMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** One key on the panel's right-hand action rail (same look as the numpad keys). */
@Composable
internal fun HwRailKey(
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    repeatable: Boolean = false,
    onAction: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val scope = rememberCoroutineScope()
    val shape = kb.keyShape()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clip(shape)
            // Glass like the board's own keys, and only where those have faces.
            .then(if (kb.keysHaveFaces) Modifier.frostGlass(kb, shape, FrostSurface.MODIFIER_KEYS) else Modifier)
            .background(kb.keyFace(kb.modifierKey), shape)
            .panelKeyBorder(kb, shape)
            .pointerInput(repeatable) {
                detectTapGestures(
                    onPress = {
                        onAction()
                        var repeat: Job? = null
                        if (repeatable) {
                            repeat = scope.launch {
                                delay(400)
                                while (true) {
                                    onAction()
                                    delay(120)
                                }
                            }
                        }
                        tryAwaitRelease()
                        repeat?.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = kb.modifierKeyText,
        )
    }
}

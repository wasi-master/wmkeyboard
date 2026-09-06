package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SpaceBar
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
import com.wasimaster.wmkeyboard.ime.HandwritingStatus
import com.wasimaster.wmkeyboard.ime.HandwritingUi
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handwriting input panel (Gboard style): a full-width writing canvas with
 * a narrow action rail on the right (backspace, space, enter, back to
 * keys). Completed strokes come from the service via
 * [KeyboardUiState.handwriting]; only the stroke currently under the
 * finger/stylus is local, so the canvas stays smooth while recognition
 * and clearing remain the service's call.
 *
 * S Pen / stylus support: stylus points are captured like finger points
 * (Compose reports them as pointer events). Palm rejection: once a stylus
 * has drawn recently, finger touches are ignored for a short window — and
 * entirely, when the stylus-only setting is on.
 */
@Composable
internal fun HandwritingPanel(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
    onUndoStroke: () -> Unit,
    onDownloadModel: () -> Unit,
    onKey: (Key) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val height = keyRowsHeight(state)
    val hw = state.handwriting
    val feedback = LocalKeyPressFeedback.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(2.dp),
        ) {
            when (hw.status) {
                HandwritingStatus.READY -> WritingCanvas(state, onStroke)
                HandwritingStatus.CHECKING ->
                    StatusMessage(stringResource(R.string.ime_handwriting_checking_progress))
                HandwritingStatus.DOWNLOADING -> DownloadingMessage(hw, onDownloadModel)
                HandwritingStatus.NEED_MODEL, HandwritingStatus.ERROR -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val language = HandwritingModels.displayName(hw.languageTag)
                    Text(
                        hw.errorMessage ?: if (hw.modelBytes > 0L) {
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
                    Row(
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

            // Language chip: shows the active model, tap cycles through the
            // handwriting languages of the enabled modes.
            // Only languages ML Kit can actually recognize belong on the chip;
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
            if (hw.strokes.isNotEmpty() && hw.status == HandwritingStatus.READY) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = stringResource(R.string.ime_handwriting_undo_stroke_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
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

        // Action rail, sized like a key column.
        Column(modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()) {
            HwRailKey(
                description = stringResource(CommonR.string.common_delete),
                icon = Icons.AutoMirrored.Outlined.Backspace,
                repeatable = true,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onKey(Key("⌫", action = KeyAction.Delete))
            }
            HwRailKey(
                description = stringResource(R.string.ime_rail_space_desc),
                icon = Icons.Outlined.SpaceBar,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onKey(Key(" ", action = KeyAction.Space))
            }
            HwRailKey(
                description = stringResource(R.string.ime_rail_enter_desc),
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onKey(Key("⏎", action = KeyAction.Enter))
            }
            HwRailKey(
                description = stringResource(R.string.ime_rail_back_desc),
                icon = Icons.Outlined.Keyboard,
                modifier = Modifier.weight(1f),
            ) {
                feedback()
                onClose()
            }
        }
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
private fun WritingCanvas(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
) {
    val kb = LocalKbTheme.current
    val hw = state.handwriting
    val stylusOnly = state.settings.handwritingStylusOnly

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeStroke by remember { mutableStateOf<List<HwPoint>>(emptyList()) }
    // Palm rejection: after stylus ink, finger touches are ignored briefly
    // so a knuckle or palm resting on the screen never draws.
    var lastStylusTime by remember { mutableLongStateOf(0L) }

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
        if (hw.strokes.isEmpty() && activeStroke.isEmpty() && !hw.recognizing) {
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
            for (stroke in hw.strokes) {
                drawPath(pathOf(stroke.points), color = inkColor, style = strokeStyle)
            }
            if (activeStroke.size > 1) {
                drawPath(pathOf(activeStroke), color = kb.accent, style = strokeStyle)
            }
        }
    }
}

private fun pathOf(points: List<HwPoint>): Path {
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
private fun HwRailKey(
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
            .background(kb.modifierKey, shape)
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

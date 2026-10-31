package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloseFullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HandwritingFullScreenMode
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.HandwritingStatus
import com.wasimaster.wmkeyboard.ime.HandwritingUi
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlin.math.roundToInt

/**
 * What the full-screen handwriting surface hands back to the service. A
 * composition local rather than three more parameters, because the keyboard
 * screen's argument list is already at the JVM's method-size ceiling.
 */
class HandwritingFullScreenControls(
    /** Expand from the panel, or shrink back to it. */
    val onToggle: () -> Unit = {},
    /** Pen-button mode: catch touches as ink, or let them through to the app. */
    val onInkToggle: () -> Unit = {},
    /** The compact bar's rectangle in window pixels, for the touchable region. */
    val onBarBounds: (IntRect) -> Unit = {},
)

val LocalHandwritingFullScreen = staticCompositionLocalOf { HandwritingFullScreenControls() }

/**
 * The handwriting panel is blown up over the whole app (issue #386). The
 * service's insets check mirrors this predicate — keep the two in step.
 */
internal fun handwritingFullScreen(state: KeyboardUiState): Boolean =
    state.handwriting.fullScreen && state.panel == PanelMode.HANDWRITING

/**
 * Pen-button mode is the only one below Android 14: the stylus mode rides on
 * the platform's stylus handwriting, which earlier releases don't have.
 */
internal fun handwritingFullScreenManual(state: KeyboardUiState): Boolean =
    state.settings.handwritingFullScreenMode == HandwritingFullScreenMode.MANUAL ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Full-screen handwriting, Samsung Keyboard style (issue #386): the canvas
 * leaves the keyboard and covers the whole window, see-through so the app
 * shows under the ink, and the keyboard shrinks to a small bar at the bottom
 * with the recogniser's candidates, delete, space and enter.
 *
 * The window itself is the switch between ink and app: the service hands
 * the system a touchable region of the whole window while the canvas is
 * catching ink, and of the bar alone otherwise, so every other touch falls
 * through to the app. In stylus mode the canvas here never catches anything —
 * Android's own stylus handwriting window does, and lets fingers through.
 */
@Composable
internal fun HandwritingFullScreen(
    state: KeyboardUiState,
    onStroke: (HwStroke, IntSize) -> Unit,
    onUndoStroke: () -> Unit,
    onDownloadModel: () -> Unit,
    onKey: (Key) -> Unit,
    onSuggestion: (String) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // The candidates move with every stroke: read live (see [withLiveTyping]).
    val words = withLiveTyping(state).suggestions
    val hw = state.handwriting
    val controls = LocalHandwritingFullScreen.current
    val feedback = LocalKeyPressFeedback.current
    val manual = handwritingFullScreenManual(state)
    val ready = hw.status == HandwritingStatus.READY

    Box(modifier = Modifier.fillMaxSize()) {
        if (ready && manual) {
            if (hw.fullScreenInk) {
                WritingCanvas(state, onStroke, showHint = false)
            } else {
                // Ink still waiting for recognition stays where it was drawn
                // after the pen is put down.
                PassiveInk(hw, state.settings.handwritingInk)
            }
        }

        val barShape = RoundedCornerShape(18.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val position = coords.positionInWindow()
                    controls.onBarBounds(
                        IntRect(
                            offset = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                            size = coords.size,
                        ),
                    )
                }
                .shadow(10.dp, barShape)
                .clip(barShape),
        ) {
            BoardBackground(kb)
            Column(modifier = Modifier.padding(4.dp)) {
                if (!ready) {
                    // The model check, the download or the offer to download:
                    // nothing can be written until it is through.
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                        HandwritingStatusBody(state, onStroke, onDownloadModel)
                    }
                } else if (!manual && hw.strokes.isEmpty() && words.isEmpty()) {
                    Text(
                        stringResource(R.string.ime_handwriting_stylus_hint),
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (words.isNotEmpty()) {
                    CandidateRow(words, onSuggestion)
                }
                Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    val key = Modifier.weight(1f).fillMaxHeight()
                    if (hw.strokes.isNotEmpty() && ready) {
                        HwRailKey(
                            description = stringResource(R.string.ime_handwriting_undo_stroke_desc),
                            icon = Icons.AutoMirrored.Outlined.Undo,
                            modifier = key,
                        ) {
                            feedback()
                            onUndoStroke()
                        }
                    }
                    if (manual && ready) {
                        HwRailKey(
                            description = stringResource(
                                if (hw.fullScreenInk) {
                                    R.string.ime_handwriting_ink_on_desc
                                } else {
                                    R.string.ime_handwriting_ink_off_desc
                                },
                            ),
                            icon = if (hw.fullScreenInk) Icons.Outlined.Draw else Icons.Outlined.TouchApp,
                            modifier = key,
                        ) {
                            feedback()
                            controls.onInkToggle()
                        }
                    }
                    HwRailKey(
                        description = stringResource(CommonR.string.common_delete),
                        icon = Icons.AutoMirrored.Outlined.Backspace,
                        repeatable = true,
                        modifier = key,
                    ) {
                        feedback()
                        onKey(Key("⌫", action = KeyAction.Delete))
                    }
                    HwRailKey(
                        description = stringResource(R.string.ime_rail_space_desc),
                        icon = Icons.Outlined.SpaceBar,
                        modifier = key,
                    ) {
                        feedback()
                        onKey(Key(" ", action = KeyAction.Space))
                    }
                    HwRailKey(
                        description = stringResource(R.string.ime_rail_enter_desc),
                        icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                        modifier = key,
                    ) {
                        feedback()
                        onKey(Key("⏎", action = KeyAction.Enter))
                    }
                    HwRailKey(
                        description = stringResource(R.string.ime_handwriting_exit_full_screen_desc),
                        icon = Icons.Outlined.CloseFullscreen,
                        modifier = key,
                    ) {
                        feedback()
                        controls.onToggle()
                    }
                    HwRailKey(
                        description = stringResource(R.string.ime_rail_back_desc),
                        icon = Icons.Outlined.Keyboard,
                        modifier = key,
                    ) {
                        feedback()
                        onClose()
                    }
                }
            }
        }
    }
}

/** The recogniser's readings of the last word; a tap swaps it in. */
@Composable
private fun CandidateRow(candidates: List<String>, onPick: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (word in candidates.take(MAX_BAR_CANDIDATES)) {
            Text(
                word,
                color = kb.suggestionText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(kb.chipShape())
                    .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                    .background(kb.chip)
                    .chipBorder(kb, kb.chipShape())
                    .clickable {
                        feedback()
                        onPick(word)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Committed-but-unrecognised ink, drawn without catching any touch. */
@Composable
private fun PassiveInk(hw: HandwritingUi, pen: HandwritingInkSettings) {
    // Recognised ink dissolves here too, as on the panel's canvas.
    val dissolve = rememberInkDissolve(hw)
    if (hw.strokes.isEmpty() && dissolve.progress == null) return
    val kb = LocalKbTheme.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        val style = Stroke(width = pen.penWidthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val penColor = pen.penColor?.let { Color(it) } ?: kb.accent
        val ink = if (hw.recognizing) penColor.copy(alpha = 0.45f) else penColor
        drawDissolvingInk(dissolve, penColor.copy(alpha = 0.45f), style, pen.fade)
        for (stroke in hw.strokes) drawPath(pathOf(stroke.points), color = ink, style = style)
    }
}

private const val MAX_BAR_CANDIDATES = 6

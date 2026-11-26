package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.notes.NoteAttachments
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The keyboard's note body as rich text (#1586): the draft the keys type into
 * is the note's Markdown itself, drawn by [NoteRichStyle] with its markers
 * hidden, so bold shows bold, a list shows bullets, a task its box, a divider
 * its rule and a row of pictures the pictures. Offsets are the source's, so
 * the shared capture caret, the selection, the handles and the taps all
 * work as they do in [ClipEditText], which this follows line for line.
 *
 * A tap on a task's box ticks it ([onTaskToggle], with the line's index);
 * anywhere else a tap places the caret, as in any field.
 */
@Composable
internal fun NoteRichField(
    text: String,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    dimColor: Color,
    accent: Color,
    codeBackground: Color,
    onTaskToggle: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val handle = LocalCaptureCaret.current
    val caret = handle.at.coerceIn(0, text.length)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val fontSize = 14.sp
    val overlay = LocalSelectionOverlay.current
    val owner = remember { SelectionAnchor() }
    val selecting = handle.hasSelection && handle.selectionEnd <= text.length
    val latestHandle by rememberUpdatedState(handle)
    val latestText by rememberUpdatedState(text)
    val latestToggle by rememberUpdatedState(onTaskToggle)
    val haptic = LocalHapticFeedback.current
    var caretBar by remember(text) { mutableStateOf(false) }
    val setCaretBar by rememberUpdatedState<(Boolean) -> Unit>({ caretBar = it })
    val toApp = handle.toApp
    val switchShown = handle.onFocusSwitch != null
    BoxWithConstraints(modifier = modifier) {
        val fieldHeight = minHeight
        val switchRoom = if (switchShown) LocalKbTheme.current.fieldButtonDp.dp + 4.dp else 0.dp
        val widthSp = with(density) { ((maxWidth - switchRoom) * 0.98f).toSp() }
        val styled = remember(text, textColor, dimColor, accent, codeBackground) {
            NoteRichStyle.annotate(text, fontSize, textColor, dimColor, accent, codeBackground) { line -> "$IMAGE_ID${line.start}" }
        }
        // A row of pictures stands in for its line's text, as wide as the field.
        val pictures = remember(styled, widthSp) {
            styled.read.lines.filter { it.kind == NoteMarkup.LineKind.IMAGES }.associate { line ->
                val urls = NoteMarkup.images(text.substring(line.start, line.end)).map { it.second }
                val height = if (urls.size > 1) ROW_HEIGHT_SP else SINGLE_HEIGHT_SP
                "$IMAGE_ID${line.start}" to InlineTextContent(Placeholder(widthSp, height.sp, PlaceholderVerticalAlign.TextCenter)) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (url in urls) {
                            AsyncImage(
                                model = NoteAttachments.file(context.filesDir, url) ?: url,
                                imageLoader = rememberMediaImageLoader(),
                                contentDescription = null,
                                contentScale = if (urls.size > 1) ContentScale.Crop else ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)),
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .then(if (switchShown) Modifier.padding(end = switchRoom) else Modifier)
                .then(if (toApp) Modifier.alpha(FieldToAppAlpha) else Modifier)
                .verticalScroll(scroll),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = fieldHeight)
                    .fieldTextGestures(
                        key = Unit,
                        text = { latestText },
                        layout = { layout },
                        handle = { latestHandle },
                        setCaretBar = { setCaretBar(it) },
                        onHold = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        belowText = true,
                    ),
            ) {
                Text(
                    text = styled.text,
                    color = textColor,
                    fontSize = fontSize,
                    inlineContent = pictures,
                    onTextLayout = {
                        layout = it
                        overlay?.moved(owner)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            owner.coordinates = it
                            overlay?.moved(owner)
                        }
                        .selectionHighlight(
                            text,
                            if (selecting) handle.selectionStart else 0,
                            if (selecting) handle.selectionEnd else 0,
                            LocalKbTheme.current.accent.copy(alpha = HighlightAlpha),
                        ) { layout }
                        .drawBehind {
                            val l = layout?.takeIf { it.layoutInput.text.text == text } ?: return@drawBehind
                            with(NoteRichStyle) { drawDecorations(styled, l, textColor, dimColor, accent) }
                        }
                        // A task's box: drawn here, so the tap on it is taken here,
                        // before the field's own gestures see it.
                        .pointerInput(styled) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val l = layout?.takeIf { it.layoutInput.text.text == latestText } ?: return@awaitEachGesture
                                val line = NoteRichStyle.taskAt(styled, l, down.position) ?: return@awaitEachGesture
                                down.consume()
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                up.consume()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestToggle(line)
                            }
                        },
                )
                PublishFieldSelection(
                    owner = owner,
                    text = text,
                    active = !toApp,
                    handle = handle,
                    coordinates = { owner.coordinates },
                    layout = { layout?.takeIf { it.layoutInput.text.text == text } },
                    caretBar = caretBar,
                    onCaretBarDismiss = { caretBar = false },
                )
                if (text.isEmpty()) {
                    Text(
                        text = if (toApp) androidx.compose.ui.res.stringResource(R.string.ime_capture_focus_app_hint) else placeholder,
                        color = placeholderColor,
                        fontSize = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                val result = layout?.takeIf { it.layoutInput.text.text == text }
                if (result != null && !selecting && !toApp) {
                    val rect = result.getCursorRect(caret.coerceIn(0, result.layoutInput.text.length))
                    Box(Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }) {
                        RichCaret(color = accent, height = with(density) { rect.height.toDp() }, restartKey = caret to text)
                    }
                    LaunchedEffect(rect.top, rect.bottom, scroll.viewportSize, scroll.maxValue) {
                        val viewport = scroll.viewportSize
                        if (viewport <= 0) return@LaunchedEffect
                        val margin = with(density) { 4.dp.toPx() }
                        val want = when {
                            rect.top - margin < scroll.value -> rect.top - margin
                            rect.bottom + margin > scroll.value + viewport -> rect.bottom + margin - viewport
                            else -> return@LaunchedEffect
                        }
                        scroll.scrollTo(want.roundToInt().coerceIn(0, scroll.maxValue))
                    }
                }
            }
        }
        FieldFocusSwitch(handle, Modifier.align(Alignment.TopEnd))
    }
}

/** The blinking bar; solid under reduce motion. */
@Composable
private fun RichCaret(color: Color, height: androidx.compose.ui.unit.Dp, restartKey: Any?) {
    val reduceMotion = LocalKbTheme.current.reduceMotion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, reduceMotion) {
        visible = true
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(CARET_BLINK_MS)
            visible = !visible
        }
    }
    Box(
        Modifier
            .width(1.5.dp)
            .height(height)
            .drawBehind { if (visible) drawRect(color) },
    )
}

private const val IMAGE_ID = "note-picture-"
private const val ROW_HEIGHT_SP = 96
private const val SINGLE_HEIGHT_SP = 132
private const val CARET_BLINK_MS = 500L

/**
 * The body drawn the same way while the title or a tag has the keys: no
 * caret, and a tap hands the body the keys with the caret under the finger,
 * or ticks a task's box.
 */
@Composable
internal fun NoteRichStatic(
    text: String,
    textColor: Color,
    dimColor: Color,
    accent: Color,
    codeBackground: Color,
    onTap: (Int?) -> Unit,
    onTaskToggle: (Int) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val styled = remember(text, textColor, dimColor, accent, codeBackground) {
        NoteRichStyle.annotate(text, 14.sp, textColor, dimColor, accent, codeBackground)
    }
    val latestTap by rememberUpdatedState(onTap)
    val latestToggle by rememberUpdatedState(onTaskToggle)
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .pointerInput(styled) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    val l = layout?.takeIf { it.layoutInput.text.text == text }
                    val task = l?.let { NoteRichStyle.taskAt(styled, it, down.position) }
                    if (task != null) latestToggle(task) else latestTap(l?.getOffsetForPosition(down.position))
                }
            },
    ) {
        Text(
            styled.text,
            color = textColor,
            fontSize = 14.sp,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val l = layout?.takeIf { it.layoutInput.text.text == text } ?: return@drawBehind
                    with(NoteRichStyle) { drawDecorations(styled, l, textColor, dimColor, accent) }
                },
        )
    }
}

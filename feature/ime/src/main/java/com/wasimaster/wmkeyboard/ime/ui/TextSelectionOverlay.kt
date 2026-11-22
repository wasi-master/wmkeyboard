package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.CaptureSelectionAction
import com.wasimaster.wmkeyboard.ime.CaretText
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Text selection on the keyboard (#352): a long press picks a word, two
 * handles stretch it, and a small bar offers what can be done with it.
 *
 * Why this is not a SelectionContainer or a BasicTextField. Compose draws
 * their handles as popup windows and their Copy/Paste bar as a platform
 * ActionMode. An IME has no activity for an ActionMode, and a popup window
 * over the keyboard is the untrusted-touch trap (see KeyPreviewOverlay's
 * history): on Android 12 and 13 it swallows the taps meant for the app. So
 * every piece here is drawn inside the keyboard's own window instead.
 * [SelectionOverlay] sits last in the keyboard body's box and draws the
 * handles and the bar for whichever text has a selection up, in that box's
 * own space; the text itself only draws its highlight and says where it is.
 *
 * Two kinds of text publish selections here. Read-only text in the tools
 * (a Wikipedia article, a dictionary entry) keeps its selection itself, and
 * a tap anywhere else lets it go. The keyboard's own fields keep theirs in
 * the service, next to the caret, so typing replaces it; their selection
 * only ends when the text or the caret says so.
 *
 * Every offset handed to a text layout is clamped to *that layout's* own
 * text length, and every layout is checked to be of the text in hand, the
 * rule #252 was written in blood for.
 */

/**
 * Where a tool's text came from, twice over: [framing] is how the Ask AI
 * message names it to the model, "the Wikipedia article "Cat"", and [label]
 * how the composer names it to the user, "Wikipedia: Cat".
 */
data class AskAiSource(val framing: String, val label: String)

/** One action on the selection bar. */
@Stable
internal class SelectionBarAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/** A piece of text with a selection up, as the overlay needs it to draw the handles and the bar. */
@Stable
internal class SelectionSession(
    /** Who published it; only the owner can take it down. */
    val owner: Any,
    /** The text's own box, or null before it is placed. */
    val coordinates: () -> LayoutCoordinates?,
    /** The text's layout, or null before one exists. */
    val layout: () -> TextLayoutResult?,
    val start: Int,
    val end: Int,
    val actions: List<SelectionBarAction>,
    /** A tap anywhere outside the text, its handles and the bar lets the selection go. */
    val dismissOnOutsideTap: Boolean,
    /** A handle moved: the new span, and whether it was the start that moved. */
    val onChange: (start: Int, end: Int, movedStart: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)

/** The one selection on the keyboard, and what the overlay last drew for it. */
@Stable
internal class SelectionOverlayState {
    var session by mutableStateOf<SelectionSession?>(null)
        private set

    /**
     * Bumped whenever the text with the selection moves or lays out again —
     * a list scrolling under it — so the handles are placed again with it.
     * Read in the overlay's placement block only, so a bump re-places and
     * never recomposes.
     */
    internal var moves by mutableIntStateOf(0)

    /** What the overlay last drew, in its own space: the handles, the bar and the text. */
    internal var chrome: List<Rect> = emptyList()
    internal var textBounds: Rect? = null

    fun show(session: SelectionSession) {
        this.session = session
    }

    fun clear(owner: Any) {
        if (session?.owner === owner) session = null
    }

    fun moved(owner: Any) {
        if (session?.owner === owner) moves++
    }

    /** Whether [point], in the overlay's space, is on something the selection owns. */
    fun hits(point: Offset): Boolean =
        chrome.any { it.contains(point) } || textBounds?.contains(point) == true
}

/** Null outside the keyboard body, where selections draw their highlight and nothing else. */
internal val LocalSelectionOverlay = staticCompositionLocalOf<SelectionOverlayState?> { null }

/**
 * What the read-only text's bar calls into: the AI chat's own action type,
 * which already carries Copy and Insert, plus whether Ask AI is on offer.
 */
@Stable
internal class SelectionTools(val onAiChat: (AiChatAction) -> Unit, val askAi: Boolean)

internal val LocalSelectionTools = compositionLocalOf { SelectionTools({}, askAi = false) }

/**
 * Lets a read-only selection go when a finger lands anywhere else in the
 * keyboard body. Watches the Initial pass and consumes nothing, so the tap
 * still does whatever it was going to do. Only attached while such a
 * selection is up: every key press goes through this box.
 */
internal fun Modifier.selectionDismissObserver(overlay: SelectionOverlayState, active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        pointerInput(overlay) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val session = overlay.session ?: return@awaitEachGesture
                if (session.dismissOnOutsideTap && !overlay.hits(down.position)) session.onDismiss()
            }
        }
    }

/**
 * The handles and the bar for the one selection up, drawn over the keyboard
 * body. Placed with [Modifier.matchParentSize] as the body box's last child;
 * the layout itself takes no touches, so everything under it still gets them.
 */
@Composable
internal fun SelectionOverlay(overlay: SelectionOverlayState, modifier: Modifier = Modifier) {
    val session = overlay.session ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handleBox = with(density) { HandleTouch.roundToPx() }
    val gap = with(density) { BarGap.roundToPx() }
    // A new span of the same text eases the handles and the bar over from
    // where they were; a new text, a scroll or reduce motion places them
    // outright. The ease starts at 0 in the composition that sees the span,
    // so no frame shows them already at the far end.
    val glide = remember(overlay) { HandleGlide() }
    val token = glide.observe(session, LocalKbTheme.current.motion.selection)
    val ease = remember(token) { Animatable(if (glide.animate) 0f else 1f) }
    LaunchedEffect(ease) { ease.animateTo(1f, tween(SelectionEaseMs, easing = FastOutSlowInEasing)) }
    Layout(
        modifier = modifier,
        content = {
            SelectionHandle(session, start = true)
            SelectionHandle(session, start = false)
            SelectionBar(session)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            // Read here, not in composition: a scroll re-places, it never recomposes.
            overlay.moves
            // Likewise the ease, which re-places on every frame of it and
            // recomposes nothing.
            val left = 1f - ease.value
            val own = coordinates
            val geometry = own?.let { selectionGeometry(it, session) }
            if (geometry == null) {
                overlay.chrome = emptyList()
                overlay.textBounds = null
                glide.forget()
                return@layout
            }
            val rects = ArrayList<Rect>(3)
            val visible = geometry.visible
            fun inView(point: Offset) = point.y >= visible.top - 1f && point.y <= visible.bottom + 1f &&
                point.x >= visible.left - 1f && point.x <= visible.right + 1f
            // Each handle hangs from its end of the selection, the point of the
            // drop on the line's foot. A handle whose end is scrolled out of the
            // text's own view is not drawn: it would float over something else.
            // A bar at a bare caret has nothing to stretch, so no handles.
            val caretOnly = session.start == session.end
            listOf(geometry.startFoot, geometry.endFoot).forEachIndexed { index, foot ->
                if (caretOnly || !inView(Offset(foot.x, foot.y - 1f))) {
                    glide.forget(index)
                    return@forEachIndexed
                }
                val at = glide.place(index, token, IntOffset((foot.x - handleBox / 2f).roundToInt(), foot.y.roundToInt()), left)
                val x = at.x
                val y = at.y
                placeables[index].place(x, y)
                rects += Rect(x.toFloat(), y.toFloat(), (x + handleBox).toFloat(), (y + handleBox).toFloat())
            }
            // The bar over the selection's first line, or under its handles
            // when there is no room above, and always inside the body.
            val bar = placeables[2]
            val mid = (geometry.startFoot.x + geometry.endFoot.x) / 2f
            val barX0 = (mid - bar.width / 2f).roundToInt().coerceIn(0, (width - bar.width).coerceAtLeast(0))
            val above = (geometry.top - bar.height - gap).roundToInt()
            val below = (geometry.endFoot.y + handleBox + gap).roundToInt()
            val barTarget = when {
                above >= 0 -> above
                below + bar.height <= height -> below
                else -> (height - bar.height).coerceAtLeast(0)
            }
            val barAt = glide.place(BarSlot, token, IntOffset(barX0, barTarget), left)
            val barX = barAt.x
            val barY = barAt.y
            bar.place(barX, barY)
            rects += Rect(barX.toFloat(), barY.toFloat(), (barX + bar.width).toFloat(), (barY + bar.height).toFloat())
            overlay.chrome = rects
            overlay.textBounds = visible
        }
    }
}

/** Which of [HandleGlide]'s slots is the bar; the two handles are 0 and 1. */
private const val BarSlot = 2

/**
 * Eases the handles and the bar from where they were to where a new span puts
 * them. [observe] runs in composition and hands out a new token whenever the
 * span changes; the first placement under a token works out how far each
 * piece jumped, and every placement after that takes the rest of that jump
 * off what the geometry says, [left] of it at a time. The geometry itself is
 * read fresh each time, so a scroll mid-ease carries the pieces with it.
 *
 * Plain fields, not state: the overlay's placement block is the only reader,
 * and the ease's own value is what re-places it.
 */
private class HandleGlide {
    private var owner: Any? = null
    private var start = -1
    private var end = -1
    private var token = 0

    /** Whether the current token's ease moves anything; false for a snap. */
    var animate = false
        private set

    private val placed = arrayOfNulls<IntOffset>(3)
    private val jump = arrayOfNulls<IntOffset>(3)
    private val jumpToken = IntArray(3) { -1 }

    fun observe(session: SelectionSession, motion: Boolean): Int {
        if (session.owner === owner && session.start == start && session.end == end) return token
        animate = motion && session.owner === owner
        owner = session.owner
        start = session.start
        end = session.end
        token++
        return token
    }

    /** Where piece [slot] goes now, given where the geometry puts it. */
    fun place(slot: Int, token: Int, target: IntOffset, left: Float): IntOffset {
        if (jumpToken[slot] != token) {
            jumpToken[slot] = token
            jump[slot] = placed[slot]?.takeIf { animate }?.let { it - target }
        }
        val by = jump[slot]
        val at = if (by == null || left <= 0f) {
            target
        } else {
            IntOffset(target.x + (by.x * left).roundToInt(), target.y + (by.y * left).roundToInt())
        }
        placed[slot] = at
        return at
    }

    /** Piece [slot] is not on screen: when it comes back, it comes back in place. */
    fun forget(slot: Int) {
        placed[slot] = null
        jump[slot] = null
    }

    fun forget() {
        for (slot in placed.indices) forget(slot)
    }
}

/** How long the handles, the bar and the highlight take to ease to a new span. */
private const val SelectionEaseMs = 140

/** Where a selection's handles and bar go, in the overlay's space. */
private class SelectionGeometry(
    /** The foot of the selection's first character's line, at its leading edge. */
    val startFoot: Offset,
    /** The foot of the last character's line, at its trailing edge. */
    val endFoot: Offset,
    /** The top of the first selected line. */
    val top: Float,
    /** The part of the text that is on screen. */
    val visible: Rect,
)

private fun selectionGeometry(own: LayoutCoordinates, session: SelectionSession): SelectionGeometry? {
    val text = session.coordinates() ?: return null
    // A detached box cannot be asked where it is; the text left the screen
    // and its DisposableEffect has not run yet.
    if (!own.isAttached || !text.isAttached) return null
    if (session.start == session.end) return caretGeometry(own, text, session)
    val layout = session.layout() ?: return null
    val length = layout.layoutInput.text.length
    val start = session.start.coerceIn(0, length)
    val end = session.end.coerceIn(0, length)
    if (start >= end) return null
    val startLine = layout.getLineForOffset(start)
    val endLine = layout.getLineForOffset(end - 1)
    val startBox = layout.getBoundingBox(start)
    val endBox = layout.getBoundingBox(end - 1)
    val startX = if (layout.getBidiRunDirection(start) == ResolvedTextDirection.Rtl) startBox.right else startBox.left
    val endX = if (layout.getBidiRunDirection(end - 1) == ResolvedTextDirection.Rtl) endBox.left else endBox.right
    return SelectionGeometry(
        startFoot = own.localPositionOf(text, Offset(startX, layout.getLineBottom(startLine))),
        endFoot = own.localPositionOf(text, Offset(endX, layout.getLineBottom(endLine))),
        top = own.localPositionOf(text, Offset(0f, layout.getLineTop(startLine))).y,
        visible = own.localBoundingBoxOf(text, clipBounds = true),
    )
}

/**
 * Where the bar goes over a bare caret (#434): both feet on the caret's own
 * foot. An empty box has no layout of its text to ask, so its caret is the
 * box's leading edge, which is where the first character will land.
 */
private fun caretGeometry(own: LayoutCoordinates, text: LayoutCoordinates, session: SelectionSession): SelectionGeometry {
    val layout = session.layout()
    val rect = if (layout != null) {
        layout.getCursorRect(session.start.coerceIn(0, layout.layoutInput.text.length))
    } else {
        Rect(0f, 0f, 0f, text.size.height.toFloat())
    }
    val foot = own.localPositionOf(text, Offset(rect.left, rect.bottom))
    return SelectionGeometry(
        startFoot = foot,
        endFoot = foot,
        top = own.localPositionOf(text, Offset(rect.left, rect.top)).y,
        visible = own.localBoundingBoxOf(text, clipBounds = true),
    )
}

/**
 * One drop-shaped handle. The drop's point sits on the selection's end and a
 * drag moves that end to the character under the point, not under the finger:
 * the finger is below the line, where it does not hide the text.
 */
@Composable
private fun SelectionHandle(session: SelectionSession, start: Boolean) {
    val kb = LocalKbTheme.current
    val current by rememberUpdatedState(session)
    val holder = remember { SelectionAnchor() }
    Box(
        modifier = Modifier
            .size(HandleTouch)
            .onPlaced { holder.coordinates = it }
            .pointerInput(start) {
                // Where the point was when the drag began, and how far from it
                // the finger landed, both in the text's own space.
                var grab = Offset.Zero
                var lastOffset = -1
                detectDragGestures(
                    onDragStart = { finger ->
                        val handle = holder.coordinates
                        val text = current.coordinates()
                        if (handle != null && text != null && handle.isAttached && text.isAttached) {
                            val point = text.localPositionOf(handle, Offset(size.width / 2f, 0f))
                            grab = text.localPositionOf(handle, finger) - point
                        }
                        lastOffset = -1
                    },
                ) { change, _ ->
                    change.consume()
                    val s = current
                    val handle = holder.coordinates ?: return@detectDragGestures
                    val text = s.coordinates() ?: return@detectDragGestures
                    if (!handle.isAttached || !text.isAttached) return@detectDragGestures
                    val layout = s.layout() ?: return@detectDragGestures
                    val length = layout.layoutInput.text.length
                    if (length == 0) return@detectDragGestures
                    // Converted fresh on every event: the handle moves under
                    // the finger, so its own space is not a fixed frame.
                    val point = text.localPositionOf(handle, change.position) - grab
                    val line = layout.getLineForVerticalPosition(point.y - 1f)
                    val probe = Offset(point.x, (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)
                    val offset = layout.getOffsetForPosition(probe).coerceIn(0, length)
                    if (offset == lastOffset) return@detectDragGestures
                    lastOffset = offset
                    // A handle never crosses the other one: at least one
                    // character stays selected.
                    if (start) {
                        val next = offset.coerceAtMost(s.end - 1).coerceAtLeast(0)
                        if (next != s.start) s.onChange(next, s.end, true)
                    } else {
                        val next = offset.coerceAtLeast(s.start + 1).coerceAtMost(length)
                        if (next != s.end) s.onChange(s.start, next, false)
                    }
                }
            },
    ) {
        val color = kb.accent
        Canvas(Modifier.size(HandleTouch)) {
            val r = HandleDrop.toPx() / 2f
            val cx = size.width / 2f
            // The drop hangs to the outside of the selection: left of the
            // start, right of the end, its square corner on the point.
            val centre = Offset(if (start) cx - r else cx + r, r)
            drawCircle(color, radius = r, center = centre)
            val corner = Path().apply {
                if (start) {
                    moveTo(cx - r, 0f); lineTo(cx, 0f); lineTo(cx, r); lineTo(cx - r, r)
                } else {
                    moveTo(cx, 0f); lineTo(cx + r, 0f); lineTo(cx + r, r); lineTo(cx, r)
                }
                close()
            }
            drawPath(corner, color)
        }
    }
}

/**
 * Where one piece of selectable text last was, and the identity it publishes
 * its selection under. A plain field, not state: the overlay re-reads it when
 * [SelectionOverlayState.moved] says to.
 */
internal class SelectionAnchor {
    var coordinates: LayoutCoordinates? = null
}

/** The bar of actions over the selection, in the theme's popup colours. */
@Composable
private fun SelectionBar(session: SelectionSession) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            // Through elevationFor like every other raised surface (#1327): a
            // scalloped chip shape (Sakura breeze) is concave, and Skia's
            // concave shadow never finishes on its rim of bumps. The bar went
            // up on the first selection in a tool's field or text, the
            // RenderThread spun, and Android offered to close the keyboard.
            .shadow(elevationFor(kb.chipShapeKind, kb.popupElevation.coerceAtLeast(2.dp)), shape)
            .clip(shape)
            .background(kb.popup)
            .padding(horizontal = 2.dp),
    ) {
        for (action in session.actions) {
            Text(
                action.label,
                color = if (action.primary) kb.accent else kb.popupText,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .clickable(onClick = action.onClick)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            )
        }
    }
}

/**
 * Hands [start]..[end] of one text to the overlay while [shown], and takes it
 * back when not, or when the text leaves the screen.
 */
@Composable
internal fun PublishSelection(
    owner: Any,
    shown: Boolean,
    start: Int,
    end: Int,
    coordinates: () -> LayoutCoordinates?,
    layout: () -> TextLayoutResult?,
    actions: List<SelectionBarAction>,
    dismissOnOutsideTap: Boolean,
    onChange: (Int, Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val overlay = LocalSelectionOverlay.current ?: return
    SideEffect {
        if (shown) {
            overlay.show(
                SelectionSession(owner, coordinates, layout, start, end, actions, dismissOnOutsideTap, onChange, onDismiss),
            )
        } else {
            overlay.clear(owner)
        }
    }
    DisposableEffect(overlay, owner) { onDispose { overlay.clear(owner) } }
}

/**
 * The highlight under [start]..[end], drawn from [layout] only when it is a
 * layout of [text].
 *
 * A new span of the same text sweeps over from the old one, a character at a
 * time, under Animate text selection: a select-all reads as the selection
 * growing rather than as a second highlight replacing the first. Appearing,
 * going and a new text are never animated.
 */
internal fun Modifier.selectionHighlight(
    text: String,
    start: Int,
    end: Int,
    color: Color,
    layout: () -> TextLayoutResult?,
): Modifier = this then SelectionHighlightElement(text, minOf(start, end), maxOf(start, end), color, layout)

private data class SelectionHighlightElement(
    val text: String,
    val from: Int,
    val to: Int,
    val color: Color,
    val layout: () -> TextLayoutResult?,
) : ModifierNodeElement<SelectionHighlightNode>() {
    override fun create() = SelectionHighlightNode(text, from, to, color, layout)

    override fun update(node: SelectionHighlightNode) = node.update(text, from, to, color, layout)
}

private class SelectionHighlightNode(
    private var text: String,
    private var from: Int,
    private var to: Int,
    private var color: Color,
    private var layout: () -> TextLayoutResult?,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    // The span as drawn while it eases, in fractional characters. Only read
    // while [easing]; settled, the selection's own span is drawn.
    private val shownFrom = Animatable(from.toFloat())
    private val shownTo = Animatable(to.toFloat())
    private var easing = false

    fun update(text: String, from: Int, to: Int, color: Color, layout: () -> TextLayoutResult?) {
        val oldFrom = this.from
        val oldTo = this.to
        val sameText = text == this.text
        val moved = from != oldFrom || to != oldTo || !sameText
        val ease = moved && sameText && oldFrom < oldTo && from < to &&
            isAttached && currentValueOf(LocalKbTheme).motion.selection
        this.text = text
        this.from = from
        this.to = to
        this.color = color
        this.layout = layout
        if (ease) {
            val restart = !easing
            easing = true
            // Undispatched, so a fresh sweep has its start in place before
            // the next draw reads it.
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (restart) {
                    shownFrom.snapTo(oldFrom.toFloat())
                    shownTo.snapTo(oldTo.toFloat())
                }
                val lead = launch { shownFrom.animateTo(from.toFloat(), tween(SelectionEaseMs, easing = FastOutSlowInEasing)) }
                shownTo.animateTo(to.toFloat(), tween(SelectionEaseMs, easing = FastOutSlowInEasing))
                lead.join()
                easing = false
                invalidateDraw()
            }
        } else if (moved) {
            easing = false
        }
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawHighlight()
        drawContent()
    }

    private fun ContentDrawScope.drawHighlight() {
        if (from >= to) return
        val result = layout()?.takeIf { it.layoutInput.text.text == text } ?: return
        val length = result.layoutInput.text.length
        // Easing, whole characters only, so the sweep never cuts a glyph in half.
        val lo = (if (easing) floor(shownFrom.value).toInt() else from).coerceIn(0, length)
        val hi = (if (easing) ceil(shownTo.value).toInt() else to).coerceIn(0, length)
        if (lo < hi) drawPath(result.getPathForRange(lo, hi), color)
    }
}

/**
 * Read-only text a long press can select from (#352): Wikipedia's article,
 * a dictionary's definitions, a vocabulary card's senses. The bar offers
 * Ask AI (when the AI tool is on), Copy and Insert.
 *
 * [ask] names where the text came from for Ask AI, which is offered only
 * with one, and the whole of [text] travels with the selection as its
 * paragraph. [onTap] is the text's own tap, which a selection being up takes
 * instead, to let go of it. [onWordTap], when given, takes the tap in its
 * place with the word under the finger (#1085: a word in a definition looks
 * that word up); a tap on no word still goes to [onTap].
 */
@Composable
internal fun SelectableText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null,
    ask: AskAiSource? = null,
    /**
     * Turns the selected passage into a quote naming its source (#348); with
     * one, the bar offers Quote, which inserts that at the cursor.
     */
    quote: ((String) -> String)? = null,
    onTap: (() -> Unit)? = null,
    onWordTap: ((String) -> Unit)? = null,
) = SelectableText(
    AnnotatedString(text), color, fontSize, modifier, lineHeight, fontWeight, fontStyle, fontFamily, ask, quote, onTap,
    onWordTap = onWordTap,
)

/**
 * [SelectableText] over styled text: a note's Markdown paragraph keeps its
 * bold, code and links, and the selection and its bar work on the plain
 * characters underneath.
 */
@Composable
internal fun SelectableText(
    styled: AnnotatedString,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null,
    ask: AskAiSource? = null,
    quote: ((String) -> String)? = null,
    onTap: (() -> Unit)? = null,
    /** Inline formulas (#811) drawn in the text's placeholders; a selection takes their plain text. */
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onWordTap: ((String) -> Unit)? = null,
) {
    val text = styled.text
    val kb = LocalKbTheme.current
    val tools = LocalSelectionTools.current
    val overlay = LocalSelectionOverlay.current
    val haptic = LocalHapticFeedback.current
    val owner = remember { SelectionAnchor() }
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    // Start and end, or null. Forgotten with the text: a new article is not
    // the one the selection was made in.
    var selection by remember(text) { mutableStateOf<IntRange?>(null) }
    val tapAction by rememberUpdatedState(onTap)
    val wordTapAction by rememberUpdatedState(onWordTap)
    val layoutOf = remember(text) { { layoutState.value?.takeIf { it.layoutInput.text.text == text } } }
    val current = selection
    val start = current?.first ?: 0
    val end = current?.let { it.last + 1 } ?: 0
    Text(
        text = styled,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        inlineContent = inlineContent,
        onTextLayout = {
            layoutState.value = it
            overlay?.moved(owner)
        },
        modifier = modifier
            .onGloballyPositioned {
                owner.coordinates = it
                overlay?.moved(owner)
            }
            .selectionHighlight(text, start, end, kb.accent.copy(alpha = HighlightAlpha)) { layoutState.value }
            .pointerInput(text) {
                detectTapGestures(
                    onLongPress = { position ->
                        val layout = layoutState.value?.takeIf { it.layoutInput.text.text == text }
                            ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position).coerceIn(0, layout.layoutInput.text.length)
                        val span = CaretText.wordSpanAt(text, offset) ?: return@detectTapGestures
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selection = span
                    },
                    onTap = { position ->
                        val wordTap = wordTapAction
                        val word = if (selection == null && wordTap != null) {
                            layoutState.value?.takeIf { it.layoutInput.text.text == text }?.let { layout ->
                                val offset = layout.getOffsetForPosition(position).coerceIn(0, text.length)
                                CaretText.wordSpanAt(text, offset)?.let { text.substring(it.first, it.last + 1) }
                            }
                        } else {
                            null
                        }
                        when {
                            selection != null -> selection = null
                            word != null -> wordTap?.invoke(word)
                            else -> tapAction?.invoke()
                        }
                    },
                )
            },
    )
    val selected = text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
    val askLabel = stringResource(R.string.ime_selection_ask_ai)
    val copyLabel = stringResource(CommonR.string.common_copy)
    val insertLabel = stringResource(R.string.ime_ai_insert)
    val allLabel = stringResource(CommonR.string.common_select_all)
    val quoteLabel = stringResource(R.string.ime_selection_quote)
    val actions = remember(selected, tools, ask, text, quote) {
        if (selected.isEmpty()) return@remember emptyList()
        buildList {
            if (tools.askAi && ask != null) {
                add(
                    SelectionBarAction(askLabel, primary = true) {
                        selection = null
                        tools.onAiChat(
                            AiChatAction.AskAbout(
                                text = AskAiContext.selection(selected, ask.framing, text),
                                label = ask.label,
                                fromSelection = true,
                            ),
                        )
                    },
                )
            }
            add(SelectionBarAction(copyLabel) { selection = null; tools.onAiChat(AiChatAction.Copy(selected)) })
            add(SelectionBarAction(insertLabel) { selection = null; tools.onAiChat(AiChatAction.Insert(selected, raw = true)) })
            if (quote != null) {
                add(SelectionBarAction(quoteLabel) { selection = null; tools.onAiChat(AiChatAction.Insert(quote(selected), raw = true)) })
            }
            if (selected.length < text.length) {
                add(SelectionBarAction(allLabel) { selection = text.indices })
            }
        }
    }
    PublishSelection(
        owner = owner,
        shown = current != null,
        start = start,
        end = end,
        coordinates = { owner.coordinates },
        layout = layoutOf,
        actions = actions,
        dismissOnOutsideTap = true,
        onChange = { s, e, _ -> selection = s until e },
        onDismiss = { selection = null },
    )
}

/**
 * The bar and handles for a keyboard-owned field's selection: publishes the
 * service's selection of [text], and turns the bar's taps and the handles'
 * drags back into the field's own callbacks.
 *
 * [caretBar] is the other bar a field has (#434): a long press on no word,
 * or on an empty field, asks for Paste at the caret, and Select all when
 * there is text to select. Without it an empty field had no way to take a
 * paste at all. Unlike a selection it is the field's own to forget: a tap
 * anywhere else, or on the field, calls [onCaretBarDismiss].
 */
@Composable
internal fun PublishFieldSelection(
    owner: Any,
    text: String,
    active: Boolean,
    handle: CaptureCaretHandle,
    coordinates: () -> LayoutCoordinates?,
    layout: () -> TextLayoutResult?,
    caretBar: Boolean = false,
    onCaretBarDismiss: () -> Unit = {},
) {
    val selecting = active && handle.hasSelection && handle.selectionEnd <= text.length
    val atCaret = active && caretBar && !handle.hasSelection
    val cut = stringResource(CommonR.string.common_cut)
    val copy = stringResource(CommonR.string.common_copy)
    val paste = stringResource(CommonR.string.common_paste)
    val all = stringResource(CommonR.string.common_select_all)
    val onAction = handle.onSelectionAction
    val dismissCaretBar by rememberUpdatedState(onCaretBarDismiss)
    val wholeSelected = handle.selectionEnd - handle.selectionStart == text.length
    val actions = remember(onAction, cut, copy, paste, all, wholeSelected, atCaret) {
        buildList {
            if (atCaret) {
                add(SelectionBarAction(paste) { dismissCaretBar(); onAction(CaptureSelectionAction.PASTE) })
                if (!wholeSelected) {
                    add(SelectionBarAction(all) { dismissCaretBar(); onAction(CaptureSelectionAction.SELECT_ALL) })
                }
            } else {
                add(SelectionBarAction(cut) { onAction(CaptureSelectionAction.CUT) })
                add(SelectionBarAction(copy) { onAction(CaptureSelectionAction.COPY) })
                add(SelectionBarAction(paste) { onAction(CaptureSelectionAction.PASTE) })
                if (!wholeSelected) {
                    add(SelectionBarAction(all) { onAction(CaptureSelectionAction.SELECT_ALL) })
                }
            }
        }
    }
    val select = handle.onSelect
    val caret = handle.at.coerceIn(0, text.length)
    PublishSelection(
        owner = owner,
        shown = selecting || atCaret,
        start = if (atCaret) caret else handle.selectionStart,
        end = if (atCaret) caret else handle.selectionEnd,
        coordinates = coordinates,
        layout = layout,
        actions = actions,
        // The field's selection lives in the service next to its caret; a
        // key press is meant to replace it, not to end it first. The caret's
        // bar is only an offer, and a key press is the answer no.
        dismissOnOutsideTap = atCaret,
        // The moving end becomes the caret, so the strip follows the handle.
        onChange = { s, e, movedStart -> if (movedStart) select(e, s) else select(s, e) },
        onDismiss = { dismissCaretBar() },
    )
}

/**
 * The long press every keyboard-owned field shares: the word under the
 * finger selected, or the caret put there when it is on no word. False in
 * the second case, which is the field's cue to offer Paste at the caret.
 */
internal fun fieldLongPress(text: String, layout: TextLayoutResult?, position: Offset, handle: CaptureCaretHandle): Boolean {
    val result = layout?.takeIf { it.layoutInput.text.text == text } ?: return false
    val offset = result.getOffsetForPosition(position).coerceIn(0, result.layoutInput.text.length)
    val span = CaretText.wordSpanAt(text, offset)
    if (span == null) {
        handle.onCaretTap(offset)
    } else {
        handle.onSelect(span.first, span.last + 1)
    }
    return span != null
}

/**
 * Everything a finger does on a keyboard-owned field's text, the way a real
 * text field answers it (#612):
 *
 * - a tap puts the caret under the finger at once. `detectTapGestures` with a
 *   double tap held every single tap back for the double-tap timeout, which
 *   is what made placing the caret feel laboured;
 * - a second tap soon after, near the first, selects the word;
 * - a press and hold selects the word, and the finger, still down, stretches
 *   the selection a word at a time;
 * - a drag that starts on either end of a selection moves that end. The
 *   overlay's handles hang under the line and are small, so a finger aimed
 *   at one often landed on the text instead, where the hold selected the
 *   word under it: the selection jumped to another word instead of growing;
 * - any other drag is left alone, for the field's scroll.
 *
 * Positions are in the text's own space, so this goes on the box the text is
 * laid out at the top left of. [belowText] says a press under the last line
 * is a press on the field's empty end: the caret goes to the end, and a hold
 * there offers Paste.
 */
internal fun Modifier.fieldTextGestures(
    key: Any?,
    text: () -> String,
    layout: () -> TextLayoutResult?,
    handle: () -> CaptureCaretHandle,
    setCaretBar: (Boolean) -> Unit,
    onHold: () -> Unit = {},
    belowText: Boolean = false,
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    val holdMs = viewConfiguration.longPressTimeoutMillis
    val doubleMs = viewConfiguration.doubleTapTimeoutMillis
    val reach = FieldHandleReach.toPx()
    val drop = FieldHandleDrop.toPx()
    val doubleSlop = FieldDoubleTapSlop.toPx()
    var lastUp = 0L
    var lastAt = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown()
        val first = text()
        fun current(): TextLayoutResult? = layout()?.takeIf { it.layoutInput.text.text == text() }
        val startLayout = current()
        val held = handle()
        // Which end of the selection the finger is on, if either.
        val grabbed = startLayout?.takeIf { held.hasSelection && held.selectionEnd <= first.length }
            ?.let { selectionEndUnder(it, held, down.position, reach, drop) }
        // Wait for the finger to lift, move off, or stay: a tap, a drag, a hold.
        var outcome = FieldTouch.HOLD
        var upTime = 0L
        withTimeoutOrNull(holdMs) {
            var done = false
            while (!done) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    change == null -> { outcome = FieldTouch.GONE; done = true }
                    change.changedToUp() -> {
                        outcome = if (change.isConsumed) FieldTouch.GONE else FieldTouch.TAP
                        upTime = change.uptimeMillis
                        if (!change.isConsumed) change.consume()
                        done = true
                    }
                    change.isConsumed -> { outcome = FieldTouch.GONE; done = true }
                    (change.position - down.position).getDistance() > slop -> {
                        outcome = if (grabbed != null) FieldTouch.DRAG_END else FieldTouch.GONE
                        if (grabbed != null) change.consume()
                        done = true
                    }
                }
            }
        }
        when (outcome) {
            FieldTouch.GONE -> Unit
            FieldTouch.TAP -> {
                val result = current() ?: return@awaitEachGesture
                val now = handle()
                val double = down.uptimeMillis - lastUp <= doubleMs && (down.position - lastAt).getDistance() <= doubleSlop
                if (double) {
                    lastUp = 0L
                    setCaretBar(!fieldLongPress(text(), result, down.position, now))
                    return@awaitEachGesture
                }
                lastUp = upTime
                lastAt = down.position
                setCaretBar(false)
                val length = result.layoutInput.text.length
                val offset = if (belowText && down.position.y > result.size.height) {
                    length
                } else {
                    result.getOffsetForPosition(down.position).coerceIn(0, length)
                }
                now.onCaretTap(offset)
            }
            FieldTouch.DRAG_END -> {
                val end = grabbed ?: return@awaitEachGesture
                val result = startLayout ?: return@awaitEachGesture
                // Where the finger is from the middle of the end's line, so the
                // end follows the finger and not the point under it.
                val grab = down.position - end.lineMiddle
                val fixed = if (end.start) held.selectionEnd else held.selectionStart
                var last = -1
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    change.consume()
                    val now = current() ?: result
                    val length = now.layoutInput.text.length
                    if (length == 0) continue
                    val point = change.position - grab
                    val line = now.getLineForVerticalPosition(point.y)
                    val probe = Offset(point.x, (now.getLineTop(line) + now.getLineBottom(line)) / 2f)
                    val offset = now.getOffsetForPosition(probe).coerceIn(0, length)
                    // An end never crosses the other: one character stays selected.
                    val moved = if (end.start) offset.coerceAtMost(fixed - 1).coerceAtLeast(0) else offset.coerceAtLeast(fixed + 1).coerceAtMost(length)
                    if (moved == last) continue
                    last = moved
                    handle().onSelect(fixed, moved)
                }
            }
            FieldTouch.HOLD -> {
                val result = current()
                val now = handle()
                onHold()
                if (result == null || (belowText && down.position.y > result.size.height)) {
                    now.onCaretTap(text().length)
                    setCaretBar(true)
                    return@awaitEachGesture
                }
                val shown = text()
                val length = result.layoutInput.text.length
                val offset = result.getOffsetForPosition(down.position).coerceIn(0, length)
                val span = CaretText.wordSpanAt(shown, offset)
                if (span == null) {
                    now.onCaretTap(offset)
                    setCaretBar(true)
                    return@awaitEachGesture
                }
                setCaretBar(false)
                now.onSelect(span.first, span.last + 1)
                // Still down: the selection stretches a word at a time.
                var last = span.last + 1
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    change.consume()
                    val live = current() ?: continue
                    val at = live.getOffsetForPosition(change.position).coerceIn(0, live.layoutInput.text.length)
                    val word = CaretText.wordSpanAt(text(), at)
                    val (anchor, caret) = if (at >= span.first) {
                        span.first to maxOf(span.last + 1, word?.let { it.last + 1 } ?: at)
                    } else {
                        (span.last + 1) to (word?.first ?: at)
                    }
                    if (caret == last) continue
                    last = caret
                    handle().onSelect(anchor, caret)
                }
            }
        }
    }
}

/** What a finger on a field's text turned out to be; see [fieldTextGestures]. */
private enum class FieldTouch { TAP, HOLD, DRAG_END, GONE }

/** One end of a selection a finger landed on: which, and the middle of its line. */
private class SelectionEndTouch(val start: Boolean, val lineMiddle: Offset)

/**
 * The end of [handle]'s selection [position] is on, or null: within [reach]
 * of it across, and from the top of its line to [drop] under the line, where
 * the handle hangs. The nearer end wins where both are in reach.
 */
private fun selectionEndUnder(
    layout: TextLayoutResult,
    handle: CaptureCaretHandle,
    position: Offset,
    reach: Float,
    drop: Float,
): SelectionEndTouch? {
    val length = layout.layoutInput.text.length
    var best: SelectionEndTouch? = null
    var bestDistance = Float.MAX_VALUE
    for (start in listOf(true, false)) {
        val offset = (if (start) handle.selectionStart else handle.selectionEnd).coerceIn(0, length)
        val rect = layout.getCursorRect(offset)
        val dx = kotlin.math.abs(position.x - rect.left)
        if (dx > reach || position.y < rect.top || position.y > rect.bottom + drop) continue
        val middle = Offset(rect.left, (rect.top + rect.bottom) / 2f)
        val distance = (position - Offset(rect.left, rect.bottom)).getDistance()
        if (distance < bestDistance) {
            bestDistance = distance
            best = SelectionEndTouch(start, middle)
        }
    }
    return best
}

/** How far across from a selection's end a drag still takes that end, and how far under its line. */
private val FieldHandleReach: Dp = 22.dp
private val FieldHandleDrop: Dp = 40.dp

/** How near the first tap a second one has to land to be a double tap. */
private val FieldDoubleTapSlop: Dp = 40.dp

/** Touch box around one handle; the drop drawn in it is [HandleDrop] across. */
private val HandleTouch: Dp = 36.dp
private val HandleDrop: Dp = 20.dp
private val BarGap: Dp = 6.dp

/** The selection's wash over the text, in the theme's accent. */
internal const val HighlightAlpha = 0.32f

/**
 * The Ask AI chip a tool keeps on screen for its whole text (#352), drawn
 * only while the AI tool is on. [text] is built on tap, not before: an
 * article's full text is long, and most opens never ask.
 */
@Composable
internal fun AskAiChip(label: String, modifier: Modifier = Modifier, text: () -> String) {
    val tools = LocalSelectionTools.current
    if (!tools.askAi) return
    ToolPanelChip(stringResource(R.string.ime_selection_ask_ai), selected = true, modifier = modifier) {
        tools.onAiChat(AiChatAction.AskAbout(text(), label, fromSelection = false))
    }
}

/**
 * The Copy chip beside a tool's Ask AI (#348): the same text, to the
 * clipboard. Offered whether or not the AI tool is on.
 */
@Composable
internal fun CopyTextChip(modifier: Modifier = Modifier, text: () -> String) {
    val tools = LocalSelectionTools.current
    ToolPanelChip(stringResource(CommonR.string.common_copy), modifier = modifier) {
        tools.onAiChat(AiChatAction.Copy(text()))
    }
}

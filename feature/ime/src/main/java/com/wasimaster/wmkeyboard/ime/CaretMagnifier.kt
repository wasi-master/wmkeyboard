package com.wasimaster.wmkeyboard.ime

import android.os.Build
import android.text.InputType
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What is dragging the caret while the magnifier is up. Two surfaces can, and
 * each owns its own half of the pairing, so one letting go never takes the
 * bubble away from the other.
 */
enum class CaretDragSource { TRACKPAD, SPACEBAR }

/**
 * The line the magnifier shows: the text around the caret, cut at the line
 * breaks on either side, with the caret and any selection as offsets into it.
 * [selStart] equals [selEnd] when nothing is selected.
 *
 * [selectedBeyond] is for a selection too long to read back: only the text on
 * the caret's free side was read, and the selection runs off the other edge of
 * the bubble, which draws it as a tint from the caret to that edge.
 */
@Immutable
data class MagnifierLine(
    val text: String,
    val caret: Int,
    val selStart: Int,
    val selEnd: Int,
    val selectedBeyond: SelectionBeyond = SelectionBeyond.NONE,
)

/** Which way an unread selection runs from the caret, if one does. See [MagnifierLine.selectedBeyond]. */
enum class SelectionBeyond { NONE, BEFORE, AFTER }

/** Where the field's caret is on screen, in pixels: its x and its line's top and bottom. */
@Immutable
data class CaretAnchor(val x: Float, val top: Float, val bottom: Float)

/**
 * Everything the magnifier bubble draws. [anchor] is null for an editor that
 * does not say where its caret is, and the bubble then sits over the keyboard.
 */
@Immutable
data class CaretMagnifierState(val line: MagnifierLine, val anchor: CaretAnchor?)

/** How much text is read on each side of the caret. The bubble shows far less. */
internal const val MagnifierContextChars = 64

/**
 * The longest selection read back whole. The bubble only ever shows the
 * [MagnifierContextChars] next to the caret, and the read has no length limit
 * of its own: a select-all over a long document would otherwise cross the
 * binder on every step of the drag, and past a megabyte fail outright.
 */
internal const val MagnifierSelectionReadCap = 1024

/** Stands in for every character of a field that hides what is typed. */
private const val MaskChar = '\u2022'

/**
 * The line to magnify, from what the field returned around its selection.
 *
 * [before] ends where the selection starts and [after] begins where it ends.
 * The caret the magnifier centres on is the end of the selection that moves:
 * [focusAtEnd] says which. A selection longer than the context keeps only the
 * part next to that end, since the rest could never be on screen anyway.
 */
internal fun magnifierLine(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    focusAtEnd: Boolean,
    context: Int = MagnifierContextChars,
): MagnifierLine {
    // Text left and right of the caret, plus where the selection sits in
    // left + right. Only the side the caret is on keeps the selection's far end.
    val left: String
    val right: String
    val selStart: Int
    val selEnd: Int
    if (focusAtEnd) {
        val sel = selected.takeLastSafe(context)
        val head = if (sel.length < selected.length) "" else before.takeLastSafe(context)
        left = head + sel
        right = after.takeSafe(context)
        selStart = head.length
        selEnd = left.length
    } else {
        val sel = selected.takeSafe(context)
        val tail = if (sel.length < selected.length) "" else after.takeSafe(context)
        left = before.takeLastSafe(context)
        right = sel + tail
        selStart = left.length
        selEnd = left.length + sel.length
    }
    val lineStart = left.lastIndexOf('\n') + 1
    val newline = right.indexOf('\n')
    val lineEnd = left.length + if (newline < 0) right.length else newline
    val text = (left + right).substring(lineStart, lineEnd)
    fun clip(offset: Int) = (offset - lineStart).coerceIn(0, text.length)
    return MagnifierLine(
        text = text,
        caret = left.length - lineStart,
        selStart = clip(selStart),
        selEnd = clip(selEnd),
    )
}

/** The last [n] chars, one fewer rather than splitting a surrogate pair. */
private fun CharSequence.takeLastSafe(n: Int): String {
    if (length <= n) return toString()
    var start = length - n
    if (Character.isLowSurrogate(this[start])) start++
    return subSequence(start, length).toString()
}

/** The first [n] chars, one fewer rather than splitting a surrogate pair. */
private fun CharSequence.takeSafe(n: Int): String {
    if (length <= n) return toString()
    var end = n
    if (Character.isHighSurrogate(this[end - 1])) end--
    return subSequence(0, end).toString()
}

/**
 * The line to magnify from a single surrounding-text read: [text] holds the
 * selection with up to [MagnifierContextChars] on either side, [selStart] and
 * [selEnd] are where the selection sits in it (either order).
 */
internal fun magnifierLineFromSurrounding(
    text: CharSequence,
    selStart: Int,
    selEnd: Int,
    focusAtEnd: Boolean,
): MagnifierLine? {
    val lo = minOf(selStart, selEnd)
    val hi = maxOf(selStart, selEnd)
    if (lo < 0 || hi > text.length) return null
    return magnifierLine(text.subSequence(0, lo), text.subSequence(lo, hi), text.subSequence(hi, text.length), focusAtEnd)
}

/**
 * The line for a selection too long to read back: only the text on the far
 * side of the moving end, with the selection marked as running off the other
 * edge. [side] is what was read: after the selection when the end moves, before
 * it when the start does.
 */
internal fun magnifierLineBeyondSelection(side: CharSequence, focusAtEnd: Boolean): MagnifierLine {
    val line = if (focusAtEnd) {
        magnifierLine("", "", side, focusAtEnd = true)
    } else {
        magnifierLine(side, "", "", focusAtEnd = true)
    }
    return line.copy(selectedBeyond = if (focusAtEnd) SelectionBeyond.BEFORE else SelectionBeyond.AFTER)
}

/**
 * [line] as a password field shows it: one bullet per character, the caret and
 * the selection on the same characters. Counted in code points, so an emoji in
 * a password is one bullet, as the field draws it.
 */
internal fun MagnifierLine.masked(): MagnifierLine {
    val text = text
    fun points(end: Int) = text.codePointCount(0, end.coerceIn(0, text.length))
    val count = points(text.length)
    return copy(
        text = MaskChar.toString().repeat(count),
        caret = points(caret),
        selStart = points(selStart),
        selEnd = points(selEnd),
    )
}

/**
 * Whether a field of [inputType] hides what is typed in it. The magnifier then
 * draws bullets, as the field does: enlarging a password over the app is the
 * one thing it must never do. A visible-password field shows its text anyway.
 */
internal fun hidesTypedText(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/**
 * Reads the line around a selection out of [ic], the way that costs least.
 * [selStart] and [selEnd] are the field's last reported selection in either
 * order, -1 when it never said; [focusAtEnd] says which end is moving.
 *
 * A selection short enough to read whole comes back in one call on Android 12
 * and up ([InputConnection.getSurroundingText]), three below it. A longer one
 * is not read at all: only the text past the moving end is, see
 * [MagnifierSelectionReadCap]. Null when the field gave nothing back.
 */
internal fun readMagnifierLine(
    ic: InputConnection,
    selStart: Int,
    selEnd: Int,
    focusAtEnd: Boolean,
    sdk: Int = Build.VERSION.SDK_INT,
): MagnifierLine? {
    val known = selStart >= 0 && selEnd >= 0
    val span = if (known) kotlin.math.abs(selEnd - selStart) else 0
    if (span > MagnifierSelectionReadCap) {
        val side = if (focusAtEnd) {
            ic.getTextAfterCursor(MagnifierContextChars, 0)
        } else {
            ic.getTextBeforeCursor(MagnifierContextChars, 0)
        } ?: return null
        return magnifierLineBeyondSelection(side, focusAtEnd)
    }
    if (known && sdk >= Build.VERSION_CODES.S) {
        val surrounding = ic.getSurroundingText(MagnifierContextChars, MagnifierContextChars, 0)
        if (surrounding != null) {
            magnifierLineFromSurrounding(
                surrounding.text,
                surrounding.selectionStart,
                surrounding.selectionEnd,
                focusAtEnd,
            )?.let { return it }
        }
    }
    val before = ic.getTextBeforeCursor(MagnifierContextChars, 0) ?: return null
    // With no reported selection there is nothing to say how long it is, so it
    // is not read: an unknown length is the one the cap exists for.
    val selected = if (span > 0) ic.getSelectedText(0) ?: "" else ""
    val after = ic.getTextAfterCursor(MagnifierContextChars, 0) ?: ""
    return magnifierLine(before, selected, after, focusAtEnd)
}

/**
 * The caret's place on screen from an editor's cursor report, or null when it
 * left the insertion marker out or said the marker is scrolled out of sight
 * (the bubble then sits over the keyboard rather than over a hidden caret).
 * Editors report the marker at the selection's start, so while a selection
 * grows forward the bubble stays over its start.
 */
internal fun caretAnchorOf(info: CursorAnchorInfo): CaretAnchor? {
    val x = info.insertionMarkerHorizontal
    val top = info.insertionMarkerTop
    val bottom = info.insertionMarkerBottom
    if (x.isNaN() || top.isNaN() || bottom.isNaN()) return null
    if (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) return null
    val points = floatArrayOf(x, top, x, bottom)
    info.matrix.mapPoints(points)
    return CaretAnchor(points[0], points[1], points[3])
}

/**
 * The magnifier over the caret while a trackpad or spacebar drag moves it
 * (discussion #303).
 *
 * The bubble is the keyboard's own drawing, not a lens on the app. An input
 * method cannot read another app's pixels, so it shows the text the field
 * hands back around the caret, and asks the field where the caret is on screen
 * so the bubble can sit over it. Both are read only while a drag is in
 * progress: the cursor reports are switched on at the start and off at the end,
 * and the text is read again after each move the field reports.
 *
 * Every call into the field runs on one background lane ([io]), in the order
 * it was asked for, so a slow editor never stalls a touch and the switch-off
 * can never overtake the switch-on. Reads are coalesced: while one is in
 * flight, any number of moves ask for exactly one more, so a fast drag never
 * queues a backlog of binder calls behind the finger.
 *
 * Main thread only, apart from what runs on [io].
 */
internal class CaretMagnifierController(
    private val scope: CoroutineScope,
    reader: CoroutineDispatcher = Dispatchers.Default,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private val io = reader.limitedParallelism(1, "wmkb-magnifier")
    private val _state = MutableStateFlow<CaretMagnifierState?>(null)
    val state: StateFlow<CaretMagnifierState?> = _state.asStateFlow()

    private val sources = mutableSetOf<CaretDragSource>()
    private var connection: InputConnection? = null
    private var mask = false
    private var anchor: CaretAnchor? = null

    /** Off, no anchor is asked for and the bubble stays above the keyboard, where it goes without one (#1038). */
    var followsText = true
    private var selStart = -1
    private var selEnd = -1
    // The offset of the end that is moving: the caret the bubble centres on.
    private var focus = -1
    private var reading = false
    private var readAgain = false
    // Bumped whenever the session ends or changes field, so a read that
    // started before it can recognise itself as stale on the way back.
    private var session = 0

    /** Whether a drag holds the bubble up right now. */
    val active: Boolean get() = sources.isNotEmpty()

    /**
     * [source] started moving the caret in the field behind [ic]. [mask] draws
     * the line as bullets, for a field that hides what is typed.
     */
    fun begin(source: CaretDragSource, ic: InputConnection, selStart: Int, selEnd: Int, mask: Boolean) {
        val first = sources.isEmpty()
        if (!sources.add(source) || !first) return
        this.selStart = selStart
        this.selEnd = selEnd
        focus = selEnd
        attach(ic, mask)
    }

    /**
     * The field behind the drag changed connection (it restarted, or focus
     * moved) while a finger still holds the bubble: follow it there. The old
     * connection is dead, so there is nothing to switch off on it.
     */
    fun rebind(ic: InputConnection?, selStart: Int, selEnd: Int, mask: Boolean) {
        if (sources.isEmpty()) return
        if (ic == null) {
            stop()
            return
        }
        this.selStart = selStart
        this.selEnd = selEnd
        focus = selEnd
        attach(ic, mask)
    }

    private fun attach(ic: InputConnection, mask: Boolean) {
        session++
        connection = ic
        this.mask = mask
        anchor = null
        reading = false
        readAgain = false
        _state.value = null
        var flags = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
        // Only the insertion marker is drawn from: left unfiltered, Android 13+
        // editors also send every character's and every visible line's bounds
        // on each move of the caret.
        if (sdk >= Build.VERSION_CODES.TIRAMISU) flags = flags or InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
        if (followsText) scope.launch(io) { runCatching { ic.requestCursorUpdates(flags) } }
        read()
    }

    /** [source] let go. The bubble stays while another source still holds it. */
    fun end(source: CaretDragSource) {
        if (sources.remove(source) && sources.isEmpty()) stop()
    }

    /** Every source at once: the field or the keyboard went away. */
    fun stop() {
        sources.clear()
        session++
        reading = false
        readAgain = false
        val ic = connection
        connection = null
        anchor = null
        _state.value = null
        if (ic != null) scope.launch(io) { runCatching { ic.requestCursorUpdates(0) } }
    }

    /**
     * The field reported a new selection. The moving end is the one that
     * changed: the end when both did, as a caret step moves both.
     */
    fun onSelection(newSelStart: Int, newSelEnd: Int) {
        if (sources.isEmpty()) return
        if (newSelStart == selStart && newSelEnd == selEnd && _state.value != null) return
        focus = when {
            newSelEnd != selEnd -> newSelEnd
            newSelStart != selStart -> newSelStart
            else -> focus
        }
        selStart = newSelStart
        selEnd = newSelEnd
        read()
    }

    /** The field said where its caret is now. */
    fun onCursorAnchor(info: CursorAnchorInfo) {
        if (!followsText) return
        if (sources.isEmpty()) return
        val next = caretAnchorOf(info)
        if (next == anchor) return
        anchor = next
        _state.value?.let { _state.value = it.copy(anchor = next) }
    }

    /**
     * Reads the text around the selection on [io]. At most one read is in
     * flight; a move while it is becomes one more read once it lands, taken
     * against the selection as it stands then.
     */
    private fun read() {
        val ic = connection ?: return
        if (reading) {
            readAgain = true
            return
        }
        reading = true
        readAgain = false
        val start = selStart
        val end = selEnd
        // A collapsed or unknown selection has one end, and it is the caret.
        val atEnd = start == end || focus == maxOf(start, end)
        val masked = mask
        val mine = session
        scope.launch {
            val line = withContext(io) {
                runCatching { readMagnifierLine(ic, start, end, atEnd, sdk) }.getOrNull()
            }
            // Back on the main thread, where [stop] and [rebind] run: either
            // one since this started makes it stale, and it already let go of
            // [reading] for the session that replaced this one.
            if (mine != session) return@launch
            reading = false
            if (line != null) _state.value = CaretMagnifierState(if (masked) line.masked() else line, anchor)
            if (readAgain) read()
        }
    }
}

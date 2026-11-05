package com.wasimaster.wmkeyboard.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CONTENT_PAD = 10.dp
private val FIELD_PAD = 10.dp
private val CARET_PAD = 24.dp
private val NUMBER_PAD = 10.dp

/** The strip at the right of the gutter the fold chevrons sit in. */
private val FOLD_GUTTER = 14.dp

/** Under this many lines of room, suggestions go to the key row rather than a list at the caret. */
private const val COMPACT_LINES = 3

/** How long a fold chevron takes to turn, and a fold's band to fade. */
private const val CHEVRON_TURN_MS = 180
private const val FOLD_FLASH_MS = 450
private const val FOLD_FLASH_ALPHA = 0.35f

/** At most this many squiggles are drawn in one frame, however many a broken file has. */
private const val MAX_SQUIGGLES = 200

/**
 * Colours past this many are dropped and the file is shown plain.
 *
 * The field turns every colour into a span on an Android SpannableString, and
 * each span added there scans every span already on it, so the cost grows with
 * the square of the count. A large layout or backup JSON runs to tens of
 * thousands of tokens, and laying it out took the main thread long enough for
 * an "input dispatching timed out" ANR inside SpannableString.setSpan.
 */
private const val MAX_HIGHLIGHT_SPANS = 4_000

/** Characters past which colouring is not even worked out: the spans would be dropped anyway. */
private const val MAX_HIGHLIGHT_CHARS = 200_000

/** [language]'s colours for [text], or [text] plain when there would be too many of them. */
internal fun boundedHighlight(language: CodeLanguage, text: String, colors: CodeColors): AnnotatedString {
    if (text.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(text)
    val coloured = language.highlight(text, colors)
    return if (coloured.spanStyles.size > MAX_HIGHLIGHT_SPANS) AnnotatedString(text) else coloured
}

/**
 * Everything drawn under or over the text without touching its colours.
 *
 * All of it is drawn on the underlay rather than spanned into the text, for the
 * reason the bracket boxes always were: a span means rebuilding the coloured
 * document, and a find that recoloured the whole file on every letter of the
 * query, or a diagnostics pass that did it after every pause in typing, would
 * make the field fall behind the keyboard. Drawing also leaves the identity
 * offset mapping alone, which is what puts the caret where a finger lands.
 */
@Immutable
internal data class CodeDecorations(
    val matches: List<TextRange> = emptyList(),
    /** The index in [matches] of the one find is sitting on, or -1. */
    val activeMatch: Int = -1,
    val squiggles: List<CodeDiagnostic> = emptyList(),
    /** Line index to the worst severity on it; the number is drawn in that colour. */
    val gutterMarks: Map<Int, CodeSeverity> = emptyMap(),
) {
    companion object {
        val None = CodeDecorations()
    }
}

/** A code field is not prose: no capitals at line starts and no corrections. */
private val CodeKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
)

/**
 * Asks the keyboard for no suggestion strip of its own. [CodeKeyboardOptions]
 * has no word for that: autocorrect off still leaves the keyboard guessing
 * English words over the list the editor shows at the caret.
 */
@OptIn(ExperimentalComposeUiApi::class)
private val NoSuggestions = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = nextHandler.startInputMethod(
        object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                request.createInputConnection(outAttributes).also {
                    outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
        },
    )
}

/**
 * The editor field on its own: a monospaced field on a code background, with
 * numbered lines, the caret's line lit, matching brackets boxed, and whatever
 * [decorations] asks for drawn underneath. No toolbar, no status line and no
 * height of its own: the plugin editor and the layout JSON editor each give it
 * the whole window, and draw their own bars around it.
 *
 * Long lines run off to the right and scroll rather than wrap, which is what
 * keeps one number against one line. [wrap] turns that off for a narrow screen,
 * and the numbers stay right either way: they are drawn at the y each line
 * actually landed at, from the field's own layout, rather than at a multiple of
 * an assumed line height.
 */
@Composable
internal fun CodeSurface(
    state: CodeEditorState,
    language: CodeLanguage,
    modifier: Modifier = Modifier,
    colors: CodeColors = rememberCodeColors(),
    lineStarts: List<Int> = remember(state.text) { lineStartOffsets(state.text) },
    decorations: CodeDecorations = CodeDecorations.None,
    wrap: Boolean = false,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    bottomPad: Dp = CONTENT_PAD,
    /**
     * Runs a screen command a key asked for, such as Ctrl+F or F5, and says whether
     * the screen has one. The field runs the editing commands itself.
     */
    onCommand: (CodeCommand) -> Boolean = { false },
    /** Lets the screen give the keys back to the field, as closing the find bar with Esc does. */
    focusRequester: FocusRequester? = null,
    /** Whether to ask [language] for suggestions as the author types, and list them at the caret. */
    completions: Boolean = false,
    /** Raised by one to ask for suggestions at the caret even with no word typed, as Ctrl+Space does. */
    suggestRequests: Int = 0,
    /** Hears a tap on a line number, with the line's index. Null leaves the numbers inert. */
    onGutterPress: ((Int) -> Unit)? = null,
    /** Whether blocks fold from chevrons in the gutter, over [CodeLanguage.foldRegions]. */
    folding: Boolean = false,
    /** Where suggestions go when the field is too short to list them at the caret. Null keeps them at the caret. */
    suggestionBar: CodeSuggestionBar? = null,
    /** Snaps folds instead of animating them. */
    reduceMotion: Boolean = false,
) {
    val density = LocalDensity.current
    // Held as the state object rather than read through it: the two draw
    // blocks below read `.value` inside the draw phase, so a new text layout
    // repaints the margin and the underlay without recomposing the editor.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    val style = remember(colors, fontSize, lineHeight) {
        TextStyle(fontFamily = CodeFontFamily, fontSize = fontSize, lineHeight = lineHeight, color = colors.text)
    }
    val text = state.text
    val caret = state.value.selection.end.coerceIn(0, text.length)
    val caretLine = lineOf(lineStarts, caret)
    // Everything below is keyed on the text alone. Only `match` follows the
    // caret, and it walks a list of offsets rather than the document.
    val brackets = remember(text, language) { language.brackets(text) }
    val match = remember(brackets, caret) { language.matchingBracket(text, brackets, caret) }
    val coloured = remember(text, colors, language) { boundedHighlight(language, text, colors) }
    // Folding. The field shows the document with folded blocks swapped for a
    // placeholder, through an offset mapping; everything drawn from the layout
    // below goes through the same mapping.
    val regions = remember(text, language, folding) { if (folding) language.foldRegions(text) else emptyList() }
    val folds = remember(text, regions, state.foldStarts) { foldsFor(text, regions, state.foldStarts) }
    val foldMap = remember(folds, text) { if (folds.isEmpty()) null else FoldMap(folds, text.length) }
    val painter = remember(coloured, folds, colors) {
        CodeHighlight(coloured, folds, foldMap ?: OffsetMapping.Identity, SpanStyle(color = colors.foldMark))
    }
    val foldable = remember(text, regions, lineStarts) { if (folding) foldableStarts(text, regions, lineStarts) else emptyMap() }
    val shownCaret = foldMap?.originalToTransformed(caret) ?: caret
    val shownMatch = match?.let { (a, b) -> foldMap?.let { it.originalToTransformed(a) to it.originalToTransformed(b) } ?: match }
    val shownDecorations = remember(decorations, foldMap) { foldMap?.let { decorations.through(it) } ?: decorations }
    // Each chevron turns between open and folded instead of jumping, and the lines a
    // fold just hid or brought back are marked by a band that fades.
    val chevronTurns = remember { mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(foldable, folds, reduceMotion) {
        val live = foldable.values.toSet()
        chevronTurns.keys.filter { it !in live }.forEach { chevronTurns.remove(it) }
        val folded = folds.mapTo(HashSet()) { it.start }
        for (start in live) {
            val target = if (start in folded) 1f else 0f
            val turn = chevronTurns.getOrPut(start) { Animatable(target) }
            if (turn.value != target) {
                launch { if (reduceMotion) turn.snapTo(target) else turn.animateTo(target, tween(CHEVRON_TURN_MS)) }
            }
        }
    }
    val flash = remember { Animatable(0f) }
    var flashRange by remember { mutableStateOf<TextRange?>(null) }
    LaunchedEffect(state.foldEvent) {
        val event = state.foldEvent ?: return@LaunchedEffect
        if (reduceMotion) return@LaunchedEffect
        val region = regions.filter { it.min == event.start }.maxByOrNull { it.max } ?: return@LaunchedEffect
        val hidden = hiddenRangeOf(text, region) ?: return@LaunchedEffect
        flashRange = if (event.folded) TextRange(region.min, hidden.min) else TextRange(hidden.min + 1, hidden.max)
        flash.snapTo(FOLD_FLASH_ALPHA)
        flash.animateTo(0f, tween(FOLD_FLASH_MS))
    }
    var focused by remember { mutableStateOf(false) }
    // Raised when the caret has to be found again without having moved.
    var revealAgain by remember { mutableIntStateOf(0) }
    // A caret or a selection that lands in folded text, typed, found or jumped to, opens the fold.
    LaunchedEffect(state.value.selection, folds) {
        val selection = state.value.selection
        folds.firstOrNull { fold ->
            (selection.min > fold.hidden.min && selection.min < fold.hidden.max) ||
                (selection.max > fold.hidden.min && selection.max < fold.hidden.max)
        }?.let {
            state.unfold(it.start)
            // The caret was just scrolled to the folded line; the lines it opened can push it out of sight.
            revealAgain++
        }
    }

    // Suggestions live here rather than with the caller, so the list can sit at
    // the caret. completionStep decides what each change of text or caret does.
    var suggestions by remember { mutableStateOf<CodeCompletions?>(null) }
    var chosen by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(text) }
    // Choosing writes text, and that change must not open a list of its own.
    val justChose = remember { booleanArrayOf(false) }
    LaunchedEffect(text, caret, completions) {
        val before = seen
        seen = text
        if (!completions || justChose[0]) {
            justChose[0] = false
            suggestions = null
            return@LaunchedEffect
        }
        when (completionStep(before, text, caret, suggestions)) {
            CompletionStep.ASK -> {
                suggestions = withContext(Dispatchers.Default) { language.completions(text, caret, explicit = false) }
                chosen = 0
            }
            CompletionStep.KEEP -> Unit
            CompletionStep.CLOSE -> suggestions = null
        }
    }
    // Raised by a chosen suggestion whose blank has suggestions of its own.
    var reopenRequests by remember { mutableIntStateOf(0) }
    val choose: (CodeCompletion) -> Unit = remember(state) {
        { item ->
            suggestions?.let { shown ->
                suggestions = null
                justChose[0] = true
                state.applyEdit(shown.editFor(item))
                if (item.reopen) reopenRequests++
            }
        }
    }
    LaunchedEffect(reopenRequests) {
        if (!completions || reopenRequests == 0) return@LaunchedEffect
        // One frame, so the effect above has seen the chosen text and let it pass.
        withFrameNanos { }
        val source = state.text
        val at = state.value.selection.end
        suggestions = withContext(Dispatchers.Default) { language.completions(source, at, explicit = true) }
        chosen = 0
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(suggestRequests) {
        if (!completions || suggestRequests == 0) return@LaunchedEffect
        val source = state.text
        val at = state.value.selection.end
        suggestions = withContext(Dispatchers.Default) { language.completions(source, at, explicit = true) }
        chosen = 0
    }
    // Wrapped text has nothing to the right, so a stale sideways scroll would
    // only hide the left margin.
    LaunchedEffect(wrap) { if (wrap) horizontal.scrollTo(0) }

    val measurer = rememberTextMeasurer(cacheSize = 64)
    val digits = lineStarts.size.toString().length
    val gutterWidth = remember(digits, style, density, folding) {
        with(density) { measurer.measure(AnnotatedString("0".repeat(digits)), style).size.width.toDp() + 20.dp } +
            if (folding) FOLD_GUTTER else 0.dp
    }
    val charWidth = remember(style, density) {
        with(density) { measurer.measure(AnnotatedString("0"), style).size.width.toDp() }
    }
    val longest = remember(text) { text.lineSequence().maxOf { it.length } }

    val shape = RoundedCornerShape(12.dp)
    // A plain Box with its size reported back, rather than
    // BoxWithConstraints: that one subcomposes its content on every measure
    // pass, and this content is a text field holding the whole document.
    var frame by remember { mutableStateOf(IntSize.Zero) }
    val compact = suggestionBar != null && frame.height > 0 &&
        frame.height < with(density) { lineHeight.toPx() * COMPACT_LINES + (CONTENT_PAD * 2).toPx() }
    LaunchedEffect(suggestionBar, compact, suggestions) { suggestionBar?.publish(if (compact) suggestions else null, choose) }
    // The field is as tall as the whole document and the frame scrolls it, so
    // nothing else keeps the caret in sight: not typing past the bottom edge,
    // not a jump from another pane, and not the keyboard taking half the frame.
    // Only a caret that moved, or a frame that changed size under a focused
    // field, scrolls; a finger that scrolled away is otherwise left alone.
    suspend fun revealCaret() {
        // One frame, so the layout and the scroll range have caught up with the text.
        withFrameNanos { }
        val result = layout.value ?: return
        val box = result.getCursorRect(shownCaret.coerceIn(0, result.layoutInput.text.length))
        val top = with(density) { CONTENT_PAD.toPx() }
        revealScroll(vertical.value, frame.height, top + box.top, top + box.bottom, with(density) { lineHeight.toPx() })
            ?.let { vertical.scrollTo(it.coerceAtMost(vertical.maxValue)) }
        if (!wrap) {
            val room = frame.width - with(density) { (gutterWidth + FIELD_PAD + FIELD_PAD).roundToPx() }
            val char = with(density) { charWidth.toPx() }
            revealScroll(horizontal.value, room, box.left, box.left + char, char * 2)
                ?.let { horizontal.scrollTo(it.coerceAtMost(horizontal.maxValue)) }
        }
    }
    LaunchedEffect(caret, text, revealAgain) { revealCaret() }
    LaunchedEffect(frame.height) { if (focused) revealCaret() }

    // Keys. The handler is made once and reads the newest of everything through
    // these, so the field is not handed a new one on every change of the text.
    val latestRegions by rememberUpdatedState(regions)
    val latestBrackets by rememberUpdatedState(brackets)
    val latestFoldMap by rememberUpdatedState(foldMap)
    val latestOnCommand by rememberUpdatedState(onCommand)
    val latestLineHeight by rememberUpdatedState(with(density) { lineHeight.toPx() })
    val context = LocalContext.current
    val keys: (KeyEvent) -> Boolean = remember(language, completions, state) {
        val chords = CodeKeyChords()
        // The line cut or copy took with nothing selected, so paste can put it back as a line.
        var lineClip: String? = null
        // The selections Shift+Alt+Right grew from, and the one it grew to last, for Shift+Alt+Left.
        val grownFrom = ArrayList<TextRange>()
        var grownTo: TextRange? = null

        fun moveCaret(to: Int, select: Boolean) {
            val selection = state.value.selection
            state.select(if (select) TextRange(selection.start, to) else TextRange(to))
        }

        // The field is as tall as the document, so its own Page Up and Page Down go
        // to the ends. A page here is the frame the code is seen through.
        fun page(down: Boolean, select: Boolean): Boolean {
            val result = layout.value ?: return false
            val map = latestFoldMap
            val at = state.value.selection.end.coerceIn(0, state.text.length)
            val box = result.getCursorRect((map?.originalToTransformed(at) ?: at).coerceIn(0, result.layoutInput.text.length))
            val step = (frame.height - latestLineHeight).coerceAtLeast(latestLineHeight) * if (down) 1 else -1
            val y = (box.center.y + step).coerceIn(0f, (result.size.height - 1).coerceAtLeast(0).toFloat())
            val landed = result.getOffsetForPosition(Offset(box.left, y))
            moveCaret(map?.transformedToOriginal(landed) ?: landed, select)
            vertical.dispatchRawDelta(step)
            return true
        }

        fun runCommand(command: CodeCommand): Boolean {
            val text = state.text
            val selection = state.value.selection
            val caret = selection.end.coerceIn(0, text.length)
            when (command) {
                CodeCommand.INDENT ->
                    if (tabShiftsLines(text, selection)) state.shiftLines(1) else state.applyEdit(insertIndent(text, selection))
                CodeCommand.OUTDENT, CodeCommand.OUTDENT_LINES -> state.shiftLines(-1)
                CodeCommand.INDENT_LINES -> state.shiftLines(1, wholeLines = true)
                CodeCommand.UNDO -> state.undo()
                CodeCommand.REDO -> state.redo()
                CodeCommand.TOGGLE_LINE_COMMENT -> {
                    val marker = language.lineComment ?: return false
                    state.applyEdit(toggleLineComment(text, selection, marker))
                }
                CodeCommand.TOGGLE_BLOCK_COMMENT -> {
                    val (open, close) = language.blockComment ?: return false
                    state.applyEdit(toggleBlockComment(text, selection, open, close))
                }
                CodeCommand.MOVE_LINES_UP -> moveLines(text, selection, -1)?.let(state::applyEdit)
                CodeCommand.MOVE_LINES_DOWN -> moveLines(text, selection, 1)?.let(state::applyEdit)
                CodeCommand.COPY_LINES_UP -> state.applyEdit(copyLines(text, selection, up = true))
                CodeCommand.COPY_LINES_DOWN -> state.applyEdit(copyLines(text, selection, up = false))
                CodeCommand.DELETE_LINES -> state.applyEdit(deleteLines(text, selection))
                CodeCommand.INSERT_LINE_BELOW -> state.applyEdit(insertLine(text, selection, above = false))
                CodeCommand.INSERT_LINE_ABOVE -> state.applyEdit(insertLine(text, selection, above = true))
                CodeCommand.CUT, CodeCommand.COPY -> {
                    if (!selection.collapsed) {
                        lineClip = null
                        return false
                    }
                    val clip = lineText(text, caret)
                    copyCode(context, clip)
                    lineClip = clip
                    if (command == CodeCommand.CUT) state.applyEdit(deleteLines(text, selection))
                }
                CodeCommand.PASTE -> {
                    val clip = lineClip
                    if (clip == null || !selection.collapsed || pasteCode(context) != clip) return false
                    state.applyEdit(pasteLines(text, caret, clip))
                }
                CodeCommand.HOME, CodeCommand.SELECT_HOME -> moveCaret(smartHome(text, caret), command == CodeCommand.SELECT_HOME)
                CodeCommand.DOCUMENT_START, CodeCommand.SELECT_DOCUMENT_START ->
                    moveCaret(0, command == CodeCommand.SELECT_DOCUMENT_START)
                CodeCommand.DOCUMENT_END, CodeCommand.SELECT_DOCUMENT_END ->
                    moveCaret(text.length, command == CodeCommand.SELECT_DOCUMENT_END)
                CodeCommand.PAGE_UP, CodeCommand.SELECT_PAGE_UP -> return page(down = false, select = command == CodeCommand.SELECT_PAGE_UP)
                CodeCommand.PAGE_DOWN, CodeCommand.SELECT_PAGE_DOWN -> return page(down = true, select = command == CodeCommand.SELECT_PAGE_DOWN)
                CodeCommand.SCROLL_LINE_UP -> vertical.dispatchRawDelta(-latestLineHeight)
                CodeCommand.SCROLL_LINE_DOWN -> vertical.dispatchRawDelta(latestLineHeight)
                CodeCommand.SELECT_LINE -> state.select(expandLineSelection(text, selection))
                CodeCommand.EXPAND_SELECTION -> expandSelection(text, selection, latestRegions)?.let { next ->
                    if (grownTo != selection) grownFrom.clear()
                    grownFrom += selection
                    grownTo = next
                    state.select(next)
                }
                CodeCommand.SHRINK_SELECTION -> if (grownTo == selection && grownFrom.isNotEmpty()) {
                    val back = grownFrom.removeAt(grownFrom.lastIndex)
                    grownTo = back
                    state.select(back)
                }
                CodeCommand.JUMP_TO_BRACKET -> {
                    val brackets = latestBrackets
                    val to = bracketJump(text, brackets, caret) { at -> language.matchingBracket(text, brackets, at) } ?: return false
                    state.moveTo(to)
                }
                CodeCommand.FOLD -> foldToClose(text, latestRegions, state.foldStarts, caret)?.let { region ->
                    // The caret leaves the lines the fold hides, or the fold would open again round it.
                    val hidden = hiddenRangeOf(text, region)
                    if (hidden != null && selection.max > hidden.min && selection.min < hidden.max) state.moveTo(hidden.min)
                    state.toggleFold(region.min)
                }
                CodeCommand.UNFOLD -> foldToOpen(text, latestRegions, state.foldStarts, caret)?.let(state::unfold)
                CodeCommand.FOLD_ALL -> state.foldAll(latestRegions.map { it.min })
                CodeCommand.UNFOLD_ALL -> state.unfoldAll()
                CodeCommand.SUGGEST -> {
                    if (!completions) return false
                    scope.launch {
                        suggestions = withContext(Dispatchers.Default) { language.completions(text, caret, explicit = true) }
                        chosen = 0
                    }
                }
                else -> return false
            }
            return true
        }

        keys@{ event ->
            val shown = suggestions
            if (event.type == KeyEventType.KeyDown && shown != null && shown.items.isNotEmpty()) {
                val size = shown.items.size
                when (event.key) {
                    Key.DirectionDown -> { chosen = (chosen + 1) % size; return@keys true }
                    Key.DirectionUp -> { chosen = (chosen - 1 + size) % size; return@keys true }
                    Key.Enter, Key.Tab -> { shown.items.getOrNull(chosen)?.let(choose); return@keys true }
                    Key.Escape -> { suggestions = null; return@keys true }
                }
            }
            // Esc is taken on its way up as well: one let through would come back from
            // the system as Back and close the editor.
            if (event.type != KeyEventType.KeyDown) return@keys event.key == Key.Escape
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            when (val command = chords.map(event.key, ctrl, event.isShiftPressed, event.isAltPressed)) {
                null -> false
                CodeCommand.CHORD -> true
                CodeCommand.ESCAPE -> {
                    latestOnCommand(command)
                    true
                }
                else -> if (command.scope == CodeCommand.Scope.SCREEN) latestOnCommand(command) else runCommand(command)
            }
        }
    }
    Box(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(1.dp, colors.border, shape)
            .onSizeChanged { frame = it },
    ) {
        val viewport = frame.height
        val room = with(density) { frame.width.toDp() } - gutterWidth - FIELD_PAD - FIELD_PAD
        val contentWidth = maxOf(room, charWidth * (longest + if (folds.isEmpty()) 0 else FOLD_PLACEHOLDER.length) + CARET_PAD)

        Box(Modifier.width(gutterWidth).fillMaxHeight().background(colors.gutter))
        Box(Modifier.offset(x = gutterWidth).width(1.dp).fillMaxHeight().background(colors.border))
        EditorUnderlay(
            layout = layout,
            caret = shownCaret,
            match = shownMatch,
            decorations = shownDecorations,
            flash = {
                val range = flashRange
                val alpha = flash.value
                if (range == null || alpha <= 0f) {
                    null
                } else {
                    val map = foldMap
                    TextRange(map?.originalToTransformed(range.min) ?: range.min, map?.originalToTransformed(range.max) ?: range.max) to alpha
                }
            },
            textLeft = gutterWidth + FIELD_PAD,
            vertical = vertical,
            horizontal = horizontal,
            colors = colors,
        )

        // The margin is painted behind the row rather than laid out beside
        // it, so the row's height is the field's own and nothing has to
        // read the text layout during composition.
        Row(
            Modifier
                .verticalScroll(vertical)
                .padding(top = CONTENT_PAD, bottom = bottomPad)
                .pointerInput(onGutterPress, lineStarts, gutterWidth, foldable, foldMap) {
                    val press = onGutterPress
                    if (press == null && foldable.isEmpty()) return@pointerInput
                    // The field takes the taps that land on text; what reaches here
                    // on the left is a tap on the numbers or on a fold chevron.
                    detectTapGestures { tap ->
                        if (tap.x >= gutterWidth.toPx()) return@detectTapGestures
                        val result = layout.value ?: return@detectTapGestures
                        val shownStart = result.getLineStart(result.getLineForVerticalPosition(tap.y))
                        val line = lineOf(lineStarts, foldMap?.transformedToOriginal(shownStart) ?: shownStart)
                        val foldStart = foldable[line]
                        if (foldStart != null && tap.x >= (gutterWidth - FOLD_GUTTER).toPx()) {
                            state.toggleFold(foldStart)
                        } else {
                            press?.invoke(line)
                        }
                    }
                }
                .drawBehind {
                    drawLineNumbers(
                        layout = layout.value,
                        lineStarts = lineStarts,
                        activeLine = caretLine,
                        marks = decorations.gutterMarks,
                        right = gutterWidth.toPx() - NUMBER_PAD.toPx() - if (folding) FOLD_GUTTER.toPx() else 0f,
                        colors = colors,
                        style = style,
                        measurer = measurer,
                        scroll = vertical.value,
                        viewport = viewport,
                        map = foldMap,
                        folds = folds,
                        foldable = foldable,
                        folded = state.foldStarts,
                        turn = { start -> chevronTurns[start]?.value ?: if (folds.any { it.start == start }) 1f else 0f },
                        chevronLeft = if (folding) gutterWidth.toPx() - FOLD_GUTTER.toPx() else -1f,
                    )
                },
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(Modifier.width(gutterWidth))
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = FIELD_PAD)
                    .horizontalScroll(horizontal, enabled = !wrap),
            ) {
                // Wrapping is the width, not a flag: a field as wide as its
                // longest line has nothing to wrap, and one as wide as the
                // viewport wraps everything. The legacy field has no
                // softWrap of its own.
                CodeField(state, style, colors, painter, if (wrap) room else contentWidth, keys, focusRequester, onFocus = { focused = it }) {
                    layout.value = it
                }
            }
        }

        suggestions?.takeIf { it.items.isNotEmpty() && !compact }?.let { shown ->
            var listSize by remember { mutableStateOf(IntSize.Zero) }
            CodeCompletionList(
                completions = shown,
                chosen = chosen,
                colors = colors,
                style = style,
                onChoose = choose,
                modifier = Modifier
                    .onSizeChanged { listSize = it }
                    .offset {
                        // Placed in the layout phase, so scrolling moves the list
                        // without recomposing the editor. Below the caret when it
                        // fits, above it when the keyboard has taken the room.
                        val result = layout.value ?: return@offset IntOffset.Zero
                        val rect = result.getCursorRect(shownCaret.coerceIn(0, result.layoutInput.text.length))
                        val left = (gutterWidth + FIELD_PAD).roundToPx() - horizontal.value
                        val top = CONTENT_PAD.roundToPx() - vertical.value
                        val below = top + rect.bottom.roundToInt()
                        val above = top + rect.top.roundToInt() - listSize.height
                        val y = if (below + listSize.height <= frame.height || above < 0) below else above
                        val x = (left + rect.left.roundToInt()).coerceIn(0, maxOf(0, frame.width - listSize.width))
                        IntOffset(x, y)
                    },
            )
        }
    }
}

/**
 * Paints the document that was coloured once per change, with each folded
 * block swapped for its placeholder through [map]. With nothing folded it is
 * the coloured document character for character.
 */
private class CodeHighlight(
    coloured: AnnotatedString,
    private val folds: List<CodeFold>,
    private val map: OffsetMapping,
    placeholder: SpanStyle,
) : VisualTransformation {
    private val shown = foldedText(coloured, folds, placeholder)

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(shown, if (folds.isEmpty()) OffsetMapping.Identity else map)
}

/** Decorations placed on the folded text: ranges moved through [map], line marks left by line. */
private fun CodeDecorations.through(map: OffsetMapping): CodeDecorations = copy(
    matches = matches.map { TextRange(map.originalToTransformed(it.min), map.originalToTransformed(it.max)) },
    squiggles = squiggles.map {
        it.copy(range = TextRange(map.originalToTransformed(it.range.min), map.originalToTransformed(it.range.max)))
    },
)

/**
 * Where a scroll at [scroll] over a [viewport] has to move to show the span from
 * [start] to [end], leaving [margin] beyond it where the viewport has the room,
 * or null when the span is in sight already. A span taller than the viewport is
 * shown from its start.
 */
internal fun revealScroll(scroll: Int, viewport: Int, start: Float, end: Float, margin: Float): Int? {
    if (viewport <= 0) return null
    val size = end - start
    val target = when {
        size >= viewport -> start.roundToInt()
        else -> {
            val spare = margin.coerceIn(0f, (viewport - size) / 2f)
            when {
                start - spare < scroll -> (start - spare).roundToInt()
                end + spare > scroll + viewport -> (end + spare - viewport).roundToInt()
                else -> return null
            }
        }
    }.coerceAtLeast(0)
    return target.takeIf { it != scroll }
}

/** The field itself. Split out to keep the editor's own tree readable. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CodeField(
    state: CodeEditorState,
    style: TextStyle,
    colors: CodeColors,
    painter: VisualTransformation,
    width: Dp,
    onKey: (KeyEvent) -> Boolean,
    focusRequester: FocusRequester?,
    onFocus: (Boolean) -> Unit,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val selection = remember(colors) {
        TextSelectionColors(handleColor = colors.handle, backgroundColor = colors.selection)
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        InterceptPlatformTextInput(NoSuggestions) {
            BasicTextField(
                value = state.value,
                onValueChange = state::edit,
                textStyle = style,
                cursorBrush = SolidColor(colors.caret),
                visualTransformation = painter,
                onTextLayout = onLayout,
                keyboardOptions = CodeKeyboardOptions,
                modifier = Modifier
                    .width(width)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { onFocus(it.isFocused) }
                    .onPreviewKeyEvent(onKey),
            )
        }
    }
}

/**
 * What sits under the text: the caret's line lit across the whole editor,
 * gutter included, find matches, a box around each half of the bracket pair,
 * and a squiggle under each diagnostic.
 */
@Composable
private fun BoxScope.EditorUnderlay(
    layout: State<TextLayoutResult?>,
    caret: Int,
    match: Pair<Int, Int>?,
    decorations: CodeDecorations,
    flash: () -> Pair<TextRange, Float>?,
    textLeft: Dp,
    vertical: ScrollState,
    horizontal: ScrollState,
    colors: CodeColors,
) {
    val density = LocalDensity.current
    // The layout and the scroll positions are read here, inside the draw block,
    // so a drag or a fresh layout repaints without recomposing anything.
    Canvas(Modifier.matchParentSize()) {
        val result = layout.value ?: return@Canvas
        val top = with(density) { CONTENT_PAD.toPx() } - vertical.value
        val left = with(density) { textLeft.toPx() } - horizontal.value
        val length = result.layoutInput.text.length
        if (caret <= length) {
            val line = result.getLineForOffset(caret)
            val y = result.getLineTop(line) + top
            val height = result.getLineBottom(line) - result.getLineTop(line)
            if (y + height > 0f && y < size.height) {
                drawRect(color = colors.activeLine, topLeft = Offset(0f, y), size = Size(size.width, height))
            }
        }
        flash()?.let { (range, alpha) ->
            val first = result.getLineForOffset(range.min.coerceIn(0, length))
            val last = result.getLineForOffset(range.max.coerceIn(0, length))
            val bandTop = result.getLineTop(first) + top
            val bandBottom = result.getLineBottom(last) + top
            drawRect(color = colors.foldMark.copy(alpha = alpha), topLeft = Offset(0f, bandTop), size = Size(size.width, bandBottom - bandTop))
        }
        drawMatches(result, decorations, left, top, colors)
        if (match != null) {
            for (offset in listOf(match.first, match.second)) {
                if (offset >= length) continue
                val box = result.getBoundingBox(offset)
                drawRect(
                    color = colors.bracketMatch,
                    topLeft = Offset(box.left + left, box.top + top),
                    size = Size(box.width, box.height),
                )
            }
        }
        drawSquiggles(result, decorations, left, top, colors, with(density) { 1.5.dp.toPx() })
    }
}

/**
 * Every find match that could be on screen. getPathForRange gives one path for
 * a range however many lines and wraps it crosses, so a multi-line hit needs no
 * arithmetic here.
 */
private fun DrawScope.drawMatches(
    result: TextLayoutResult,
    decorations: CodeDecorations,
    left: Float,
    top: Float,
    colors: CodeColors,
) {
    val length = result.layoutInput.text.length
    for ((index, hit) in decorations.matches.withIndex()) {
        val start = hit.min.coerceIn(0, length)
        val end = hit.max.coerceIn(start, length)
        if (end <= start) continue
        val firstTop = result.getLineTop(result.getLineForOffset(start)) + top
        if (firstTop > size.height) break
        if (result.getLineBottom(result.getLineForOffset(end)) + top < 0f) continue
        translate(left, top) {
            drawPath(
                path = result.getPathForRange(start, end),
                color = if (index == decorations.activeMatch) colors.findActive else colors.findMatch,
            )
        }
    }
}

/** A zigzag under each diagnostic, one per visual line so a wrapped one does not jump the gap. */
@Suppress("LongParameterList")
private fun DrawScope.drawSquiggles(
    result: TextLayoutResult,
    decorations: CodeDecorations,
    left: Float,
    top: Float,
    colors: CodeColors,
    stroke: Float,
) {
    val length = result.layoutInput.text.length
    for (diagnostic in decorations.squiggles.take(MAX_SQUIGGLES)) {
        val start = diagnostic.range.min.coerceIn(0, length)
        val end = diagnostic.range.max.coerceIn(start, length)
        val colour = when (diagnostic.severity) {
            CodeSeverity.ERROR -> colors.problem
            CodeSeverity.WARNING -> colors.warning
            CodeSeverity.INFO -> colors.gutterText
        }
        val firstLine = result.getLineForOffset(start)
        val lastLine = result.getLineForOffset(if (end > start) end - 1 else start)
        for (line in firstLine..lastLine) {
            val y = result.getLineBottom(line) - stroke + top
            if (y < 0f) continue
            if (y > size.height) return
            val from = maxOf(start, result.getLineStart(line))
            val to = minOf(end, result.getLineEnd(line))
            val x0 = result.getHorizontalPosition(from, true) + left
            var x1 = result.getHorizontalPosition(to, true) + left
            // A diagnostic at one offset, or on an empty stretch, still gets a mark.
            if (x1 - x0 < stroke * 4) x1 = x0 + stroke * 6
            drawSquiggle(x0, x1, y, colour, stroke)
        }
    }
}

private fun DrawScope.drawSquiggle(x0: Float, x1: Float, y: Float, color: Color, stroke: Float) {
    val step = stroke * 2f
    val path = Path()
    path.moveTo(x0, y)
    var x = x0
    var up = true
    while (x < x1) {
        x = minOf(x + step, x1)
        path.lineTo(x, if (up) y - step else y)
        up = !up
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

/**
 * The line numbers, right-aligned at [right]. Only the ones the viewport can
 * show are measured and drawn: a long document would otherwise pay for hundreds
 * nobody is looking at.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawLineNumbers(
    layout: TextLayoutResult?,
    lineStarts: List<Int>,
    activeLine: Int,
    marks: Map<Int, CodeSeverity>,
    right: Float,
    colors: CodeColors,
    style: TextStyle,
    measurer: TextMeasurer,
    scroll: Int,
    viewport: Int,
    map: OffsetMapping? = null,
    folds: List<CodeFold> = emptyList(),
    foldable: Map<Int, Int> = emptyMap(),
    folded: Set<Int> = emptySet(),
    chevronLeft: Float = -1f,
    turn: (Int) -> Float = { if (it in folded) 1f else 0f },
) {
    val result = layout ?: return
    val limit = result.layoutInput.text.length
    for (index in lineStarts.indices) {
        val start = lineStarts[index]
        // A line inside a fold is not on screen at all.
        if (folds.any { start > it.hidden.min && start <= it.hidden.max }) continue
        val shown = map?.originalToTransformed(start) ?: start
        if (shown > limit) break
        val line = result.getLineForOffset(shown)
        val top = result.getLineTop(line)
        if (top - scroll > viewport) break
        if (result.getLineBottom(line) - scroll < 0f) continue
        val mark = marks[index]
        val active = index == activeLine
        val colour = when {
            mark == CodeSeverity.ERROR -> colors.problem
            mark == CodeSeverity.WARNING -> colors.warning
            active -> colors.gutterActiveText
            else -> colors.gutterText
        }
        val number = measurer.measure(
            AnnotatedString((index + 1).toString()),
            style.copy(color = colour, fontWeight = if (active || mark != null) FontWeight.Medium else FontWeight.Normal),
        )
        drawText(number, topLeft = Offset(right - number.size.width, top))
        val foldStart = foldable[index]
        if (foldStart != null && chevronLeft >= 0f) {
            drawChevron(chevronLeft, top, result.getLineBottom(line) - top, turn(foldStart), colors.foldMark)
        }
    }
}

/** A fold chevron, pointing down over an open block and turned a quarter by [turn] to point right over a folded one. */
private fun DrawScope.drawChevron(left: Float, top: Float, height: Float, turn: Float, color: Color) {
    val half = minOf(height * 0.2f, 4.dp.toPx())
    val centreX = left + FOLD_GUTTER.toPx() / 2f
    val centreY = top + height / 2f
    val path = Path()
    path.moveTo(centreX - half, centreY - half / 2f)
    path.lineTo(centreX, centreY + half / 2f)
    path.lineTo(centreX + half, centreY - half / 2f)
    rotate(degrees = -90f * turn, pivot = Offset(centreX, centreY)) {
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

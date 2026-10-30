package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import java.io.File

// ---------------------------------------------------------------------------
// A small code editor
// ---------------------------------------------------------------------------

/** A value that goes to [save] on every write, so an editor's view outlives the screen (#984). */
private class SavedViewState<T>(initial: T, private val save: (T) -> Unit) : MutableState<T> {
    private val state = mutableStateOf(initial)
    override var value: T
        get() = state.value
        set(next) {
            state.value = next
            save(next)
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

private fun codeEditorPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("code_editor", Context.MODE_PRIVATE)

/**
 * Whether the code editors wrap long lines, kept across editors and launches,
 * [default] until the user has said. Saved beside the settings rather than
 * among them: `KeyboardSettings` is full, and the keyboard never reads this.
 */
@Composable
internal fun rememberCodeWrap(default: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    return remember {
        val prefs = codeEditorPrefs(context)
        SavedViewState(prefs.getBoolean("wrap", default)) { prefs.edit().putBoolean("wrap", it).apply() }
    }
}

/** The code editors' text size, kept the same way as [rememberCodeWrap]. */
@Composable
internal fun rememberCodeTextSize(default: Int): MutableState<Int> {
    val context = LocalContext.current
    return remember {
        val prefs = codeEditorPrefs(context)
        SavedViewState(prefs.getInt("text_size", default)) { prefs.edit().putInt("text_size", it).apply() }
    }
}

/**
 * The parts of a source language this editor needs: how to colour it, how to
 * tidy it, where its brackets pair up, and what is wrong with it right now.
 *
 * [JsonCode] colours and prints JSON; [LayoutJsonLanguage] adds the layout
 * schema's suggestions and checks on top of it for the two JSON screens; [LuaCode]
 * serves the plugin editor. The editor itself knows about none of them.
 */
@Immutable
internal interface CodeLanguage {
    /**
     * The document, coloured. Called once per change of the text, so it must
     * not depend on where the caret is: the caret moves far more often than
     * the text changes, and re-colouring a long document on every move is what
     * makes an editor feel slow.
     */
    fun highlight(source: String, colors: CodeColors): AnnotatedString

    /** The document, indented again, or null when it does not parse. */
    fun format(source: String): String?

    /** What is wrong with the document, or null when nothing is. */
    fun problem(source: String): CodeProblem?

    /** The offsets of the brackets that structure [source], in order. */
    fun brackets(source: String): List<Int>

    /** The bracket at or before [caret] and the one that pairs with it. */
    fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>?

    // Everything below has a default, so a language with nothing to say about it
    // says nothing and the editor draws nothing. JsonCode overrides none of them;
    // LayoutJsonLanguage and LuaCode fill them in.

    /** What starts a line comment, or null for a language that has none. */
    val lineComment: String?
        get() = null

    /** What opens and closes a block comment, or null for a language that has none. */
    val blockComment: Pair<String, String>?
        get() = null

    /** How a typed bracket, quote or line break behaves. */
    val smartRules: CodeSmartRules
        get() = CodeSmartRules.Json

    /**
     * Everything wrong with the document, in document order. Called off the main
     * thread after a pause in typing, the same way [problem] is.
     */
    fun diagnostics(source: String): List<CodeDiagnostic> = emptyList()

    /**
     * What can be typed at [caret], or null for nothing. Asked after each change
     * the author types, so it reads tokens rather than waiting for a parse.
     * [explicit] is true when the author asked for the list.
     */
    fun completions(source: String, caret: Int, explicit: Boolean): CodeCompletions? = null

    /**
     * The stretches that can fold, each from its opening to the end of its
     * closing, in document order. Asked on every change of the text, so from
     * tokens rather than a parse.
     */
    fun foldRegions(source: String): List<TextRange> = emptyList()
}

/** A parse failure, at a character offset when the parser reports one. */
@Immutable
internal data class CodeProblem(val offset: Int?)

internal enum class CodeSeverity { ERROR, WARNING, INFO }

/**
 * One thing wrong with a stretch of the document. The words are a resource and
 * two arguments, resolved where they are drawn, because a language is built with
 * no Context.
 */
@Immutable
internal data class CodeDiagnostic(
    val range: TextRange,
    val severity: CodeSeverity,
    @StringRes val messageRes: Int = 0,
    val arg1: String? = null,
    val arg2: String? = null,
    /** A layout check's own message, which carries its resource and arguments itself. Wins over [messageRes]. */
    val note: LayoutMessage? = null,
)

/**
 * What smart editing needs to know about a language: which characters pair up,
 * where a typed opener must not bring its closer, and which lines open a block
 * the next line belongs inside.
 */
@Immutable
internal class CodeSmartRules(
    /** Each opener and the closer it brings. A quote is its own closer. */
    val pairs: Map<Char, Char>,
    /** The openers in [pairs] that close themselves. */
    val quotes: Set<Char>,
    /**
     * Whether a caret at an offset sits inside a string or a comment, judged from
     * the text before it. Nothing auto-closes there.
     */
    val inert: (text: String, at: Int) -> Boolean,
    /** Whether a line, up to the caret, opens a block that the next line is inside. */
    val opensBlock: (line: String) -> Boolean = { false },
) {
    /** The closers that are not quotes. */
    val closers: Set<Char> = pairs.filterKeys { it !in quotes }.values.toSet()

    companion object {
        /** JSON's rules, exactly the ones the editor always had. */
        val Json = CodeSmartRules(
            pairs = mapOf('{' to '}', '[' to ']', '"' to '"'),
            quotes = setOf('"'),
            inert = ::insideString,
        )
    }
}

/**
 * The editor's own colours. Fixed, rather than taken from the app theme: code
 * reads as code, and a green string on one theme and a pink one on the next
 * helps nobody. The two sets are the light and dark palettes that every editor
 * has trained people on.
 */
@Immutable
internal data class CodeColors(
    val background: Color,
    val border: Color,
    val gutter: Color,
    val gutterText: Color,
    val gutterActiveText: Color,
    val activeLine: Color,
    val caret: Color,
    val selection: Color,
    val bracketMatch: Color,
    val text: Color,
    val key: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val problem: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val warning: Color,
    /** The selection drag handles. Not [caret], which is a mid grey on the dark palette. */
    val handle: Color,
    val findMatch: Color,
    val findActive: Color,
    val foldMark: Color,
)

private val LightCode = CodeColors(
    background = Color(0xFFFFFFFF),
    border = Color(0xFFD8DEE4),
    gutter = Color(0xFFF5F6F8),
    gutterText = Color(0xFFA0A6AD),
    gutterActiveText = Color(0xFF24292F),
    activeLine = Color(0xFFF2F4F7),
    caret = Color(0xFF0550AE),
    selection = Color(0xFFADD6FF),
    bracketMatch = Color(0xFFD3E3F7),
    text = Color(0xFF24292F),
    key = Color(0xFF0451A5),
    string = Color(0xFFA31515),
    number = Color(0xFF098658),
    keyword = Color(0xFF0000FF),
    problem = Color(0xFFD1242F),
    comment = Color(0xFF008000),
    function = Color(0xFF795E26),
    operator = Color(0xFF24292F),
    warning = Color(0xFFBF8803),
    handle = Color(0xFF0550AE),
    findMatch = Color(0xFFFFE8A3),
    findActive = Color(0xFFF8C271),
    foldMark = Color(0xFFA0A6AD),
)

private val DarkCode = CodeColors(
    background = Color(0xFF1E1E1E),
    border = Color(0xFF33383D),
    gutter = Color(0xFF232527),
    gutterText = Color(0xFF858585),
    gutterActiveText = Color(0xFFC6C6C6),
    activeLine = Color(0xFF2A2D2E),
    caret = Color(0xFFAEAFAD),
    selection = Color(0xFF264F78),
    bracketMatch = Color(0xFF3A5070),
    text = Color(0xFFD4D4D4),
    key = Color(0xFF9CDCFE),
    string = Color(0xFFCE9178),
    number = Color(0xFFB5CEA8),
    keyword = Color(0xFF569CD6),
    problem = Color(0xFFF14C4C),
    comment = Color(0xFF6A9955),
    function = Color(0xFFDCDCAA),
    operator = Color(0xFFD4D4D4),
    warning = Color(0xFFCCA700),
    handle = Color(0xFF3794FF),
    findMatch = Color(0xFF623315),
    findActive = Color(0xFF9E6A03),
    foldMark = Color(0xFF858585),
)

/**
 * The files a real monospaced font is likely to sit in. AOSP ships the second
 * one, and every skin builds on AOSP.
 */
private val MONO_FONT_FILES = listOf(
    "/system/fonts/RobotoMono-Regular.ttf",
    "/system/fonts/DroidSansMono.ttf",
    "/system/fonts/NotoSansMono-Regular.ttf",
    "/system/fonts/JetBrainsMono-Regular.ttf",
    "/system/fonts/CutiveMono.ttf",
)

/**
 * A monospaced family, found by file rather than by name.
 *
 * `FontFamily.Monospace` asks the platform for its "monospace" alias, and some
 * skins point that alias at their own proportional face. ColorOS 15 does, so
 * the JSON on an OPPO device came out in the system sans with every column
 * ragged. The font file behind the alias does not go through the alias, so it
 * is loaded straight from disk when one of the usual ones is there, and the
 * alias stays as the fallback for a device that has none.
 *
 * Resolved once per process: it touches the file system, and the answer cannot
 * change while the app runs.
 */
internal val CodeFontFamily: FontFamily by lazy {
    val file = MONO_FONT_FILES
        .asSequence()
        .map(::File)
        .filter { it.canRead() }
        .firstOrNull { runCatching { Typeface.createFromFile(it) }.getOrNull() != null }
    if (file == null) FontFamily.Monospace else FontFamily(Font(file))
}

/**
 * True when no monospaced file was found and the alias is all there is. On such a
 * device column arithmetic may be off, so the plugin editor wraps by default.
 */
internal val CodeFontIsFallback: Boolean by lazy { CodeFontFamily === FontFamily.Monospace }

/** The palette that suits the theme the app is drawn in. */
@Composable
internal fun rememberCodeColors(): CodeColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) { if (dark) DarkCode else LightCode }
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

private const val UNDO_DEPTH = 100
private const val COALESCE_MS = 700L
private val INDENT = " ".repeat(CODE_TAB_STOP)

/**
 * What the editor holds: the text, where the caret is, and enough history to
 * step back out of a mistake.
 *
 * The history lives here rather than in the field, because a text field keeps
 * none of its own past one focus session, and because the two toolbar buttons
 * have to know whether there is anything to step to.
 */
@Stable
/** One block folded or opened, numbered so the same block folded twice is two events. */
@Immutable
internal data class CodeFoldEvent(val start: Int, val folded: Boolean, val serial: Int)

internal class CodeEditorState(
    initial: TextFieldValue,
    private val rules: CodeSmartRules = CodeSmartRules.Json,
) {

    private var current by mutableStateOf(initial)

    /**
     * Where the folded blocks start. A fold is kept by its block's start, and every
     * change of the text carries the starts with it: see [remapFoldStarts].
     */
    var foldStarts: Set<Int> by mutableStateOf(emptySet())
        private set

    var value: TextFieldValue
        get() = current
        private set(next) {
            if (foldStarts.isNotEmpty() && next.text != current.text) {
                foldStarts = remapFoldStarts(foldStarts, current.text, next.text)
            }
            current = next
        }

    /** The last fold or unfold of one block, for the field to animate. Typing never sets it. */
    var foldEvent: CodeFoldEvent? by mutableStateOf(null)
        private set

    fun toggleFold(start: Int) {
        val folding = start !in foldStarts
        foldStarts = if (folding) foldStarts + start else foldStarts - start
        foldEvent = CodeFoldEvent(start, folding, (foldEvent?.serial ?: 0) + 1)
    }

    fun unfold(start: Int) {
        if (start !in foldStarts) return
        foldStarts = foldStarts - start
        foldEvent = CodeFoldEvent(start, folded = false, serial = (foldEvent?.serial ?: 0) + 1)
    }

    fun foldAll(starts: Collection<Int>) {
        foldStarts = starts.toSet()
    }

    fun unfoldAll() {
        foldStarts = emptySet()
    }

    private val past = mutableStateListOf<TextFieldValue>()
    private val future = mutableStateListOf<TextFieldValue>()
    private var lastEditAt = 0L

    val text: String get() = value.text
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** The field reports a change. Smart editing runs here, before the record. */
    fun edit(new: TextFieldValue) {
        val smart = smartEdit(value, new, rules)
        record(smart)
        value = smart
    }

    /** Replaces the whole document, as Format and Paste do. One step of history. */
    fun replace(newText: String) {
        if (newText == value.text) return
        push()
        value = TextFieldValue(newText, TextRange(value.selection.end.coerceIn(0, newText.length)))
    }

    /**
     * Applies one prepared change, a comment toggle or a replace-all, as one step
     * of history. A change that leaves the text alone only moves the selection.
     */
    fun applyEdit(edit: CodeTextEdit) {
        val current = value.text
        val min = edit.range.min.coerceIn(0, current.length)
        val max = edit.range.max.coerceIn(min, current.length)
        val next = current.substring(0, min) + edit.text + current.substring(max)
        val selection = TextRange(edit.selection.start.coerceIn(0, next.length), edit.selection.end.coerceIn(0, next.length))
        if (next == current && selection == value.selection) return
        if (next != current) push()
        value = TextFieldValue(next, selection)
    }

    /** Puts the caret at [offset]. The text does not change. */
    fun moveTo(offset: Int) {
        val at = offset.coerceIn(0, value.text.length)
        value = TextFieldValue(value.text, TextRange(at))
    }

    /** Selects [range]. The text does not change, and nothing is recorded. */
    fun select(range: TextRange) {
        val length = value.text.length
        value = TextFieldValue(value.text, TextRange(range.start.coerceIn(0, length), range.end.coerceIn(0, length)))
    }

    fun undo() {
        val previous = past.removeLastOrNull() ?: return
        future += value
        value = previous
        lastEditAt = 0L
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        past += value
        value = next
        lastEditAt = 0L
    }

    /**
     * Tab and Shift+Tab. With no selection, Tab is two spaces. With one, it
     * moves every line the selection touches in or out by one step, and so
     * does [wholeLines] with none, as Ctrl+] does.
     */
    fun shiftLines(levels: Int, wholeLines: Boolean = false) {
        val current = value
        val selection = current.selection
        if (levels > 0 && selection.collapsed && !wholeLines) {
            edit(
                TextFieldValue(
                    text = current.text.substring(0, selection.end) + INDENT + current.text.substring(selection.end),
                    selection = TextRange(selection.end + INDENT.length),
                ),
            )
            return
        }
        val first = lineStartAt(current.text, selection.min)
        val last = current.text.indexOf('\n', selection.max).let { if (it < 0) current.text.length else it }
        val block = current.text.substring(first, last)
        val shifted = block.split('\n').joinToString("\n") { line -> shiftOne(line, levels) }
        if (shifted == block) return
        val grown = shifted.length - block.length
        push()
        value = TextFieldValue(
            text = current.text.substring(0, first) + shifted + current.text.substring(last),
            selection = TextRange(
                selection.min.coerceAtLeast(first),
                (selection.max + grown).coerceIn(first, first + shifted.length),
            ),
        )
    }

    /**
     * One history entry per burst of typing. A run of plain characters inside
     * [COALESCE_MS] is one step, so undo does not walk back letter by letter,
     * while a newline, a delete or a paste always starts a fresh one.
     */
    private fun record(new: TextFieldValue) {
        val old = value
        if (old.text == new.text) return
        val now = System.currentTimeMillis()
        val typed = new.text.length == old.text.length + 1 &&
            new.text.getOrNull(new.selection.end - 1)?.isWhitespace() == false
        if (!(typed && past.isNotEmpty() && now - lastEditAt < COALESCE_MS)) {
            past += old
            while (past.size > UNDO_DEPTH) past.removeAt(0)
        }
        lastEditAt = now
        future.clear()
    }

    /** Starts a history entry for a change that is never coalesced. */
    private fun push() {
        past += value
        while (past.size > UNDO_DEPTH) past.removeAt(0)
        future.clear()
        lastEditAt = 0L
    }

    /** The newest steps of history that fit a saved state, oldest first. */
    private fun savedHistory(): List<TextFieldValue> {
        val kept = ArrayList<TextFieldValue>()
        var characters = 0
        for (index in past.indices.reversed()) {
            val entry = past[index]
            if (kept.size >= HISTORY_KEEP || characters + entry.text.length > HISTORY_CHARS) break
            kept += entry
            characters += entry.text.length
        }
        return kept.asReversed()
    }

    companion object {
        private const val HISTORY_KEEP = 20

        /** Characters of history a saved state may carry. A bundle spends two bytes on each. */
        private const val HISTORY_CHARS = 32 * 1024

        /**
         * A saver that restores with [rules], so a Lua document does not come back
         * from a rotation typing like JSON. [keepHistory] also carries the newest
         * undo steps, capped by size: a bundle that is too large is a crash, not a
         * slow save.
         */
        fun saver(rules: CodeSmartRules, keepHistory: Boolean): Saver<CodeEditorState, Any> = listSaver(
            save = { state ->
                buildList<Any> {
                    add(state.value.text)
                    add(state.value.selection.start)
                    add(state.value.selection.end)
                    if (keepHistory) {
                        for (entry in state.savedHistory()) {
                            add(entry.text)
                            add(entry.selection.start)
                            add(entry.selection.end)
                        }
                    }
                }
            },
            restore = { saved ->
                val state = CodeEditorState(
                    TextFieldValue(saved[0] as String, TextRange(saved[1] as Int, saved[2] as Int)),
                    rules,
                )
                var index = 3
                while (index + 2 < saved.size) {
                    state.past += TextFieldValue(
                        saved[index] as String,
                        TextRange(saved[index + 1] as Int, saved[index + 2] as Int),
                    )
                    index += 3
                }
                state
            },
        )

        /** Rotation keeps the text and the caret. The history is not worth the bundle. */
        val Saver: Saver<CodeEditorState, Any> = saver(CodeSmartRules.Json, keepHistory = false)
    }
}

/** Remembers the editor's state for one document. A new [key] starts a new one. */
@Composable
internal fun rememberCodeEditorState(
    key: Any?,
    rules: CodeSmartRules = CodeSmartRules.Json,
    keepHistory: Boolean = false,
    initial: () -> String,
): CodeEditorState {
    val saver = remember(rules, keepHistory) { CodeEditorState.saver(rules, keepHistory) }
    return rememberSaveable(key, saver = saver) { CodeEditorState(TextFieldValue(initial()), rules) }
}

// ---------------------------------------------------------------------------
// Smart editing
// ---------------------------------------------------------------------------

/**
 * Turns one raw field change into the one an editor would make: a newline
 * carries the indentation on, an opening bracket brings its closer, a typed
 * closer steps over the one already there, and a backspace over an empty pair
 * takes both halves. [rules] says which characters those are for the language.
 *
 * It reads the difference between the old value and the new one rather than
 * key events, because on a phone the characters arrive from an input method
 * and there are no key events to read.
 *
 * Internal rather than private so the unit tests can drive it directly: it is
 * the one part of the editor with rules of its own.
 */
internal fun smartEdit(
    old: TextFieldValue,
    new: TextFieldValue,
    rules: CodeSmartRules = CodeSmartRules.Json,
): TextFieldValue {
    singleInsert(old, new)?.let { (at, character) ->
        val closer = rules.pairs[character]
        return when {
            character == '\n' -> autoIndent(new, at, rules)
            // The closer is already there: step over it instead of doubling it.
            (character in rules.closers || character in rules.quotes) && new.text.getOrNull(at + 1) == character ->
                TextFieldValue(old.text, TextRange(at + 1))
            closer != null && shouldClose(new.text, at, character, rules) -> TextFieldValue(
                text = new.text.substring(0, at + 1) + closer + new.text.substring(at + 1),
                selection = TextRange(at + 1),
            )
            else -> new
        }
    }
    singleDelete(old, new)?.let { (at, character) ->
        val closer = rules.pairs[character]
        val emptyPair = closer != null && old.text.getOrNull(at + 1) == closer
        return if (emptyPair) {
            TextFieldValue(new.text.removeRange(at, at + 1), TextRange(at))
        } else {
            new
        }
    }
    return new
}

/**
 * True when an opener typed at [at] should bring its closer. Not inside a
 * string or comment, and not in front of a word: a closer there would split
 * what the user is about to wrap.
 */
private fun shouldClose(text: String, at: Int, character: Char, rules: CodeSmartRules): Boolean {
    if (rules.pairs[character] == null) return false
    if (rules.inert(text, at)) return false
    val next = text.getOrNull(at + 1) ?: return true
    return next.isWhitespace() || next == ',' || next in rules.closers
}

/** True when offset [at] falls inside a JSON string. */
private fun insideString(text: String, at: Int): Boolean {
    var index = 0
    var open = false
    while (index < at && index < text.length) {
        when (text[index]) {
            '\\' -> if (open) index++
            '"' -> open = !open
        }
        index++
    }
    return open
}

/** Carries the line's indentation onto the new one, a step deeper after an opener. */
private fun autoIndent(new: TextFieldValue, at: Int, rules: CodeSmartRules): TextFieldValue {
    val text = new.text
    val prefix = text.substring(lineStartAt(text, at), at)
    val indent = prefix.takeWhile { it == ' ' || it == '\t' }
    val last = prefix.trimEnd().lastOrNull()
    val bracketOpens = last != null && last in rules.pairs && last !in rules.quotes
    val opens = bracketOpens || rules.opensBlock(prefix)
    val body = if (opens) indent + INDENT else indent
    val closesNext = bracketOpens && text.getOrNull(at + 1).let { it != null && it in rules.closers }
    return if (closesNext) {
        // The closer takes a line of its own at the outer level, and the caret
        // lands on the empty line between the two.
        val inserted = "\n" + body + "\n" + indent
        TextFieldValue(
            text = text.substring(0, at) + inserted + text.substring(at + 1),
            selection = TextRange(at + 1 + body.length),
        )
    } else {
        TextFieldValue(
            text = text.substring(0, at + 1) + body + text.substring(at + 1),
            selection = TextRange(at + 1 + body.length),
        )
    }
}

/** The offset and character of a one character insertion, or null for anything else. */
private fun singleInsert(old: TextFieldValue, new: TextFieldValue): Pair<Int, Char>? {
    if (new.text.length != old.text.length + 1) return null
    if (!old.selection.collapsed || !new.selection.collapsed) return null
    val at = new.selection.end - 1
    if (at < 0 || at != old.selection.end) return null
    // Compared by region rather than by rebuilding the document: this runs on
    // every keystroke, and the document can be tens of kilobytes.
    val same = old.text.regionMatches(0, new.text, 0, at) &&
        old.text.regionMatches(at, new.text, at + 1, old.text.length - at)
    return if (same) at to new.text[at] else null
}

/** The offset and character of a one character deletion, or null for anything else. */
private fun singleDelete(old: TextFieldValue, new: TextFieldValue): Pair<Int, Char>? {
    if (new.text.length != old.text.length - 1) return null
    if (!old.selection.collapsed || !new.selection.collapsed) return null
    val at = new.selection.end
    if (at < 0 || at >= old.text.length || at != old.selection.end - 1) return null
    val same = old.text.regionMatches(0, new.text, 0, at) &&
        old.text.regionMatches(at + 1, new.text, at, new.text.length - at)
    return if (same) at to old.text[at] else null
}

/** One line in or out by a single step. Out stops at the left margin. */
private fun shiftOne(line: String, levels: Int): String = if (levels > 0) {
    INDENT + line
} else {
    line.removePrefix(INDENT).let { if (it == line) line.trimStart(' ') else it }
}

// ---------------------------------------------------------------------------
// Lines and the clipboard
// ---------------------------------------------------------------------------

/** The offset each line starts at. The first is always 0. */
internal fun lineStartOffsets(text: String): List<Int> {
    val starts = ArrayList<Int>(text.count { it == '\n' } + 1)
    starts += 0
    text.forEachIndexed { index, character -> if (character == '\n') starts += index + 1 }
    return starts
}

/** The index of the line that [offset] falls on. */
internal fun lineOf(starts: List<Int>, offset: Int): Int {
    var low = 0
    var high = starts.size - 1
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (starts[middle] <= offset) low = middle else high = middle - 1
    }
    return low
}

/** The offset the line holding [at] starts at. */
private fun lineStartAt(text: String, at: Int): Int =
    text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

internal fun copyCode(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("code", text))
    }
}

internal fun pasteCode(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    (0 until clip.itemCount)
        .asSequence()
        .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
        .firstOrNull { it.isNotBlank() }
}.getOrNull()

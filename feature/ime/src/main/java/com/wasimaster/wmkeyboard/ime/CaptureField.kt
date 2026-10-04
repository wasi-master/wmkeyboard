package com.wasimaster.wmkeyboard.ime

/**
 * The keyboard's own text fields — the ones whose keystrokes never reach the
 * app behind the keyboard — and the caret that edits them.
 *
 * ## Why this file exists
 *
 * A dozen buffers live on the keyboard: the emoji and media and dictionary and
 * clipboard search queries, the AI Custom instruction, a plugin's text box,
 * Find and replace's two fields, Learn from text's speller, the calculator's
 * expression, the converters' amount. Each one was a plain `String` appended to
 * by a hand-written branch repeated in `processTypedText`, `onDelete`,
 * `canDelete`, `backspaceEditsBuffer`, `onSpace`, `onEnter` and
 * `onForwardDelete` — seven ladders that had to agree and did not. None of them
 * had a caret, so typing could only ever land at the end, and none of them
 * could be glided into or suggested for, because glide inserts a word *at* a
 * caret and a suggestion replaces the word *around* one (issue #161).
 *
 * So: [CaptureTarget] names the focused buffer once, [CaretText] does the text
 * arithmetic once, and the service keeps one caret for whichever buffer is
 * focused. The old per-buffer branches become one `captureEdit { }`.
 *
 * The caret can hold a selection (#352): [CaretText.anchor] is the fixed end,
 * [CaretText.caret] the end that moves, and every edit below replaces the
 * selected span first, the way a real field does. The word card's [WordSpell]
 * keeps its own editor with its own anchor, from #204.
 */

/**
 * A keyboard-owned buffer and where the caret stands in it, in UTF-16 units.
 *
 * Immutable, and every operation returns a new one, so a transform can be
 * handed to `captureEdit` and applied without the caller knowing which buffer
 * it is about to land in.
 */
data class CaretText(
    val text: String,
    val caret: Int = text.length,
    /**
     * The fixed end of the selection, or the caret itself when nothing is
     * selected. Either side of [caret]: a handle dragged backwards puts the
     * moving end in front of the fixed one.
     */
    val anchor: Int = caret,
) {

    /** [caret] guaranteed inside [text] and never splitting a surrogate pair. */
    val at: Int get() = clamp(caret)

    /** [anchor], held inside [text] the same way. */
    val anchorAt: Int get() = clamp(anchor)

    /** Where the selection starts and ends; equal when nothing is selected. */
    val selectionStart: Int get() = minOf(at, anchorAt)
    val selectionEnd: Int get() = maxOf(at, anchorAt)
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    /** The selected text, empty when nothing is selected. */
    val selectedText: String get() = text.substring(selectionStart, selectionEnd)

    /**
     * The selected span taken out, the caret where it began. What every edit
     * does first when there is a selection, and what Cut leaves behind.
     */
    fun withoutSelection(): CaretText {
        if (!hasSelection) return collapsed()
        val start = selectionStart
        return CaretText(text.substring(0, start) + text.substring(selectionEnd), start)
    }

    /** The same caret with nothing selected. */
    fun collapsed(): CaretText = CaretText(text, at)

    /** [start] to [end] selected, the caret at [end]. */
    fun selected(start: Int, end: Int): CaretText = CaretText(text, clamp(end), clamp(start))

    /**
     * The selected span run through [recase], with the new span selected; null
     * when there is no selection or nothing about it changes.
     */
    fun recased(recase: (String) -> String): CaretText? {
        if (!hasSelection) return null
        val next = recase(selectedText)
        if (next == selectedText) return null
        val start = selectionStart
        val updated = text.substring(0, start) + next + text.substring(selectionEnd)
        return CaretText(updated, start + next.length, start)
    }

    /** The whole buffer selected. */
    fun selectedAll(): CaretText = CaretText(text, text.length, 0)

    /** The word under [index] selected, or just the caret there when it is on no word. */
    fun selectedWordAt(index: Int): CaretText {
        val span = wordSpanAt(text, clamp(index)) ?: return caretAt(index)
        return CaretText(text, span.last + 1, span.first)
    }

    /** [text] typed at the caret, in place of the selection if there is one. */
    fun typed(insert: String): CaretText {
        if (hasSelection) return withoutSelection().typed(insert)
        if (insert.isEmpty()) return this
        val i = at
        return CaretText(text.substring(0, i) + insert + text.substring(i), i + insert.length)
    }

    /** Backspace: the selection, or else the character before the caret, whole emoji included. */
    fun deletedBackward(length: Int = 1): CaretText {
        if (hasSelection) return withoutSelection()
        val i = at
        if (i <= 0) return this
        val from = (i - length.coerceAtLeast(1)).coerceAtLeast(0).let { safeBack(it) }
        return CaretText(text.substring(0, from) + text.substring(i), from)
    }

    /** Forward delete: the selection, or else the character after the caret. */
    fun deletedForward(): CaretText {
        if (hasSelection) return withoutSelection()
        val i = at
        if (i >= text.length) return this
        return CaretText(text.substring(0, i) + text.substring(stepForward(text, i)), i)
    }

    /**
     * The caret moved [delta] characters, clamped to the buffer's ends. With
     * [extend] the anchor stays put and the selection grows or shrinks; without
     * it a selection collapses to the end the arrow points at, as in any field.
     */
    fun caretMoved(delta: Int, extend: Boolean = false): CaretText {
        if (hasSelection && !extend && delta != 0) {
            val edge = if (delta < 0) selectionStart else selectionEnd
            return CaretText(text, edge)
        }
        var i = at
        repeat(kotlin.math.abs(delta)) {
            i = if (delta < 0) stepBack(text, i) else stepForward(text, i)
        }
        return if (extend) CaretText(text, i, anchorAt) else CaretText(text, i)
    }

    /** The caret put at [index] — what a tap in the middle of the text asks for. */
    fun caretAt(index: Int, extend: Boolean = false): CaretText =
        if (extend) CaretText(text, clamp(index), anchorAt) else CaretText(text, clamp(index))

    /**
     * The word the caret is inside or at the end of, and where it starts.
     *
     * This is what a suggestion is *about*: the strip offers completions of
     * this word and a pick replaces exactly this span. A caret sitting on
     * whitespace or punctuation has no word, and the strip then shows
     * next-word predictions instead — the same split the field path makes.
     */
    fun wordAtCaret(): CaretWord {
        val i = at
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = i
        while (end < text.length && isWordChar(text[end])) end++
        // Only the part in front of the caret is "typed so far"; the tail is
        // the rest of a word being edited in the middle and is kept as-is, so
        // a pick over `he|llo` replaces the whole of `hello` rather than
        // leaving `…llo` stranded.
        return CaretWord(start = start, end = end, typed = text.substring(start, i))
    }

    /**
     * The word before [wordAtCaret], for the prediction context — the same
     * `previousWord` the field path feeds the engine.
     */
    fun wordBeforeCaret(): String? {
        var i = wordAtCaret().start
        while (i > 0 && !isWordChar(text[i - 1])) i--
        if (i <= 0) return null
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start, i).takeIf { it.isNotEmpty() }
    }

    /**
     * [word] put in place of the word at the caret, with the caret past it and
     * past the space that separates it from whatever follows.
     *
     * [spaceAfter] is the trailing space a strip pick and a glide both end
     * with. A gap that is already there is stepped over rather than doubled,
     * so picking a word in the middle of a sentence leaves the caret at the
     * start of the next word either way — which is where the next keystroke
     * belongs, and what the field's own strip does.
     */
    fun replacedWordAtCaret(word: String, spaceAfter: Boolean): CaretText {
        // A pick with text selected replaces the selection, as typing would.
        if (hasSelection) return withoutSelection().typed(if (spaceAfter) "$word " else word)
        val span = wordAtCaret()
        val tail = text.substring(span.end)
        val head = text.substring(0, span.start) + word
        if (!spaceAfter) return CaretText(head + tail, head.length)
        if (tail.startsWith(" ")) return CaretText(head + tail, head.length + 1)
        return CaretText("$head $tail", head.length + 1)
    }

    /**
     * A glided [word] put in at the caret as a word of its own, with the
     * caret after it.
     *
     * A glide is not a pick: it never finishes the word in front of the
     * caret, it adds another one (issue #300). A glide at the end of a query
     * leaves no trailing space behind it, so the word it lands next to is
     * usually the previous glide's, and swapping that out would leave every
     * query one word long. So a word touching the caret gets a space between
     * it and the new one, and a word after the caret gets one too, stepping
     * over a gap that is already there rather than doubling it. At the very
     * end nothing trails: the next stroke brings its own space.
     */
    fun glided(word: String): CaretText {
        if (hasSelection) return withoutSelection().glided(word)
        val i = at
        val head = text.substring(0, i)
        val tail = text.substring(i)
        val lead = if (head.isNotEmpty() && isWordChar(head.last())) " " else ""
        val body = head + lead + word
        return when {
            tail.isEmpty() -> CaretText(body, body.length)
            tail.startsWith(" ") -> CaretText(body + tail, body.length + 1)
            isWordChar(tail.first()) -> CaretText("$body $tail", body.length + 1)
            else -> CaretText(body + tail, body.length)
        }
    }

    private fun clamp(index: Int): Int {
        val i = index.coerceIn(0, text.length)
        return safeBack(i)
    }

    /** [i], moved back one if it would sit between a surrogate pair. */
    private fun safeBack(i: Int): Int =
        if (i in 1 until text.length &&
            Character.isHighSurrogate(text[i - 1]) && Character.isLowSurrogate(text[i])
        ) {
            i - 1
        } else {
            i
        }

    companion object {
        fun stepBack(s: String, at: Int): Int =
            if (at >= 2 && Character.isLowSurrogate(s[at - 1]) && Character.isHighSurrogate(s[at - 2])) at - 2
            else (at - 1).coerceAtLeast(0)

        fun stepForward(s: String, at: Int): Int =
            if (at + 1 < s.length && Character.isHighSurrogate(s[at]) && Character.isLowSurrogate(s[at + 1])) at + 2
            else (at + 1).coerceAtMost(s.length)

        /**
         * What counts as part of a word for the caret's purposes. Letters and
         * digits in any script, plus the two marks that sit inside words in
         * the languages this keyboard ships — the apostrophe of `don't` and
         * `c'est`, and the hyphen of `well-known`.
         */
        fun isWordChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

        /**
         * The word [index] is on or just after, as a range of [text], or null
         * when it touches no word. What a long press selects, here and on the
         * tools' read-only text.
         */
        fun wordSpanAt(text: String, index: Int): IntRange? {
            if (text.isEmpty()) return null
            val i = index.coerceIn(0, text.length)
            // On a word, or at the end of one: a press just past the last letter
            // still means that word.
            val probe = when {
                i < text.length && isWordChar(text[i]) -> i
                i > 0 && isWordChar(text[i - 1]) -> i - 1
                else -> return null
            }
            var start = probe
            while (start > 0 && isWordChar(text[start - 1])) start--
            var end = probe + 1
            while (end < text.length && isWordChar(text[end])) end++
            // Trim the hyphens and apostrophes a word does not start or end with.
            while (start < end && !text[start].isLetterOrDigit()) start++
            while (end > start && !text[end - 1].isLetterOrDigit()) end--
            return if (start < end) start until end else null
        }
    }
}

/** The span [CaretText.wordAtCaret] found, and the part of it already typed. */
data class CaretWord(val start: Int, val end: Int, val typed: String)

/**
 * Which keyboard-owned field has the keys.
 *
 * Read off the state by [KeyboardUiState.captureTarget], once, in the priority
 * order the typed-text path always used. Every ladder that used to re-derive
 * this branches on the enum instead, so the seven copies cannot drift apart
 * again — they already had, which is what [KeyboardUiState.keysTakenByKeyboard]
 * was an incomplete patch for.
 */
enum class CaptureTarget(
    /**
     * Whether the field holds *words* — so a glide can spell into it and the
     * suggestion strip has something to offer. False for the two numeric
     * fields, where a word list means nothing, and for the typing test, which
     * decodes its own strokes against the prompt it is scoring.
     */
    val takesWords: Boolean,
    /**
     * Whether the caret in this field can be moved. False only for the typing
     * test: its score counts keystrokes in the order they were made, so a
     * character inserted behind the ones already scored would be counted
     * against a word it was never typed into.
     */
    val movableCaret: Boolean = true,
) {
    TYPING_TEST(takesWords = false, movableCaret = false),
    AI_CUSTOM(takesWords = true),
    /** The AI panel's chat composer (#280): free text, newlines and all. */
    AI_CHAT(takesWords = true),

    /** The KDE Connect panel's "add by address" box: an IP address or a host name. */
    KDE_HOST(takesWords = false),

    /**
     * Typing on the paired computer, live (#285). The buffer is the line the
     * computer has been sent, and every change to it is replayed there as
     * backspaces and text — which only works while changes happen at the end,
     * so the caret does not move: arrow keys go to the computer instead.
     */
    KDE_REMOTE(takesWords = true, movableCaret = false),

    /** The same panel in compose mode: a line edited here and sent whole on Enter. */
    KDE_COMPOSE(takesWords = true),
    PLUGIN(takesWords = true),
    FIND_QUERY(takesWords = true),
    FIND_REPLACEMENT(takesWords = true),
    LEARN_EDIT(takesWords = true),
    CALC(takesWords = false),
    CONVERTER(takesWords = false),
    WORD_SPELL(takesWords = true),
    EMOJI_SEARCH(takesWords = true),
    MEDIA_SEARCH(takesWords = true),
    DICTIONARY_SEARCH(takesWords = true),
    CLIPBOARD_SEARCH(takesWords = true),
    /** The clipboard panel's clip editor: free text, newlines and all. */
    CLIP_EDIT(takesWords = true),
    ;

    /**
     * Whether the field's own editor owns the caret rather than the shared one.
     * Only the word card, whose [WordSpell] carries anchor and cursor for #204.
     */
    val ownsCaret: Boolean get() = this == WORD_SPELL

    /**
     * Whether the field's own strip carries a microphone that dictates into it
     * (#353). Every field that holds words, less three: the typing test scores
     * keystrokes, and the word card's spelling and the Learn speller each hold
     * a single word, which a spoken phrase is not.
     */
    val takesDictation: Boolean
        get() = takesWords && this != TYPING_TEST && this != WORD_SPELL && this != LEARN_EDIT

    /**
     * A search box rather than a place for prose: the full stop a recognizer
     * puts at the end of every phrase would become part of what is searched for.
     */
    val isSearch: Boolean
        get() = this == EMOJI_SEARCH || this == MEDIA_SEARCH || this == DICTIONARY_SEARCH ||
            this == CLIPBOARD_SEARCH || this == FIND_QUERY
}

/**
 * Where a caret sits, in which buffer, and what that buffer held at the time.
 *
 * [key] identifies the buffer rather than only its [CaptureTarget], because two
 * of them are really families: a plugin has one box per widget id, and Find and
 * replace's two fields are one panel.
 *
 * [text] is what makes this safe to leave lying around. A caret is only used
 * when both the key *and* the text still match what is focused; anything else
 * — another field taking the keys, a query prefilled from the user's text, a
 * tool writing its own result into the box, a panel closing and reopening —
 * puts the caret back at the end, which is exactly where it sat before any of
 * these fields had one. So no write site has to remember to reset it, and a
 * path that sets a buffer directly cannot leave a caret pointing into text
 * that is no longer there.
 */
data class CaptureCaret(val key: String, val at: Int, val text: String, val anchor: Int = at)

/** What the selection bar over a keyboard-owned field can do with its text (#352). */
enum class CaptureSelectionAction { CUT, COPY, PASTE, SELECT_ALL }

/** What the microphone on a keyboard-owned field's strip was asked to do (#353). */
enum class CaptureVoiceAction {
    /** Start dictating into the field, or finish the phrase being said. */
    TOGGLE,

    /** Put the dictation away: the session ends and the strip has its words back. */
    CLOSE,

    /** Ask for the microphone, which an input method cannot do itself. */
    PERMISSION,

    /** Open the voice settings: a missing Whisper model or server address. */
    SETTINGS,
}

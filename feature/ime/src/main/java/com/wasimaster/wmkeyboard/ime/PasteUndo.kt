package com.wasimaster.wmkeyboard.ime

/**
 * The first Backspace after a paste takes the whole paste back
 * ([com.wasimaster.wmkeyboard.core.settings.ClipboardSettings.backspaceUndoesPaste],
 * FUTO's "Delete pasted/inserted text on backspace").
 *
 * The service records a paste as a revertible commit and lets its caret guard
 * and its "any other input" clears retire it, the way it does a correction;
 * what is left, and kept here so it can be tested, is the two questions the
 * record and the press each ask.
 */
internal object PasteUndo {

    /**
     * The longest paste the press takes back. The probe reads this much of the
     * field across the binder, and a page of text pasted by mistake is rarer
     * than a word or a link; past this, Backspace deletes one character.
     */
    const val MAX_CHARS = 10_000

    /**
     * Whether a paste of [text] is recorded at all: the switch is on, there is
     * text (an image goes in through commitContent and leaves none to take
     * back), it is short enough to probe, and the field is not a terminal's,
     * where a typeless field keeps its plain Backspace.
     */
    fun recordable(text: CharSequence?, enabled: Boolean, nullField: Boolean): Boolean =
        enabled && !nullField && !text.isNullOrEmpty() && text.length <= MAX_CHARS

    /**
     * How many characters the press deletes: the length of the first of
     * [forms] that the text before the caret ends with, or 0 when none does
     * and the press is an ordinary one.
     *
     * [forms] is the paste as the keyboard sent it and as the field may hold
     * it (styled under Fancy Text, or with the line breaks an editor
     * normalised); [before] is at least the longest of them read back from
     * the field. A field that changed under the paste, or text typed after it
     * that the clears missed, fails the match, which is what makes deleting a
     * whole run safe.
     */
    fun deleteLength(forms: List<String>, before: CharSequence?, composingEmpty: Boolean): Int {
        if (!composingEmpty || before.isNullOrEmpty()) return 0
        val text = before.toString()
        return forms.firstOrNull { it.isNotEmpty() && text.endsWith(it) }?.length ?: 0
    }

    /**
     * The forms a paste of [text] may stand in the field as: itself, [styled]
     * (the keyboard's own Fancy Text form of it), and with Windows line
     * breaks folded, the one rewrite editors commonly make to pasted text.
     * Longest first, so a match never stops at a shorter form's tail.
     */
    fun forms(text: String, styled: String = text): List<String> =
        listOf(text, styled, text.replace("\r\n", "\n"))
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
}

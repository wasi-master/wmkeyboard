package com.wasimaster.wmkeyboard.ime

/** How far back No space reads: past a word's last letter and the marks on it. */
const val NO_SPACE_LOOK_BACK = 16

/**
 * Whether [text] ends on a word's letter or digit, the marks on it counted with
 * it (#1057): a Hebrew word ending in niqqud ends on the point, not the letter.
 */
fun endsOnWordCharacter(text: CharSequence): Boolean {
    var at = text.length
    while (at > 0) {
        val cp = Character.codePointBefore(text, at)
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK ->
                at -= Character.charCount(cp)
            else -> return Character.isLetterOrDigit(cp)
        }
    }
    return false
}

/**
 * Opening bracket → its closer, for the auto-close rule
 * (`TextEditingSettings.autoCloseBrackets`): typing the opener types the
 * closer behind the caret as well, and the caret stays between them.
 *
 * Brackets only. Quotes are deliberately absent — the apostrophe in "don't"
 * and the quote key that closes a quotation share their character with the
 * opener, so a keyboard that closed them would be wrong more often than
 * right, and [WRAP_PAIRS]-style symmetric pairs have no way to tell an
 * opening `"` from a closing one without reading the whole line. `<` is
 * absent for the same kind of reason: outside code it is a less-than sign,
 * and "5 <" is nobody opening a bracket.
 */
val AUTO_CLOSE_PAIRS: Map<Char, String> = mapOf(
    '(' to ")", '[' to "]", '{' to "}",
    '（' to "）", '［' to "］", '｛' to "｝",
    '〈' to "〉", '《' to "》", '⟨' to "⟩", '⟦' to "⟧",
    '「' to "」", '『' to "』", '｢' to "｣",
    '【' to "】", '〔' to "〕", '〖' to "〗", '〘' to "〙", '〚' to "〛",
)

/**
 * The closing `"` or `'` to type behind the caret when one lands, for the
 * separate quotes switch (#970), or null. [before] is the character in front
 * of the one just typed, [after] the text ahead of the caret.
 *
 * A quote right after a letter or digit closes something or is an apostrophe
 * ("don't", 5"), so it stands down, and it pairs only where a bracket would:
 * with nothing it would end up wrapped around.
 */
fun autoCloseQuoteFor(typed: Char, before: Char?, after: CharSequence?): String? {
    if (typed != '"' && typed != '\'') return null
    if (before != null && before.isLetterOrDigit()) return null
    val next = after?.firstOrNull() ?: return typed.toString()
    val room = next.isWhitespace() || next in AUTO_CLOSE_CLOSERS || next in CLAUSE_ENDS
    return if (room) typed.toString() else null
}

/** The closers of [AUTO_CLOSE_PAIRS], for the "type over it" test below. */
val AUTO_CLOSE_CLOSERS: Set<Char> = AUTO_CLOSE_PAIRS.values.mapTo(HashSet()) { it[0] }

/**
 * The closer to type behind the caret when [typed] lands, or null when this
 * keypress closes nothing. [after] is the text in front of the caret.
 *
 * A bracket typed in front of text is being put *around* that text by hand —
 * "(" before "world" is on its way to "(world)" — and a closer dropped into the
 * middle of it would have to be deleted again, so the rule stands down. That
 * holds for any text, not only letters: "@name", "#tag", "$5", an emoji or a
 * quotation all get their bracket closed by hand (#970). The pair goes in only
 * where nothing would end up inside it: before a space, a line break, the end
 * of the text, another closer ("[(]"), or a mark that ends a clause.
 */
fun autoCloseCloserFor(typed: Char, after: CharSequence?): String? {
    val closer = AUTO_CLOSE_PAIRS[typed] ?: return null
    val next = after?.firstOrNull() ?: return closer
    val room = next.isWhitespace() || next in AUTO_CLOSE_CLOSERS || next in CLAUSE_ENDS
    return if (room) closer else null
}

/** Marks a bracket pair may sit in front of: "say ()." keeps its full stop outside. */
private val CLAUSE_ENDS: Set<Char> = setOf('.', ',', ';', ':', '!', '?', '。', '、', '，', '；', '：', '！', '？')

/**
 * Whether pressing [typed] should step the caret over the closer already in
 * front of it rather than typing a second one.
 *
 * Without this, the habit of typing both halves turns the pair the keyboard
 * just made into "())". Deliberately stateless — it does not ask whether *this*
 * closer was the keyboard's — because the alternative is tracking caret-relative
 * positions through every edit the app makes behind our back, and the cost of
 * being wrong is small and visible: the caret ends up past a closer that was
 * already there, which is where typing one would have put it anyway.
 */
fun typesOverCloser(typed: Char, after: CharSequence?): Boolean =
    typed in AUTO_CLOSE_CLOSERS && after?.firstOrNull() == typed

/**
 * Whether one backspace should take out both halves of an empty pair: the
 * caret sits between an opener and its own closer, which is exactly the state
 * auto-close leaves behind, so the press that undoes the opener undoes the
 * closer with it. A pair with anything inside it is left alone.
 */
fun deletesEmptyPair(before: CharSequence, after: CharSequence?): Boolean {
    val opener = before.lastOrNull() ?: return false
    if (opener == '"' || opener == '\'') return after?.firstOrNull() == opener
    val closer = AUTO_CLOSE_PAIRS[opener] ?: return false
    return after?.firstOrNull() == closer[0]
}

/**
 * The closing bracket that the automatic space after punctuation follows, the
 * way it follows a comma: "(see below)" then a word reads "(see below) word"
 * (#617). Only `)`. A `]` is as often half of a Markdown link, where
 * "[text](url)" must not come apart at the space, and `}` is code.
 */
const val SPACED_CLOSER: Char = ')'

/**
 * Whether a [SPACED_CLOSER] landing in front of [after] — the text after the
 * caret — ends the run of text it closes, so a space may follow it. Anything
 * but a blank, a line break or the end of the text means the closer is landing
 * mid-text: in front of another closer ("((a))"), a mark ("(a).") or a word,
 * none of which wants a space pushed in.
 */
fun closerTakesSpace(after: CharSequence?): Boolean =
    after.isNullOrEmpty() || after[0].isWhitespace()

package com.wasimaster.wmkeyboard.ime

/**
 * Whether a snippet's [text] may go into a field of [kind] (#815). A number,
 * phone, date or time field takes the characters its keypad types and little
 * else, and an app that filters what it is given would keep a few of a
 * snippet's letters and drop the rest. So there a snippet expands only when
 * its text is made of what that field takes, and is otherwise left as typed;
 * every other field takes anything.
 */
internal fun snippetFitsField(text: String, kind: FieldKind): Boolean {
    val extra = when (kind) {
        FieldKind.NUMBER -> "+-.,"
        FieldKind.PHONE -> "+*#()-./ ,;"
        FieldKind.DATE, FieldKind.TIME, FieldKind.DATETIME -> "-/.:, "
        else -> return true
    }
    return text.isNotEmpty() && text.all { it.isDigit() || it in extra }
}

/**
 * Whether a snippet's raw [text] could expand to something a keypad field
 * takes (#1436). Only a `{variable}` can turn letters into digits, so text with
 * a letter and no brace never fits, and is turned away without expanding it.
 */
internal fun couldFitKeypad(text: String): Boolean {
    if (text.indexOf('{') >= 0) return true
    return text.none { it.isLetter() }
}

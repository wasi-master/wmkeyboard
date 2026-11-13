package com.wasimaster.wmkeyboard.core.layout

/**
 * What Shift makes of a key's text when the key has no shift label of its own.
 *
 * `String.uppercase()` uses Unicode's full case mapping, which spells some
 * letters with two: Greek ᾳ (alpha with ypogegrammeni) comes out as "ΑΙ",
 * where the capital a Greek typist expects is the single letter ᾼ. Each
 * character whose full mapping grows into several letters takes its
 * one-letter capital instead, when Unicode has one. A letter with no
 * one-letter capital keeps the full mapping, so ß still types "SS" and ŉ
 * "ʼN", as before.
 */
fun keyUppercase(text: String): String {
    if (text.all { it.code < 0x80 }) return text.uppercase()
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val full = String(Character.toChars(cp)).uppercase()
        val simple = Character.toUpperCase(cp)
        if (full.codePointCount(0, full.length) > 1 && simple != cp) {
            out.appendCodePoint(simple)
        } else {
            out.append(full)
        }
        i += Character.charCount(cp)
    }
    return out.toString()
}

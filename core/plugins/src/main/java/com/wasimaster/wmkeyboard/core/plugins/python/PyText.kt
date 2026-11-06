package com.wasimaster.wmkeyboard.core.plugins.python

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/*
 * The Swift `String` and `Character` behaviour PyMini leans on, rebuilt for the JVM.
 *
 * PyMini's strings are Swift strings: `count`, indexing, slicing and iteration
 * go through `Character`, an extended grapheme cluster (`Array(s)` in
 * PyInterpreter.swift, `index`/`slice`/`iterate`). `==` and `<` compare by
 * canonical equivalence. Case mapping is per scalar (`String.uppercased()`),
 * and `String(format:)` is C's printf. None of that is what a Kotlin `String`
 * does by default, so it lives here.
 */

/** Grapheme clusters of [s]: what Swift's `Array(s)` gives. */
internal fun graphemes(s: String): List<String> {
    if (isSimpleText(s)) {
        val out = ArrayList<String>(s.length)
        for (c in s) out.add(c.toString())
        return out
    }
    val it = BreakIterator.getCharacterInstance(Locale.ROOT)
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out.add(s.substring(start, end))
        start = end
        end = it.next()
    }
    return out
}

/** Swift's `s.count`. */
internal fun graphemeCount(s: String): Int {
    if (isSimpleText(s)) return s.length
    val it = BreakIterator.getCharacterInstance(Locale.ROOT)
    it.setText(s)
    var n = 0
    it.first()
    while (it.next() != BreakIterator.DONE) n++
    return n
}

/** Whether `s.count > max`, without segmenting a string that can't be that long. */
internal fun longerThan(s: String, max: Int): Boolean = s.length > max && graphemeCount(s) > max

/** Every UTF-16 unit is its own cluster: nothing combines below U+0300 except CR LF. */
private fun isSimpleText(s: String): Boolean {
    for (c in s) if (c >= '̀' || c == '\r') return false
    return true
}

private fun isAscii(s: String): Boolean {
    for (c in s) if (c.code >= 0x80) return false
    return true
}

private fun nfc(s: String): String = if (isAscii(s)) s else Normalizer.normalize(s, Normalizer.Form.NFC)

/** Swift `String ==`: canonical equivalence, so `é` equals `e` + U+0301. */
internal fun strEq(a: String, b: String): Boolean {
    if (a == b) return true
    if (isAscii(a) && isAscii(b)) return false
    return nfc(a) == nfc(b)
}

/** Hash consistent with [strEq]. */
internal fun strHash(s: String): Int = nfc(s).hashCode()

/** Swift `String <`: the NFC forms compared scalar by scalar. */
internal fun strCompare(a: String, b: String): Int {
    if (a == b) return 0
    val x = nfc(a)
    val y = nfc(b)
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val cx = x.codePointAt(i)
        val cy = y.codePointAt(j)
        if (cx != cy) return if (cx < cy) -1 else 1
        i += Character.charCount(cx)
        j += Character.charCount(cy)
    }
    val restX = x.length - i
    val restY = y.length - j
    return if (restX == restY) 0 else if (restX < restY) -1 else 1
}

// MARK: - Character properties (Swift's, which read the first scalar)

private fun firstScalar(g: String): Int = if (g.isEmpty()) 0 else g.codePointAt(0)

private fun isSingleScalar(g: String): Boolean = g.isNotEmpty() && Character.charCount(g.codePointAt(0)) == g.length

/** `Character.isLetter`: the first scalar is Alphabetic. */
internal fun charIsLetter(g: String): Boolean = g.isNotEmpty() && Character.isAlphabetic(firstScalar(g))

/** `Character.isNumber`: the first scalar has a numeric type. */
internal fun charIsNumber(g: String): Boolean {
    if (g.isEmpty()) return false
    return when (Character.getType(firstScalar(g))) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }
}

/** Unicode White_Space, which is what Swift checks; Java's `isWhitespace` differs. */
internal fun scalarIsWhitespace(cp: Int): Boolean = when (cp) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** `Character.isWhitespace`. */
internal fun charIsWhitespace(g: String): Boolean = g.isNotEmpty() && scalarIsWhitespace(firstScalar(g))

/** `Character.isHexDigit`: ASCII and fullwidth hex digits. */
internal fun charIsHexDigit(g: String): Boolean {
    if (!isSingleScalar(g)) return false
    return when (firstScalar(g)) {
        in '0'.code..'9'.code, in 'a'.code..'f'.code, in 'A'.code..'F'.code,
        in 0xFF10..0xFF19, in 0xFF21..0xFF26, in 0xFF41..0xFF46 -> true
        else -> false
    }
}

private fun scalarIsCased(cp: Int): Boolean =
    Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp)

private fun charIsCased(g: String): Boolean {
    var i = 0
    while (i < g.length) {
        val cp = g.codePointAt(i)
        if (scalarIsCased(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

/** `Character.isUppercase`. */
internal fun charIsUppercase(g: String): Boolean {
    if (isSingleScalar(g) && Character.isUpperCase(firstScalar(g))) return true
    return swiftUpper(g) == g && charIsCased(g)
}

/** `String.uppercased()`: each scalar's full mapping. Upper case has no context rules, so the whole string at once is the same thing. */
internal fun swiftUpper(s: String): String = s.uppercase(Locale.ROOT)

/**
 * `String.lowercased()`: each scalar's full mapping on its own. Java applies
 * the Final_Sigma context rule and Swift doesn't, so a string with a capital
 * sigma is mapped one scalar at a time.
 */
internal fun swiftLower(s: String): String {
    if (s.indexOf('Σ') < 0) return s.lowercase(Locale.ROOT)
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        sb.append(s.substring(i, i + n).lowercase(Locale.ROOT))
        i += n
    }
    return sb.toString()
}

// MARK: - C printf, which is what Swift's String(format:) is

private fun signed(d: Double, body: String): String = if (d < 0 || (d == 0.0 && 1.0 / d < 0)) "-$body" else body

/** `%.{prec}f`. */
internal fun cFixed(d: Double, prec: Int): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d < 0) "-inf" else "inf"
    return signed(d, BigDecimal(Math.abs(d)).setScale(prec, RoundingMode.HALF_EVEN).toPlainString())
}

/** Significant digits and decimal exponent of |d| rounded to [digits] figures. */
private fun roundedDigits(magnitude: Double, digits: Int): Pair<String, Int> {
    val bd = BigDecimal(magnitude).round(MathContext(digits, RoundingMode.HALF_EVEN))
    var unscaled = bd.unscaledValue().toString()
    val exponent = bd.precision() - bd.scale() - 1
    if (unscaled.length < digits) unscaled += "0".repeat(digits - unscaled.length)
    return Pair(unscaled.substring(0, digits), exponent)
}

private fun exponentText(e: Int): String {
    val sign = if (e < 0) "-" else "+"
    val a = Math.abs(e)
    return "e" + sign + (if (a < 10) "0$a" else a.toString())
}

/** `%.{prec}e`. */
internal fun cExp(d: Double, prec: Int): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d < 0) "-inf" else "inf"
    val magnitude = Math.abs(d)
    if (magnitude == 0.0) {
        return signed(d, "0" + (if (prec > 0) "." + "0".repeat(prec) else "") + "e+00")
    }
    val (digits, exponent) = roundedDigits(magnitude, prec + 1)
    val mantissa = digits.substring(0, 1) + (if (prec > 0) "." + digits.substring(1) else "")
    return signed(d, mantissa + exponentText(exponent))
}

/** `%.{prec}g`: fixed or exponent form, whichever C picks, trailing zeros dropped. */
internal fun cGeneral(d: Double, prec: Int): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d < 0) "-inf" else "inf"
    val p = if (prec == 0) 1 else prec
    val magnitude = Math.abs(d)
    if (magnitude == 0.0) return signed(d, "0")
    val exponent = roundedDigits(magnitude, p).second
    return if (p > exponent && exponent >= -4) {
        signed(d, stripZeros(cFixed(magnitude, p - 1 - exponent)))
    } else {
        val e = cExp(magnitude, p - 1)
        val at = e.indexOf('e')
        signed(d, stripZeros(e.substring(0, at)) + e.substring(at))
    }
}

private fun stripZeros(s: String): String {
    if (s.indexOf('.') < 0) return s
    return s.trimEnd('0').trimEnd('.')
}

/**
 * Swift's `Double(text)`: optional sign, digits with an optional fraction and
 * exponent, or inf/infinity/nan. No whitespace, no `_`, no type suffix.
 */
internal fun parseSwiftDouble(text: String): Double? {
    if (text.isEmpty()) return null
    val body = if (text[0] == '+' || text[0] == '-') text.substring(1) else text
    val negative = text[0] == '-'
    when (body.lowercase(Locale.ROOT)) {
        "inf", "infinity" -> return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        "nan" -> return Double.NaN
    }
    if (!DECIMAL.matches(body)) return null
    return text.toDoubleOrNull()
}

private val DECIMAL = Regex("(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

/** Swift's `Int(text)` / `Int(text, radix:)`: optional sign then digits, nothing else. */
internal fun parseSwiftInt(text: String, radix: Int = 10): Long? {
    if (radix < 2 || radix > 36 || text.isEmpty()) return null
    val digits = if (text[0] == '+' || text[0] == '-') text.substring(1) else text
    if (digits.isEmpty()) return null
    for (c in digits) if (Character.digit(c, radix) < 0 || c.code > 0x7F) return null
    return try {
        java.lang.Long.parseLong(if (text[0] == '+') digits else text, radix)
    } catch (e: NumberFormatException) {
        null
    }
}

/** Foundation's `trimmingCharacters(in: .whitespaces)`: tab and the Zs space separators. */
internal fun trimWhitespaces(s: String): String =
    s.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** Unicode titlecase mapping of one scalar (full mapping where Java only has the upper one, as for ß). */
private fun titlecaseMapping(cp: Int): String {
    val t = Character.toTitleCase(cp)
    if (t != cp) return String(Character.toChars(t))
    val upper = String(Character.toChars(cp)).uppercase(Locale.ROOT)
    if (upper.codePointCount(0, upper.length) <= 1) return upper
    val first = upper.codePointAt(0)
    return String(Character.toChars(first)) + upper.substring(Character.charCount(first)).lowercase(Locale.ROOT)
}

/** CoreFoundation's case-ignorable subset: marks, format characters, modifiers, and the apostrophe. */
private fun cfCaseIgnorable(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt(),
    Character.MODIFIER_LETTER.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
    else -> cp == '\''.code || cp == 0x2019
}

/**
 * Foundation's `String.capitalized`: an uppercase or lowercase letter is
 * titlecased when the last letter-ish scalar before it wasn't cased, and
 * lowercased otherwise. Case-ignorable scalars keep the run going; anything
 * else (space, digit, most punctuation) ends the word.
 */
internal fun foundationCapitalized(s: String): String {
    val out = StringBuilder(s.length)
    var isLastCased = false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val n = Character.charCount(cp)
        val type = Character.getType(cp)
        if (type == Character.UPPERCASE_LETTER.toInt() || type == Character.TITLECASE_LETTER.toInt()) {
            if (isLastCased) out.append(s.substring(i, i + n).lowercase(Locale.ROOT)) else out.appendCodePoint(cp)
            isLastCased = true
        } else if (type == Character.LOWERCASE_LETTER.toInt()) {
            if (isLastCased) out.appendCodePoint(cp) else out.append(titlecaseMapping(cp))
            isLastCased = true
        } else {
            out.appendCodePoint(cp)
            if (!cfCaseIgnorable(cp)) isLastCased = false
        }
        i += n
    }
    return out.toString()
}

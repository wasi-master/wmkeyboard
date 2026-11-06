package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyFormat.swift: format specs for f-string placeholders,
 * `"{:...}".format(...)` and `format(value, spec)`, all one implementation.
 *
 * Grammar (Python's, minus locale-aware `n` and the `#` alternate form):
 *     [[fill]align][sign][0][width][grouping][.precision][type]
 */

/** A parsed format spec. An empty spec formats with `str()`. */
internal class PyFormatSpec(spec: String) {
    var fill: String = " "
    /** `<` left, `>` right, `^` centre, `=` pad after the sign. null = type default. */
    var align: String? = null
    /** `+` always sign, `-` negatives only, ` ` space for positives. */
    var sign: String = "-"
    var zeroPad = false
    var width: Int? = null
    /** `,` or `_` thousands separator. */
    var grouping: String? = null
    var precision: Int? = null
    var type: String? = null

    init {
        val chars = graphemes(spec)
        var i = 0
        // fill+align, or a bare align.
        if (chars.size >= 2 && chars[1] in ALIGNS) {
            fill = chars[0]; align = chars[1]; i = 2
        } else if (chars.isNotEmpty() && chars[0] in ALIGNS) {
            align = chars[0]; i = 1
        }
        if (i < chars.size && chars[i] in SIGNS) { sign = chars[i]; i += 1 }
        if (i < chars.size && chars[i] == "#") {
            throw PyError("the '#' format option is not supported", "ValueError")
        }
        if (i < chars.size && chars[i] == "0") {
            zeroPad = true
            if (align == null) { align = "="; fill = "0" }
            i += 1
        }
        val digits = StringBuilder()
        while (i < chars.size && charIsNumber(chars[i])) { digits.append(chars[i]); i += 1 }
        if (digits.isNotEmpty()) width = parseSwiftInt(digits.toString())?.toInt()
        if (i < chars.size && (chars[i] == "," || chars[i] == "_")) { grouping = chars[i]; i += 1 }
        if (i < chars.size && chars[i] == ".") {
            i += 1
            val p = StringBuilder()
            while (i < chars.size && charIsNumber(chars[i])) { p.append(chars[i]); i += 1 }
            precision = parseSwiftInt(p.toString())?.toInt()
                ?: throw PyError("format precision needs a number", "ValueError")
        }
        if (i < chars.size) {
            type = chars[i]
            i += 1
        }
        if (i != chars.size) throw PyError("invalid format spec '$spec'", "ValueError")
    }

    private companion object {
        val ALIGNS = setOf("<", ">", "^", "=")
        val SIGNS = setOf("+", "-", " ")
    }
}

/** Apply a format spec to a value. An empty spec means `str(value)`. */
internal fun pyFormat(value: PyValue, spec: String): String {
    if (spec.isEmpty()) return pyStr(value)
    val s = PyFormatSpec(spec)
    return when (s.type) {
        "s" -> padded(pyStr(value), s, numeric = false)
        "d", "b", "o", "x", "X", "c" -> integerFormatted(value, s)
        "f", "F", "e", "E", "g", "G", "%" -> floatFormatted(value, s)
        null -> when (value) {
            // No type: numbers format as numbers, everything else (bool too) as text.
            is PyValue.Int -> { s.type = "d"; integerFormatted(value, s) }
            is PyValue.Float -> { s.type = "g"; floatFormatted(value, s) }
            else -> padded(pyStr(value), s, numeric = false)
        }
        else -> throw PyError("unknown format code '${s.type}'", "ValueError")
    }
}

private fun integerFormatted(value: PyValue, spec: PyFormatSpec): String {
    val i = asInt(value)
    if (i == null) {
        if (asDouble(value) != null) throw PyError("cannot format a float with '${spec.type ?: "d"}'", "ValueError")
        throw PyError("cannot format '${value.typeName}' as a number", "TypeError")
    }
    if (spec.type == "c") {
        if (!isScalar(i)) throw PyError("chr() arg not in range", "ValueError")
        return padded(String(Character.toChars(i.toInt())), spec, numeric = false)
    }
    val magnitude = if (i < 0) -i else i
    var digits = when (spec.type) {
        "b" -> java.lang.Long.toUnsignedString(magnitude, 2)
        "o" -> java.lang.Long.toUnsignedString(magnitude, 8)
        "x" -> java.lang.Long.toUnsignedString(magnitude, 16)
        "X" -> java.lang.Long.toUnsignedString(magnitude, 16).uppercase()
        else -> java.lang.Long.toUnsignedString(magnitude)
    }
    spec.grouping?.let { digits = grouped(digits, it) }
    val prefix = signPrefix(i < 0, spec)
    return padded(prefix + digits, spec, numeric = true, signLength = prefix.length)
}

private fun floatFormatted(value: PyValue, spec: PyFormatSpec): String {
    var d = asDouble(value) ?: throw PyError("cannot format '${value.typeName}' as a number", "TypeError")
    val negative = d < 0 || (d == 0.0 && 1.0 / d < 0)
    if (spec.type == "%") d *= 100
    val magnitude = Math.abs(d)
    var body: String
    if (magnitude.isNaN()) body = "nan"
    else if (magnitude.isInfinite()) body = "inf"
    else {
        body = when (spec.type) {
            "e", "E" -> cExp(magnitude, spec.precision ?: 6).let { if (spec.type == "E") it.uppercase() else it }
            "g", "G" -> cGeneral(magnitude, maxOf(1, spec.precision ?: 6)).let { if (spec.type == "G") it.uppercase() else it }
            else -> cFixed(magnitude, spec.precision ?: 6)
        }
        spec.grouping?.let { g ->
            val dot = body.indexOf('.')
            body = if (dot < 0) grouped(body, g) else grouped(body.substring(0, dot), g) + body.substring(dot)
        }
    }
    if (spec.type == "%") body += "%"
    val prefix = signPrefix(negative, spec)
    return padded(prefix + body, spec, numeric = true, signLength = prefix.length)
}

private fun signPrefix(negative: Boolean, spec: PyFormatSpec): String {
    if (negative) return "-"
    return when (spec.sign) {
        "+" -> "+"
        " " -> " "
        else -> ""
    }
}

/** Insert [separator] every three digits from the right. */
private fun grouped(digits: String, separator: String): String {
    if (digits.length <= 3) return digits
    val out = StringBuilder()
    for ((offset, c) in digits.reversed().withIndex()) {
        if (offset > 0 && offset % 3 == 0) out.append(separator.reversed())
        out.append(c)
    }
    return out.reverse().toString()
}

/**
 * Pad to the spec's width. Numbers default to right alignment, everything
 * else to left; `=` keeps the sign in front and pads after it.
 */
private fun padded(s: String, spec: PyFormatSpec, numeric: Boolean, signLength: Int = 0): String {
    val width = spec.width ?: return s
    val count = graphemeCount(s)
    if (count >= width) return s
    val padding = width - count
    val align = spec.align ?: if (numeric) ">" else "<"
    val fill = spec.fill.repeat(padding)
    return when (align) {
        ">" -> fill + s
        "^" -> {
            val left = padding / 2
            spec.fill.repeat(left) + s + spec.fill.repeat(padding - left)
        }
        "=" -> s.substring(0, signLength) + fill + s.substring(signLength)
        else -> s + fill
    }
}

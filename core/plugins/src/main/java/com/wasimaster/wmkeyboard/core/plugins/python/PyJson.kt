package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyJSON.swift: JSON for PyMini values, behind the `json` namespace.
 * The encoder walks PyValue directly so dict order survives a round trip, and
 * the decoder keeps object keys in file order.
 */
internal object PyJson {

    /** Encode [value]. A null [indent] uses Python's `", "` / `": "` separators; a number pretty-prints. */
    fun encode(value: PyValue, indent: Int? = null, limit: Int = 1_000_000, charge: () -> Unit = {}): String {
        val out = StringBuilder()
        write(value, out, indent, 0, limit, charge)
        return out.toString()
    }

    private fun write(v: PyValue, out: StringBuilder, indent: Int?, depth: Int, limit: Int, charge: () -> Unit) {
        charge()
        if (out.length > limit && graphemeCount(out.toString()) > limit) throw PyError.abort("string too large")
        val pad = indent?.let { " ".repeat(maxOf(0, it * (depth + 1))) } ?: ""
        val closePad = indent?.let { " ".repeat(maxOf(0, it * depth)) } ?: ""
        val newline = if (indent == null) "" else "\n"
        val separator = if (indent == null) ", " else ",\n"
        when (v) {
            PyValue.None -> out.append("null")
            is PyValue.Bool -> out.append(if (v.value) "true" else "false")
            is PyValue.Int -> out.append(v.value)
            is PyValue.Float -> out.append(if (v.value.isFinite()) formatDouble(v.value) else "null")
            is PyValue.Str -> out.append(quoted(v.value))
            is PyValue.List -> writeItems(v.list.items, out, pad, closePad, newline, separator, indent, depth, limit, charge)
            is PyValue.Tuple -> writeItems(v.items, out, pad, closePad, newline, separator, indent, depth, limit, charge)
            is PyValue.Set -> writeItems(v.set.values, out, pad, closePad, newline, separator, indent, depth, limit, charge)
            is PyValue.Dict -> {
                if (v.dict.count == 0) { out.append("{}"); return }
                out.append("{").append(newline)
                var first = true
                for ((k, value) in v.dict.orderedPairs) {
                    if (!first) out.append(separator)
                    first = false
                    out.append(pad).append(quoted(pyStr(k.value))).append(": ")
                    write(value, out, indent, depth + 1, limit, charge)
                }
                out.append(newline).append(closePad).append("}")
            }
            else -> throw PyError("${v.typeName} is not JSON serializable", "TypeError")
        }
    }

    private fun writeItems(
        items: List<PyValue>,
        out: StringBuilder,
        pad: String,
        closePad: String,
        newline: String,
        separator: String,
        indent: Int?,
        depth: Int,
        limit: Int,
        charge: () -> Unit,
    ) {
        if (items.isEmpty()) { out.append("[]"); return }
        out.append("[").append(newline)
        var first = true
        for (item in ArrayList(items)) {
            if (!first) out.append(separator)
            first = false
            out.append(pad)
            write(item, out, indent, depth + 1, limit, charge)
        }
        out.append(newline).append(closePad).append("]")
    }

    private fun quoted(s: String): String {
        val out = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            when (c) {
                '"'.code -> out.append("\\\"")
                '\\'.code -> out.append("\\\\")
                '\n'.code -> out.append("\\n")
                '\t'.code -> out.append("\\t")
                '\r'.code -> out.append("\\r")
                else -> if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", c)) else out.appendCodePoint(c)
            }
            i += Character.charCount(c)
        }
        return out.append("\"").toString()
    }

    /** Parse [text]. Object keys keep the file's own order. */
    fun decode(text: String, charge: () -> Unit = {}): PyValue {
        val parser = Parser(graphemes(text), charge)
        parser.skipSpace()
        val v = parser.value()
        parser.skipSpace()
        if (!parser.atEnd) throw PyError("could not parse JSON", "ValueError")
        return v
    }

    /** A tiny recursive-descent reader over grapheme clusters, as PyMini's is. */
    private class Parser(val chars: List<String>, val charge: () -> Unit) {
        var i = 0
        var depth = 0

        val atEnd: Boolean get() = i >= chars.size

        fun skipSpace() {
            while (i < chars.size && (chars[i] == " " || chars[i] == "\n" || chars[i] == "\t" || chars[i] == "\r")) i += 1
        }

        fun value(): PyValue {
            charge()
            if (i >= chars.size) throw PyError("unexpected end of JSON", "ValueError")
            return when (chars[i]) {
                "{" -> obj()
                "[" -> array()
                "\"" -> PyValue.Str(string())
                "t" -> { literal("true"); PyValue.Bool(true) }
                "f" -> { literal("false"); PyValue.Bool(false) }
                "n" -> { literal("null"); PyValue.None }
                else -> number()
            }
        }

        fun literal(word: String) {
            for (c in word) {
                if (i >= chars.size || chars[i] != c.toString()) throw PyError("invalid JSON literal", "ValueError")
                i += 1
            }
        }

        fun obj(): PyValue {
            depth += 1
            try {
                if (depth >= MAX_DEPTH) throw PyError.abort("JSON nested too deeply")
                i += 1
                val d = PyDict()
                skipSpace()
                if (i < chars.size && chars[i] == "}") { i += 1; return PyValue.Dict(d) }
                while (true) {
                    skipSpace()
                    val key = string()
                    skipSpace()
                    if (i >= chars.size || chars[i] != ":") throw PyError("expected ':' in JSON", "ValueError")
                    i += 1
                    skipSpace()
                    d[PyHashable.Str(key)] = value()
                    skipSpace()
                    if (i >= chars.size) throw PyError("unexpected end of JSON", "ValueError")
                    if (chars[i] == ",") { i += 1; continue }
                    if (chars[i] == "}") { i += 1; return PyValue.Dict(d) }
                    throw PyError("expected ',' or '}' in JSON", "ValueError")
                }
            } finally {
                depth -= 1
            }
        }

        fun array(): PyValue {
            depth += 1
            try {
                if (depth >= MAX_DEPTH) throw PyError.abort("JSON nested too deeply")
                i += 1
                val items = ArrayList<PyValue>()
                skipSpace()
                if (i < chars.size && chars[i] == "]") { i += 1; return PyValue.List(PyList()) }
                while (true) {
                    skipSpace()
                    items.add(value())
                    skipSpace()
                    if (i >= chars.size) throw PyError("unexpected end of JSON", "ValueError")
                    if (chars[i] == ",") { i += 1; continue }
                    if (chars[i] == "]") { i += 1; return PyValue.List(PyList(items)) }
                    throw PyError("expected ',' or ']' in JSON", "ValueError")
                }
            } finally {
                depth -= 1
            }
        }

        fun string(): String {
            if (i >= chars.size || chars[i] != "\"") throw PyError("expected a JSON string", "ValueError")
            i += 1
            val out = StringBuilder()
            while (i < chars.size) {
                val c = chars[i]
                if (c == "\"") { i += 1; return out.toString() }
                if (c == "\\") {
                    i += 1
                    if (i >= chars.size) break
                    when (chars[i]) {
                        "n" -> out.append("\n")
                        "t" -> out.append("\t")
                        "r" -> out.append("\r")
                        "b" -> out.append("\u0008")
                        "f" -> out.append("\u000C")
                        "u" -> {
                            val start = i + 1
                            val code = if (start + 4 <= chars.size) {
                                parseSwiftInt(chars.subList(start, start + 4).joinToString(""), 16)
                            } else {
                                null
                            }
                            // Like PyMini, a lone surrogate (half of a 😀 pair) is refused.
                            if (code == null || !isScalar(code)) throw PyError("invalid \\u escape in JSON", "ValueError")
                            out.appendCodePoint(code.toInt())
                            i += 4
                        }
                        else -> out.append(chars[i])
                    }
                    i += 1
                    continue
                }
                out.append(c)
                i += 1
            }
            throw PyError("unterminated JSON string", "ValueError")
        }

        fun number(): PyValue {
            val text = StringBuilder()
            var isDouble = false
            while (i < chars.size && chars[i].length == 1 && "0123456789+-.eE".contains(chars[i])) {
                if (chars[i] == "." || chars[i] == "e" || chars[i] == "E") isDouble = true
                text.append(chars[i])
                i += 1
            }
            val t = text.toString()
            if (!isDouble) parseSwiftInt(t)?.let { return PyValue.Int(it) }
            val d = parseSwiftDouble(t) ?: throw PyError("invalid number in JSON", "ValueError")
            return PyValue.Float(d)
        }

        companion object {
            /** Deepest nesting accepted, so a hostile file can't recurse the stack out. */
            const val MAX_DEPTH = 100
        }
    }
}

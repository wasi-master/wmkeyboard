package com.wasimaster.wmkeyboard.core.plugins.python

/*
 * Port of PyLexer.swift. Turns source text into tokens, with Python's
 * significant indentation (synthetic INDENT, DEDENT and NEWLINE tokens) and
 * f-strings split into their pieces. Newlines inside (), [] and {} are ignored,
 * and `#` starts a comment. Like the Swift lexer it walks grapheme clusters.
 */

/** One segment of an f-string as the lexer sees it; the expression stays raw source. */
internal sealed class FRawPart {
    data class Lit(val text: String) : FRawPart()
    data class Expr(val source: String, val conv: String?, val spec: List<FRawPart>?) : FRawPart()
}

/** Token kinds. Indentation is `Indent` / `Dedent`; statement ends are `Newline`. */
internal sealed class Tok {
    data class IntTok(val value: Long) : Tok()
    data class DoubleTok(val value: Double) : Tok()
    data class Str(val value: String) : Tok()
    data class FString(val parts: List<FRawPart>) : Tok()
    data class Name(val name: String) : Tok()
    data class Keyword(val word: String) : Tok()
    data class Op(val op: String) : Tok()
    object Newline : Tok()
    object Indent : Tok()
    object Dedent : Tok()
    object Eof : Tok()
}

/** A token plus its 1-based source line. */
internal class Token(val kind: Tok, val line: Int)

private val pyKeywords = setOf(
    "if", "elif", "else", "while", "for", "in", "def", "return",
    "break", "continue", "pass", "True", "False", "None", "and", "or", "not",
    "lambda", "try", "except", "finally", "raise", "import", "from", "as",
    "is", "del", "global",
)

internal class PyLexer(source: String) {
    // Normalise newlines so \r\n and \r behave like \n.
    private val chars: List<String> = graphemes(source.replace("\r\n", "\n").replace("\r", "\n"))
    private var pos = 0
    private var line = 1
    private val indentStack = arrayListOf(0)
    private var bracketDepth = 0
    private val tokens = ArrayList<Token>()
    private var atLineStart = true

    companion object {
        // Longest first, so `**=` and `//=` beat `**` and `//`.
        private val multiOps = setOf("**=", "//=", "**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=")
        private val singleOps = "+-*/%<>=(),:.[]{}&|^~;".map { it.toString() }.toSet()
        private val stringPrefixes = setOf("f", "F", "r", "R")

        /** Split one placeholder's raw text into expression, `!conversion` and `:spec`. */
        private fun placeholder(body: String, line: Int): FRawPart {
            val chars = graphemes(body)
            var depth = 0
            var quote: String? = null
            var conv: String? = null
            var exprEnd = chars.size
            var specStart: Int? = null
            var i = 0
            while (i < chars.size) {
                val c = chars[i]
                if (quote != null) {
                    if (c == "\\") { i += 2; continue }
                    if (c == quote) quote = null
                } else if (c == "\"" || c == "'") {
                    quote = c
                } else if (c == "{" || c == "[" || c == "(") {
                    depth += 1
                } else if (c == "}" || c == "]" || c == ")") {
                    depth -= 1
                } else if (depth == 0 && c == "!" && i + 1 < chars.size && chars[i + 1] != "=") {
                    // `!r` or `!s`, but only when it ends the expression.
                    val next = chars[i + 1]
                    val after = if (i + 2 < chars.size) chars[i + 2] else ":"
                    if ((next == "r" || next == "s" || next == "a") && after == ":" || i + 2 == chars.size) {
                        conv = next
                        exprEnd = minOf(exprEnd, i)
                        i += 2
                        continue
                    }
                } else if (depth == 0 && c == ":") {
                    exprEnd = minOf(exprEnd, i)
                    specStart = i + 1
                    break
                }
                i += 1
            }
            val source = trimWhitespaces(chars.subList(0, exprEnd).joinToString(""))
            if (source.isEmpty()) throw perr("empty expression in f-string", line)
            val spec = specStart?.let { specParts(chars.subList(it, chars.size).joinToString(""), line) }
            return FRawPart.Expr(source, conv, spec)
        }

        /** A format spec can interpolate too, as in `{value:>{width}}`. */
        private fun specParts(spec: String, line: Int): List<FRawPart> {
            val parts = ArrayList<FRawPart>()
            var lit = StringBuilder()
            val chars = graphemes(spec)
            var i = 0
            while (i < chars.size) {
                if (chars[i] == "{") {
                    if (lit.isNotEmpty()) { parts.add(FRawPart.Lit(lit.toString())); lit = StringBuilder() }
                    var depth = 1
                    val inner = StringBuilder()
                    i += 1
                    while (i < chars.size && depth > 0) {
                        if (chars[i] == "{") depth += 1
                        else if (chars[i] == "}") { depth -= 1; if (depth == 0) break }
                        inner.append(chars[i])
                        i += 1
                    }
                    if (i >= chars.size) throw perr("unterminated format spec", line)
                    i += 1
                    parts.add(placeholder(inner.toString(), line))
                    continue
                }
                lit.append(chars[i])
                i += 1
            }
            if (lit.isNotEmpty()) parts.add(FRawPart.Lit(lit.toString()))
            return parts
        }
    }

    /** Scan the whole source. Always ends with `Eof`. */
    fun tokenize(): List<Token> {
        while (pos < chars.size) {
            if (atLineStart && bracketDepth == 0) {
                handleLineStart()
                if (pos >= chars.size) break
            }
            val c = chars[pos]

            if (c == "\n") {
                pos += 1
                line += 1
                if (bracketDepth == 0) {
                    emitNewline()
                    atLineStart = true
                }
                continue
            }
            if (c == " " || c == "\t") { pos += 1; continue }
            if (c == "#") { skipComment(); continue }
            // A backslash at end of line joins the next physical line.
            if (c == "\\" && peek(1) == "\n") { pos += 2; line += 1; continue }

            val prefix = stringPrefix()
            if (prefix != null) {
                if (prefix.first) lexFString(prefix.second) else lexString(prefix.second)
                continue
            }
            if (c == "\"" || c == "'") { lexString(false); continue }
            if (charIsNumber(c) || (c == "." && (peek(1)?.let { charIsNumber(it) } ?: false))) { lexNumber(); continue }
            if (charIsLetter(c) || c == "_") { lexIdentifier(); continue }

            lexOperator()
        }
        // Close out any open indentation, then EOF.
        if (tokens.isNotEmpty() && tokens.last().kind != Tok.Newline) emitNewline()
        while (indentStack.size > 1) {
            indentStack.removeAt(indentStack.size - 1)
            tokens.add(Token(Tok.Dedent, line))
        }
        tokens.add(Token(Tok.Eof, line))
        return tokens
    }

    /** Recognise a `f"…"` / `r'…'` / `rf"…"` prefix and consume it: (fstring, raw), or null. */
    private fun stringPrefix(): Pair<Boolean, Boolean>? {
        var n = 0
        var isF = false
        var isR = false
        while (true) {
            val c = peek(n) ?: break
            if (!stringPrefixes.contains(c) || n >= 2) break
            if (c == "f" || c == "F") { if (isF) return null; isF = true } else { if (isR) return null; isR = true }
            n += 1
        }
        val quote = peek(n)
        if (n == 0 || (quote != "\"" && quote != "'")) return null
        pos += n
        return Pair(isF, isR)
    }

    // MARK: - Indentation

    private fun handleLineStart() {
        var width = 0
        while (pos < chars.size) {
            val c = chars[pos]
            if (c == " ") { width += 1; pos += 1 }
            else if (c == "\t") { width += 8 - (width % 8); pos += 1 }
            else break
        }
        // Blank or comment-only line: not significant for indentation.
        if (pos >= chars.size || chars[pos] == "\n" || chars[pos] == "#") {
            atLineStart = false
            return
        }
        atLineStart = false
        val current = indentStack.last()
        if (width > current) {
            indentStack.add(width)
            tokens.add(Token(Tok.Indent, line))
        } else if (width < current) {
            while (indentStack.isNotEmpty() && width < indentStack.last()) {
                indentStack.removeAt(indentStack.size - 1)
                tokens.add(Token(Tok.Dedent, line))
            }
            if (indentStack.lastOrNull() != width) throw perr("inconsistent indentation", line)
        }
    }

    private fun emitNewline() {
        // Collapse runs of blank lines into a single logical NEWLINE.
        val last = tokens.lastOrNull()?.kind
        if (last == Tok.Newline || last == Tok.Indent) return
        if (tokens.isEmpty()) return
        tokens.add(Token(Tok.Newline, line))
    }

    private fun skipComment() {
        while (pos < chars.size && chars[pos] != "\n") pos += 1
    }

    // MARK: - Literals

    private fun lexNumber() {
        // Base-prefixed integers: 0x1f, 0o17, 0b1010.
        val p = peek(1)
        if (chars[pos] == "0" && p != null && "xXoObB".contains(p) && p.length == 1) {
            val radix = if (p == "x" || p == "X") 16 else if (p == "o" || p == "O") 8 else 2
            pos += 2
            val digits = StringBuilder()
            while (pos < chars.size && (charIsHexDigit(chars[pos]) || chars[pos] == "_")) {
                if (chars[pos] != "_") digits.append(chars[pos])
                pos += 1
            }
            val v = parseSwiftInt(digits.toString(), radix) ?: throw perr("invalid number", line)
            tokens.add(Token(Tok.IntTok(v), line))
            return
        }
        val s = StringBuilder()
        var isDouble = false
        while (pos < chars.size) {
            val c = chars[pos]
            if (charIsNumber(c)) { s.append(c); pos += 1 }
            else if (c == "_") pos += 1 // digit grouping
            else if (c == ".") {
                if (isDouble) break
                isDouble = true; s.append(c); pos += 1
            } else if (c == "e" || c == "E") {
                // Only an exponent when a digit or sign follows, so `1e` alone
                // doesn't eat the identifier after it.
                val next = peek(1)
                val signed = next == "+" || next == "-"
                val ok = (next?.let { charIsNumber(it) } ?: false) ||
                    (signed && (peek(2)?.let { charIsNumber(it) } ?: false))
                if (!ok) break
                isDouble = true; s.append(c); pos += 1
                if (signed) { s.append(chars[pos]); pos += 1 }
            } else break
        }
        val text = s.toString()
        if (isDouble) {
            val d = parseSwiftDouble(text) ?: throw perr("invalid number '$text'", line)
            tokens.add(Token(Tok.DoubleTok(d), line))
        } else {
            val i = parseSwiftInt(text)
            if (i != null) {
                tokens.add(Token(Tok.IntTok(i), line))
            } else {
                val d = parseSwiftDouble(text) ?: throw perr("invalid number '$text'", line)
                tokens.add(Token(Tok.DoubleTok(d), line))
            }
        }
    }

    /** Consume a single quote or a matching triple-quote delimiter: (quote, length). */
    private fun openString(): Pair<String, Int> {
        val quote = chars[pos]
        val length = if (peek(1) == quote && peek(2) == quote) 3 else 1
        pos += length
        return Pair(quote, length)
    }

    private fun closesString(quote: String, length: Int): Boolean =
        chars[pos] == quote && (length == 1 || (peek(1) == quote && peek(2) == quote))

    /**
     * A backslash followed by a newline joins lines without adding text. In a raw
     * string the backslash and the character after it are both kept.
     */
    private fun scanEscape(raw: Boolean): String {
        pos += 1
        if (pos >= chars.size) return if (raw) "\\" else ""
        val c = chars[pos]
        pos += 1
        if (c == "\n") { line += 1; return if (raw) "\\\n" else "" }
        if (raw) return "\\" + c
        return escape(c)
    }

    private fun lexString(raw: Boolean) {
        val startLine = line
        val (quote, length) = openString()
        val s = StringBuilder()
        while (pos < chars.size) {
            val c = chars[pos]
            if (c == "\\") {
                s.append(scanEscape(raw))
            } else if (closesString(quote, length)) {
                pos += length
                tokens.add(Token(Tok.Str(s.toString()), startLine))
                return
            } else {
                if (c == "\n") {
                    if (length != 3) throw perr("unterminated string", startLine)
                    line += 1
                }
                s.append(c)
                pos += 1
            }
        }
        throw perr("unterminated string", startLine)
    }

    /** Unrecognised escapes keep the backslash, as Python does, so ¯\_(ツ)_/¯ survives. */
    private fun escape(c: String): String = when (c) {
        "n" -> "\n"
        "t" -> "\t"
        "r" -> "\r"
        "0" -> "\u0000"
        "\\" -> "\\"
        "'" -> "'"
        "\"" -> "\""
        else -> "\\" + c
    }

    /** Lex `f"..."` into literal runs and `{expr!conv:spec}` placeholders. */
    private fun lexFString(raw: Boolean) {
        val startLine = line
        val (quote, length) = openString()
        val parts = ArrayList<FRawPart>()
        var lit = StringBuilder()
        while (pos < chars.size) {
            val c = chars[pos]
            if (c == "\\") {
                lit.append(scanEscape(raw))
                continue
            }
            if (closesString(quote, length)) {
                pos += length
                if (lit.isNotEmpty()) parts.add(FRawPart.Lit(lit.toString()))
                tokens.add(Token(Tok.FString(parts), startLine))
                return
            }
            if (c == "{") {
                if (peek(1) == "{") { lit.append("{"); pos += 2; continue }
                if (lit.isNotEmpty()) { parts.add(FRawPart.Lit(lit.toString())); lit = StringBuilder() }
                pos += 1
                val body = scanPlaceholder()
                parts.add(placeholder(body, line))
                continue
            }
            if (c == "}") {
                if (peek(1) == "}") { lit.append("}"); pos += 2; continue }
                throw perr("single '}' in f-string", line)
            }
            if (c == "\n") {
                if (length != 3) throw perr("unterminated string", startLine)
                line += 1
            }
            lit.append(c)
            pos += 1
        }
        throw perr("unterminated string", startLine)
    }

    /** Read the raw text of one `{...}` placeholder, minding nesting and quotes. */
    private fun scanPlaceholder(): String {
        val src = StringBuilder()
        var depth = 1
        var quote: String? = null
        while (pos < chars.size) {
            val c = chars[pos]
            if (c == "\n") line += 1
            if (quote != null) {
                if (c == "\\") {
                    src.append(c); pos += 1
                    if (pos < chars.size) { src.append(chars[pos]); pos += 1 }
                    continue
                }
                if (c == quote) quote = null
            } else if (c == "\"" || c == "'") {
                quote = c
            } else if (c == "{" || c == "[" || c == "(") {
                depth += 1
            } else if (c == "]" || c == ")") {
                depth -= 1
            } else if (c == "}") {
                depth -= 1
                if (depth == 0) { pos += 1; return src.toString() }
            }
            src.append(c)
            pos += 1
        }
        throw perr("unterminated f-string expression", line)
    }

    private fun lexIdentifier() {
        val s = StringBuilder()
        while (pos < chars.size && (charIsLetter(chars[pos]) || charIsNumber(chars[pos]) || chars[pos] == "_")) {
            s.append(chars[pos]); pos += 1
        }
        val word = s.toString()
        tokens.add(Token(if (pyKeywords.contains(word)) Tok.Keyword(word) else Tok.Name(word), line))
    }

    private fun lexOperator() {
        // Try the longest operators first.
        for (width in intArrayOf(3, 2)) {
            if (pos + width > chars.size) continue
            val slice = chars.subList(pos, pos + width).joinToString("")
            if (multiOps.contains(slice)) {
                tokens.add(Token(Tok.Op(slice), line))
                pos += width
                return
            }
        }
        val c = chars[pos]
        if (!singleOps.contains(c)) throw perr("unexpected character '$c'", line)
        when (c) {
            "(", "[", "{" -> bracketDepth += 1
            ")", "]", "}" -> bracketDepth = maxOf(0, bracketDepth - 1)
        }
        tokens.add(Token(Tok.Op(c), line))
        pos += 1
    }

    private fun peek(n: Int): String? {
        val i = pos + n
        return if (i < chars.size) chars[i] else null
    }
}

package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.core.plugins.PyApiEntry
import com.wasimaster.wmkeyboard.core.plugins.PyCompletionItem
import com.wasimaster.wmkeyboard.core.plugins.PyDiagnostic
import com.wasimaster.wmkeyboard.core.plugins.PyHostShape
import com.wasimaster.wmkeyboard.core.plugins.PyNameStyle
import com.wasimaster.wmkeyboard.core.plugins.PyOutlineItem
import com.wasimaster.wmkeyboard.core.plugins.PySeverity
import com.wasimaster.wmkeyboard.core.plugins.PyTokenKind
import com.wasimaster.wmkeyboard.core.plugins.PyTokens
import com.wasimaster.wmkeyboard.core.plugins.PythonNative
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling

/** What one pass of the checks found, as the editor draws it. */
internal data class PythonInspection(val diagnostics: List<CodeDiagnostic>, val outline: List<PyOutlineItem>)

/**
 * Python, as the plugin editor needs it: coloured, tidied, checked and paired
 * up, by the tooling half of `libwm_python` ([PythonTooling]).
 *
 * Colours and brackets come from the lexer's tokens, never a parse tree: a
 * plugin being typed is broken Python most of the time, and colouring that went
 * dark whenever the file stopped parsing would be dark while the user is
 * typing. Until the library is downloaded every answer is empty and the editor
 * is a plain text field.
 */
internal object PythonCode : CodeLanguage {

    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"

    /** The last document lexed: colours and brackets ask about the same string on every change. */
    @Volatile
    private var cached: PyTokens? = null

    private fun tokensOf(source: String): PyTokens {
        cached?.let { if (it.source === source) return it }
        return PythonTooling.tokens(source).also { cached = it }
    }

    override val lineComment: String get() = "#"

    override val smartRules: CodeSmartRules get() = PythonSmartRules

    /** Whether the interpreter would load [source] as far as syntax goes. True while the library is missing. */
    fun parses(source: String): Boolean = !PythonTooling.ready || PythonNative.nativeCheck(source) == null

    /**
     * One pass over the tokens. Plain names and operators carry no span, and
     * neighbouring tokens of one colour share one span, because the span count
     * is what the text layout pays for.
     */
    override fun highlight(source: String, colors: CodeColors): AnnotatedString {
        val tokens = tokensOf(source)
        val styles = Styles(colors)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var pending: SpanStyle? = null
        var pendingStart = 0
        var pendingEnd = 0
        for (index in 0 until tokens.size) {
            val kind = tokens.kind(index)
            if (kind == PyTokenKind.WHITESPACE || kind == PyTokenKind.NEWLINE) continue
            val style = styleOf(tokens, index, kind, styles)
            if (style != null && style === pending) {
                pendingEnd = tokens.end(index)
                continue
            }
            pending?.let { spans += AnnotatedString.Range(it, pendingStart, pendingEnd) }
            pending = style
            pendingStart = tokens.start(index)
            pendingEnd = tokens.end(index)
        }
        pending?.let { spans += AnnotatedString.Range(it, pendingStart, pendingEnd) }
        return AnnotatedString(source, spans)
    }

    override fun format(source: String): String = PythonTooling.format(source)

    /** Everything wrong with [source] for a plugin shaped like [host], and its outline. */
    fun analyse(source: String, host: PyHostShape?): PythonInspection {
        val analysis = PythonTooling.analyse(source, host)
        return PythonInspection(analysis.diagnostics.map { it.toCodeDiagnostic() }, analysis.outline)
    }

    override fun diagnostics(source: String): List<CodeDiagnostic> = analyse(source, null).diagnostics

    fun apiAt(source: String, caret: Int): PyApiEntry? = PythonTooling.apiAt(source, caret)

    override fun foldRegions(source: String): List<TextRange> =
        PythonTooling.analyse(source, null).folds.map { TextRange(it.start, it.end) }

    override fun completions(source: String, caret: Int, explicit: Boolean): CodeCompletions? {
        val found = PythonTooling.complete(source, caret, explicit, null) ?: return null
        if (found.items.isEmpty()) return null
        return CodeCompletions(TextRange(found.replace.start, found.replace.end), found.items.map { it.toCodeCompletion() })
    }

    /** The first thing the lexer alone can see is wrong: an unclosed string, or a stray character. */
    override fun problem(source: String): CodeProblem? {
        val tokens = tokensOf(source)
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) == PyTokenKind.UNKNOWN || tokens.unterminated(index)) return CodeProblem(tokens.start(index))
        }
        return null
    }

    /** Round, square and curly brackets that are code; one inside a string or comment is not a token of its own. */
    override fun brackets(source: String): List<Int> {
        val tokens = tokensOf(source)
        val found = ArrayList<Int>()
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) != PyTokenKind.OPERATOR) continue
            val start = tokens.start(index)
            if (tokens.end(index) - start == 1 && (source[start] in OPENERS || source[start] in CLOSERS)) found += start
        }
        return found
    }

    /** Pairs only brackets of one kind, so a `(` never boxes against a `]`. */
    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? {
        if (brackets.isEmpty()) return null
        // The bracket behind the caret wins, which is where a caret sits after
        // the user has typed one.
        val behind = if (caret > 0) brackets.binarySearch(caret - 1) else -1
        val index = if (behind >= 0) behind else brackets.binarySearch(caret).takeIf { it >= 0 } ?: return null
        val at = brackets[index]
        if (at >= source.length) return null
        val bracket = source[at]
        val opener = OPENERS.indexOf(bracket)
        if (opener >= 0) {
            val closer = CLOSERS[opener]
            var depth = 0
            for (i in index until brackets.size) {
                val here = source[brackets[i]]
                if (here == bracket) depth++
                if (here == closer && --depth == 0) return at to brackets[i]
            }
            return null
        }
        val openerOfThis = OPENERS[CLOSERS.indexOf(bracket)]
        var depth = 0
        for (i in index downTo 0) {
            val here = source[brackets[i]]
            if (here == bracket) depth++
            if (here == openerOfThis && --depth == 0) return brackets[i] to at
        }
        return null
    }

    private fun styleOf(tokens: PyTokens, index: Int, kind: PyTokenKind, styles: Styles): SpanStyle? = when (kind) {
        PyTokenKind.COMMENT -> styles.comment
        PyTokenKind.STRING, PyTokenKind.FSTRING -> if (tokens.unterminated(index)) styles.problem else styles.string
        PyTokenKind.NUMBER -> styles.number
        PyTokenKind.KEYWORD, PyTokenKind.SOFT_KEYWORD -> styles.keyword
        PyTokenKind.UNKNOWN -> styles.problem
        PyTokenKind.NAME -> when (tokens.nameStyle(index)) {
            PyNameStyle.FUNCTION -> styles.function
            PyNameStyle.ATTRIBUTE, PyNameStyle.API -> styles.key
            PyNameStyle.BUILTIN -> styles.function
            PyNameStyle.CONSTANT -> styles.number
            PyNameStyle.PARAMETER, PyNameStyle.PLAIN -> null
        }
        PyTokenKind.OPERATOR, PyTokenKind.WHITESPACE, PyTokenKind.NEWLINE -> null
    }

    /** One instance of each style per pass, shared by every span of that colour. */
    private class Styles(colors: CodeColors) {
        val comment = SpanStyle(color = colors.comment)
        val string = SpanStyle(color = colors.string)
        val number = SpanStyle(color = colors.number)
        val keyword = SpanStyle(color = colors.keyword)
        val function = SpanStyle(color = colors.function)
        val key = SpanStyle(color = colors.key)
        val problem = SpanStyle(color = colors.problem)
    }
}

/** Python's pairs, both quote marks, and a block after every line that ends in a colon. */
internal val PythonSmartRules = CodeSmartRules(
    pairs = mapOf('(' to ')', '{' to '}', '[' to ']', '"' to '"', '\'' to '\''),
    quotes = setOf('"', '\''),
    inert = ::pythonInert,
    opensBlock = ::pythonOpensBlock,
    indentWidth = 4,
)

/**
 * Whether a caret at [at] is inside a string or a comment, judged by lexing
 * only the text before it, so the answer is exact.
 */
internal fun pythonInert(text: String, at: Int): Boolean {
    if (at <= 0) return false
    val tokens = PythonTooling.tokens(text.substring(0, at.coerceAtMost(text.length)))
    if (tokens.size == 0) return false
    val last = tokens.size - 1
    return when (tokens.kind(last)) {
        PyTokenKind.COMMENT -> true
        PyTokenKind.STRING, PyTokenKind.FSTRING -> tokens.unterminated(last)
        else -> false
    }
}

/** Whether [line] ends a block header: its last code token is a colon. */
internal fun pythonOpensBlock(line: String): Boolean {
    val tokens = PythonTooling.tokens(line)
    var last = tokens.size - 1
    while (last >= 0 && !tokens.kind(last).isCode) last--
    return last >= 0 && tokens.kind(last) == PyTokenKind.OPERATOR && tokens.matches(last, ":")
}

/** A suggestion as the code field lists it. */
internal fun PyCompletionItem.toCodeCompletion(): CodeCompletion = CodeCompletion(
    label = label,
    kind = when (kind) {
        "variable", "parameter" -> CodeCompletionKind.VARIABLE
        "function", "method" -> CodeCompletionKind.FUNCTION
        "module" -> CodeCompletionKind.MODULE
        "constant" -> CodeCompletionKind.CONSTANT
        "field" -> CodeCompletionKind.FIELD
        "keyword" -> CodeCompletionKind.KEYWORD
        "snippet" -> CodeCompletionKind.SNIPPET
        else -> CodeCompletionKind.VALUE
    },
    insert = insert,
    caret = if (caret in 0..insert.length) caret else insert.length,
    detail = detail,
)

/** A problem as the code field draws it, its words from [pyDiagnosticMessage]. */
internal fun PyDiagnostic.toCodeDiagnostic(): CodeDiagnostic = CodeDiagnostic(
    range = TextRange(span.start, span.end),
    severity = when (severity) {
        PySeverity.ERROR -> CodeSeverity.ERROR
        PySeverity.WARNING -> CodeSeverity.WARNING
        PySeverity.INFO -> CodeSeverity.INFO
    },
    messageRes = pyDiagnosticMessage(code),
    arg1 = args.getOrNull(0),
    arg2 = args.getOrNull(1),
)

/**
 * The symbol keys, in the order a Python author reaches for them: what a phone
 * keyboard hides behind a layer switch first, then the keywords.
 */
internal val PythonAccessoryKeys: List<AccessoryKey> = listOf(
    AccessoryKey("=", "=", "=="),
    AccessoryKey("(", "("),
    AccessoryKey(")", ")"),
    AccessoryKey(":", ":"),
    AccessoryKey("\"", "\""),
    AccessoryKey(",", ","),
    AccessoryKey(".", "."),
    AccessoryKey("[", "["),
    AccessoryKey("]", "]"),
    AccessoryKey("{", "{"),
    AccessoryKey("}", "}"),
    AccessoryKey("'", "'"),
    AccessoryKey("#", "# "),
    AccessoryKey("!=", "!="),
    AccessoryKey("<", "<", "<="),
    AccessoryKey(">", ">", ">="),
    AccessoryKey("+", "+", "+="),
    AccessoryKey("-", "-", "-="),
    AccessoryKey("*", "*", "**"),
    AccessoryKey("/", "/", "//"),
    AccessoryKey("%", "%"),
    AccessoryKey("_", "_"),
    AccessoryKey("def", "def "),
    AccessoryKey("return", "return "),
    AccessoryKey("if", "if "),
    AccessoryKey("elif", "elif "),
    AccessoryKey("else", "else:"),
    AccessoryKey("for", "for "),
    AccessoryKey("in", "in "),
    AccessoryKey("while", "while "),
    AccessoryKey("global", "global "),
    AccessoryKey("None", "None"),
    AccessoryKey("True", "True"),
    AccessoryKey("False", "False"),
    AccessoryKey("and", "and "),
    AccessoryKey("or", "or "),
    AccessoryKey("not", "not "),
)

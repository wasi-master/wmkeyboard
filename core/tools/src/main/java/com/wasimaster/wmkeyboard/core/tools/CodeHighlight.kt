package com.wasimaster.wmkeyboard.core.tools

/**
 * Colours for a fenced code block: comments, strings, numbers, keywords and
 * type names. One tokenizer for every language, told apart only by which
 * comment markers they use and a shared keyword list, because a note or a chat
 * answer needs code to be readable at a glance, not an IDE's precision.
 *
 * No Compose, like [AiMarkdownBlocks]: the caller maps [Kind] to colours.
 */
object CodeHighlight {

    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD, TYPE }

    /** [kind] over `[start, end)` of the code. */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val HASH_COMMENTS = setOf(
        "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "console", "yaml", "yml", "toml",
        "perl", "pl", "r", "php", "make", "makefile", "dockerfile", "powershell", "ps1", "nix", "conf", "ini",
    )
    private val DASH_COMMENTS = setOf("sql", "lua", "haskell", "hs", "elm", "ada")
    private val SLASH_COMMENTS_OFF = setOf(
        "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "console", "yaml", "yml", "toml",
        "sql", "lua", "haskell", "hs", "html", "xml", "svg", "markdown", "md", "ini", "conf",
    )
    private val MARKUP = setOf("html", "xml", "svg", "vue", "markdown", "md")

    private val KEYWORDS = (
        "abstract and as assert async await break case catch class const continue def default defer del delete do " +
            "elif else enum except export extends extern false final finally fn for foreach from func function get go " +
            "goto if impl implements import in instanceof interface is lambda let loop match mod module mut namespace " +
            "new nil none not null object of or override package pass private protected pub public raise readonly " +
            "record ref require require_once return sealed select self set static struct super switch then this " +
            "throw throws trait true try type typeof union unsafe use using val var void when where while with yield " +
            "int long short byte char float double bool boolean string str echo local end begin elsif unless until " +
            "fun data inline internal open lateinit suspend companion operator infix reified crossinline noinline " +
            "True False None"
        ).split(' ').toHashSet()

    private val TRIPLE = mapOf('"' to "\"\"\"", '\'' to "'''")

    fun tokens(code: String, language: String): List<Token> {
        val lang = language.lowercase()
        if (lang == "markdown" || lang == "md") return markdownTokens(code)
        // A key is set apart from a value: the names are what a reader scans for.
        val keyed = lang == "json" || lang == "jsonc" || lang == "json5"
        val hash = lang in HASH_COMMENTS
        val dash = lang in DASH_COMMENTS
        val slash = lang !in SLASH_COMMENTS_OFF
        val markup = lang in MARKUP
        val css = lang == "css" || lang == "scss" || lang == "less"
        val out = ArrayList<Token>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            val start = i
            when {
                slash && c == '/' && i + 1 < n && code[i + 1] == '/' && !css -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                (slash || css) && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    i = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    out += Token(start, i, Kind.COMMENT)
                }
                markup && code.startsWith("<!--", i) -> {
                    i = code.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    out += Token(start, i, Kind.COMMENT)
                }
                hash && c == '#' && (i == 0 || code[i - 1].isWhitespace()) -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                dash && c == '-' && i + 1 < n && code[i + 1] == '-' -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    // An apostrophe in prose-like markup is not a string.
                    if (c == '\'' && markup) {
                        i++
                        continue
                    }
                    // Python's docstrings and Kotlin's raw strings run to
                    // their closing triple, across lines.
                    if (c != '`' && code.startsWith(TRIPLE.getValue(c), i)) {
                        i = code.indexOf(TRIPLE.getValue(c), i + 3).let { if (it < 0) n else it + 3 }
                        out += Token(start, i, Kind.STRING)
                        continue
                    }
                    i++
                    while (i < n && code[i] != c) {
                        if (code[i] == '\\') i++
                        // Only backticks (template strings) run across lines.
                        if (i < n && code[i] == '\n' && c != '`') break
                        i++
                    }
                    if (i < n && code[i] == c) i++
                    var colon = i
                    while (colon < n && (code[colon] == ' ' || code[colon] == '\t')) colon++
                    out += Token(start, i, if (keyed && colon < n && code[colon] == ':') Kind.TYPE else Kind.STRING)
                }
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                    out += Token(start, i, Kind.NUMBER)
                }
                c.isLetter() || c == '_' || c == '$' || c == '@' -> {
                    i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val word = code.substring(start, i)
                    when {
                        word in KEYWORDS -> out += Token(start, i, Kind.KEYWORD)
                        word[0] == '@' -> out += Token(start, i, Kind.TYPE)
                        word[0].isUpperCase() && word.length > 1 && !css -> out += Token(start, i, Kind.TYPE)
                    }
                }
                else -> i++
            }
        }
        return out
    }

    private val MD_HEADING = Regex("""(?m)^#{1,6} .*$""")
    private val MD_FENCE = Regex("""(?ms)^ {0,3}```.*?(?:^ {0,3}```[ \t]*$|\z)""")
    private val MD_CODE_SPAN = Regex("""`[^`\n]+`""")
    private val MD_STRONG = Regex("""(\*\*|__)(?=\S).+?(?<=\S)\1|(?<![\w*])\*(?=\S)[^*\n]+?(?<=\S)\*(?!\*)|~~[^~\n]+~~""")
    private val MD_LINK_TARGET = Regex("""(?<=\])\([^)\s]+(?: "[^"]*")?\)|<https?://[^>\s]+>""")
    private val MD_MARKER = Regex("""(?m)^\s{0,6}(?:[-*+]|\d{1,3}[.)]|>)(?= )|^\s*\|?(?:\s*:?-{3,}:?\s*\|?)+\s*$""")

    /**
     * Markdown's source, coloured by its marks rather than by words: headings
     * as keywords, emphasis as types, code as strings, link targets as numbers
     * and list, quote and table markers as comments. A word like "if" in a
     * sentence stays the text colour.
     */
    private fun markdownTokens(code: String): List<Token> {
        val out = ArrayList<Token>()
        val taken = BooleanArray(code.length)
        fun add(range: IntRange, kind: Kind) {
            if (range.isEmpty() || (range.first until range.last + 1).any { taken[it] }) return
            for (k in range) taken[k] = true
            out += Token(range.first, range.last + 1, kind)
        }
        // Code first: nothing inside a fence or a code span is Markdown.
        MD_FENCE.findAll(code).forEach { add(it.range, Kind.STRING) }
        MD_CODE_SPAN.findAll(code).forEach { add(it.range, Kind.STRING) }
        MD_HEADING.findAll(code).forEach { add(it.range, Kind.KEYWORD) }
        MD_LINK_TARGET.findAll(code).forEach { add(it.range, Kind.NUMBER) }
        MD_STRONG.findAll(code).forEach { add(it.range, Kind.TYPE) }
        MD_MARKER.findAll(code).forEach { add(it.range, Kind.COMMENT) }
        out.sortBy { it.start }
        return out
    }
}

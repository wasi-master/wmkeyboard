package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.feature.R

/**
 * Reads one web page as plain text, for the AI tool's `web_fetch` (#470).
 *
 * Not a browser and not trying to be: there is no JavaScript, so a page that
 * paints itself from an API comes back nearly empty, and a model that is told
 * the page said nothing is better off than one handed a wall of script. What
 * this does do is the part a language model actually needs — drop the markup,
 * keep the prose, and stop at a budget, because the whole point of the tool is
 * to spend a bounded number of tokens on a page rather than the page's own
 * size in tokens.
 */
object WebFetchClient {

    /**
     * Characters of page text handed to the model. Roughly 1,500 tokens: enough
     * for an article's argument, small enough that three fetches in one run
     * still leave the model room to answer. Tool results are also replayed in
     * every later round of the same run, so this is paid for more than once.
     */
    const val MAX_CHARS = 6_000

    private const val TIMEOUT_MS = 15_000

    /** What came back: [text] is already trimmed to the budget. */
    data class Page(
        val title: String,
        val text: String,
        /** The page was longer than the budget and this is its beginning. */
        val truncated: Boolean,
    )

    /**
     * Blocking; call on an IO dispatcher. Throws [ToolHttpException] on
     * anything the user should read.
     *
     * [url] comes from a model, so it is checked rather than trusted: only
     * http and https, because a `file://` the model invented would have this
     * reading the app's own storage and handing it back to a cloud provider.
     */
    fun fetch(url: String, maxChars: Int = MAX_CHARS, source: NetSource = NetSource.AI_TOOLS): Page {
        val target = normalize(url) ?: throw ToolHttpException(R.string.ftools_web_fetch_bad_url)
        val body = ToolHttp.get(
            url = target,
            timeoutMs = TIMEOUT_MS,
            headers = mapOf("Accept" to "text/html,text/plain;q=0.9,*/*;q=0.1"),
            source = source,
            route = NetLog.pathOf(target),
        )
        return readable(body, maxChars)
    }

    /**
     * The address to request, or null when it is not one we will fetch.
     * A bare `example.com/x` is read as https, because that is how a model
     * writes an address it copied out of a search result's display line.
     */
    internal fun normalize(url: String): String? {
        val trimmed = url.trim().trim('<', '>', '"', '\'')
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // Anything else that names a scheme is refused outright rather
            // than repaired: `file:`, `content:` and `javascript:` are the
            // ones that matter and none of them is a typo for https.
            SCHEME.containsMatchIn(trimmed) -> return null
            else -> "https://$trimmed"
        }
        val host = withScheme.substringAfter("://").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        return withScheme.takeIf { host.isNotEmpty() && '.' in host }
    }

    /** The page's title and prose, cut to [maxChars]. */
    internal fun readable(html: String, maxChars: Int = MAX_CHARS): Page {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decode(collapse(stripTags(it))) }.orEmpty()
        val text = toText(html)
        val cut = text.length > maxChars
        return Page(
            title = title,
            text = if (cut) text.take(maxChars).substringBeforeLast(' ') else text,
            truncated = cut,
        )
    }

    /**
     * Markup to prose. Order matters: the blocks whose *contents* are not text
     * go first, then the tags that mean a line break become one, and only then
     * is everything still in angle brackets removed — strip the tags first and
     * a page comes back as one unreadable paragraph with its scripts inlined.
     */
    private fun toText(html: String): String {
        var s = html
        s = COMMENT.replace(s, " ")
        s = NON_TEXT.replace(s, " ")
        s = BREAK.replace(s, "\n")
        s = stripTags(s)
        s = decode(s)
        return collapseLines(s)
    }

    private fun stripTags(s: String) = TAG.replace(s, " ")

    /** The handful of entities that actually show up in prose. */
    private fun decode(s: String): String {
        if ('&' !in s) return s
        var out = s
        for ((entity, char) in ENTITIES) out = out.replace(entity, char)
        return NUMERIC.replace(out) { match ->
            val digits = match.groupValues[1]
            val code = if (digits.startsWith("x") || digits.startsWith("X")) {
                digits.drop(1).toIntOrNull(HEX)
            } else {
                digits.toIntOrNull()
            }
            code?.takeIf { it in 1..Character.MAX_CODE_POINT }
                ?.let { String(Character.toChars(it)) } ?: match.value
        }
    }

    private fun collapse(s: String) = s.replace(WHITESPACE, " ").trim()

    /**
     * Blank lines go, and runs of spaces inside a line go, but the line breaks
     * themselves stay: they are what is left of the page's structure, and a
     * model reads a list far better as lines than as one sentence.
     */
    private fun collapseLines(s: String): String =
        s.lineSequence()
            .map { collapse(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 28 KB across this file's patterns.
    private val SCHEME by lazy { Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""") }
    private val COMMENT by lazy { Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL) }
    private val NON_TEXT by lazy {
        Regex(
            """<(script|style|noscript|svg|template|head|iframe)\b[^>]*>.*?</\1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
    private val BREAK by lazy {
        Regex(
            """</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|blockquote|pre|table)\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val TAG by lazy { Regex("""<[^>]*>""", RegexOption.DOT_MATCHES_ALL) }
    private val TITLE by lazy { Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }
    private val WHITESPACE by lazy { Regex("""[^\S\n]+""") }
    private val NUMERIC by lazy { Regex("""&#([xX]?[0-9a-fA-F]+);""") }

    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'",
        "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”",
    )

    private const val HEX = 16
}

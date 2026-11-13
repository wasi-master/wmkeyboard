package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.core.selection.CodeCases
import java.util.Locale

/**
 * Modifiers on the clipboard and the selection inside a snippet:
 * `{clip:clean}`, `{clip:singleline:title}`, `{selection:snake}` and so on
 * (competitor gap list item 8, LeanType's clipboard modifiers).
 *
 * A chain is applied left to right and separated by colons, so
 * `{clip:clean:singleline}` strips the citation marks first and then joins the
 * lines. An unknown modifier is skipped rather than failing the snippet, the
 * way an unknown `{token}` stays untouched.
 *
 * `replace(old,new)` swaps literal text, not a pattern: a snippet should not
 * be able to hang the keyboard with a runaway expression, and the plain swap
 * covers what people use it for (a separator, a stray prefix). A colon inside
 * its brackets belongs to the arguments, not the chain.
 */
internal object TextModifiers {

    /** `{clip:…}` or `{selection:…}`, with the chain in group 2. */
    val TOKEN = Regex("""\{(clip|selection):([^}\n]{1,200})\}""")

    /** [text] with every modifier in [chain] applied in turn. */
    fun apply(text: String, chain: String, locale: Locale = Locale.getDefault()): String =
        split(chain).fold(text) { acc, modifier -> one(acc, modifier.trim(), locale) }

    /** [chain] cut at the colons that sit outside brackets. */
    internal fun split(chain: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in chain) {
            when {
                c == '(' -> depth++
                c == ')' && depth > 0 -> depth--
            }
            if (c == ':' && depth == 0) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        out += current.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun one(text: String, modifier: String, locale: Locale): String {
        val name = modifier.substringBefore('(').trim().lowercase(Locale.ROOT)
        return when (name) {
            "clean" -> clean(text)
            "singleline" -> text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            "trim" -> text.trim()
            "upper" -> text.uppercase(locale)
            "lower" -> text.lowercase(locale)
            "title" -> title(text, locale)
            "camel" -> CodeCases.camel(text) ?: text
            "snake" -> CodeCases.snake(text) ?: text
            "kebab", "slug" -> CodeCases.kebab(text) ?: text
            "constant" -> CodeCases.constant(text) ?: text
            "unquote" -> unquote(text)
            "nourl" -> URL.replace(text, "").replace(SPACES, " ").trim()
            "replace" -> replace(text, modifier)
            else -> text
        }
    }

    /**
     * Wikipedia-style reference marks gone: `[1]`, `[12]`, `[a]`, `[note 3]`,
     * `[citation needed]` and friends, then the spaces they leave tidied.
     */
    private fun clean(text: String): String =
        CITATION.replace(text, "")
            .replace(SPACE_BEFORE_MARK) { it.groupValues[1] }
            .replace(SPACES, " ")
            .trim()

    private fun title(text: String, locale: Locale): String =
        WORD.replace(text.lowercase(locale)) { match ->
            match.value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }

    /** One layer of matching quotes around the whole text taken off. */
    private fun unquote(text: String): String {
        val t = text.trim()
        if (t.length < 2) return text
        val close = QUOTES[t.first()] ?: return text
        return if (t.last() == close) t.substring(1, t.length - 1) else text
    }

    /** `replace(old,new)`: every [old] in [text] swapped for [new], literally. */
    private fun replace(text: String, modifier: String): String {
        val args = modifier.substringAfter('(', "").substringBeforeLast(')', "")
        val comma = args.indexOf(',')
        if (comma <= 0) return text
        val old = args.substring(0, comma)
        val new = args.substring(comma + 1)
        return text.replace(old, new)
    }

    private val CITATION = Regex(
        """\[(?:\d{1,4}|[a-z]|note \d{1,3}|citation needed|clarification needed|who\?|when\?|according to whom\?|""" +
            """dubious(?: [–-] discuss)?|failed verification|better source needed|page needed|verification needed)\]""",
        RegexOption.IGNORE_CASE,
    )
    private val SPACE_BEFORE_MARK = Regex(""" +([.,;:!?)])""")
    private val SPACES = Regex("""[ \t]{2,}""")
    private val URL = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""\p{L}[\p{L}\p{M}'’]*""")
    private val QUOTES = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’', '«' to '»', '„' to '“', '「' to '」')
}

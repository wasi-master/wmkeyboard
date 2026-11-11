package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.util.Locale

/** Where a search shortcut sends its words (#1242). */
enum class SearchTarget(val id: String, val tool: ToolbarTool?) {
    GIF("gif", ToolbarTool.GIF),
    STICKER("sticker", ToolbarTool.STICKER),
    WIKIPEDIA("wikipedia", ToolbarTool.WIKIPEDIA),
    DICTIONARY("dictionary", ToolbarTool.DICTIONARY),
    WEB("web", ToolbarTool.WEB_SEARCH),

    /** A saved contact whose name starts with the words, to call or text. */
    CONTACTS("contacts", null),
    ;

    companion object {
        fun of(id: String?): SearchTarget? = entries.firstOrNull { it.id == id }
    }
}

/** One shortcut: typing [prefix] and then some words searches [target] for them. */
data class SearchTrigger(val prefix: String, val target: SearchTarget)

/**
 * Search shortcuts (#1242): "@gif cats" on the strip offers a GIF search for
 * "cats", "@wiki tagore" a Wikipedia search, and so on, the way a Telegram
 * inline bot is called by name. The user picks the prefixes, and more than
 * one can lead to the same place, so "@gif" and "!" can both mean GIFs.
 *
 * A prefix counts only at the start of the text or after a space, so an
 * address such as "me@web.de" never reads as a search. A prefix that ends in
 * a letter or digit needs a space before the words ("@gif cats", not
 * "@gifcats"); one that ends in a symbol needs none ("!cats").
 */
object SearchTriggers {

    val DEFAULTS: List<SearchTrigger> = listOf(
        SearchTrigger("@gif", SearchTarget.GIF),
        SearchTrigger("@sticker", SearchTarget.STICKER),
        SearchTrigger("@wiki", SearchTarget.WIKIPEDIA),
        SearchTrigger("@define", SearchTarget.DICTIONARY),
        SearchTrigger("@web", SearchTarget.WEB),
        SearchTrigger("@contact", SearchTarget.CONTACTS),
    )

    /** The longest a prefix can be. */
    const val MAX_PREFIX = 16

    /** Words past this many characters are not a search any more, but a message. */
    const val MAX_QUERY = 60

    /** One shortcut a line, the target's id, a tab, then the prefix. */
    fun encode(list: List<SearchTrigger>): String =
        list.joinToString("\n") { "${it.target.id}\t${it.prefix}" }

    fun decode(text: String): List<SearchTrigger> = text.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val target = SearchTarget.of(line.substring(0, tab)) ?: return@mapNotNull null
        val prefix = clean(line.substring(tab + 1)) ?: return@mapNotNull null
        SearchTrigger(prefix, target)
    }.toList()

    /** [raw] as a prefix: trimmed, no spaces inside, not too long; null when nothing is left. */
    fun clean(raw: String): String? =
        raw.trim().filterNot { it.isWhitespace() }.take(MAX_PREFIX).takeIf { it.isNotEmpty() }

    /** What a shortcut found at the end of the text. */
    data class Match(
        val trigger: SearchTrigger,
        /** The words after the prefix. */
        val query: String,
        /** How many characters before the cursor the prefix and the words take. */
        val span: Int,
    )

    /**
     * The shortcut the end of [text] is, or null. When two prefixes both fit
     * ("@w" and "@wiki"), the longer one wins.
     */
    fun match(text: String, triggers: List<SearchTrigger>): Match? {
        if (triggers.isEmpty() || text.isEmpty()) return null
        var best: Match? = null
        for (trigger in triggers) {
            val found = matchOne(text, trigger) ?: continue
            if (best == null || trigger.prefix.length > best.trigger.prefix.length) best = found
        }
        return best
    }

    private fun matchOne(text: String, trigger: SearchTrigger): Match? {
        val prefix = trigger.prefix
        val lower = text.lowercase(Locale.ROOT)
        val p = prefix.lowercase(Locale.ROOT)
        // The words may hold spaces, so look back for the last place the
        // prefix starts a word, within reach of the cursor.
        val earliest = (text.length - MAX_QUERY - prefix.length - 1).coerceAtLeast(0)
        var at = lower.lastIndexOf(p)
        while (at >= earliest) {
            if (at == 0 || text[at - 1].isWhitespace()) {
                val rest = text.substring(at + prefix.length)
                val needsSpace = prefix.last().isLetterOrDigit()
                if (needsSpace && (rest.isEmpty() || !rest.first().isWhitespace())) return null
                if ('\n' in rest) return null
                val query = rest.trim()
                if (query.isEmpty() || query.length > MAX_QUERY) return null
                return Match(trigger, query, text.length - at)
            }
            if (at == 0) break
            at = lower.lastIndexOf(p, at - 1)
        }
        return null
    }
}

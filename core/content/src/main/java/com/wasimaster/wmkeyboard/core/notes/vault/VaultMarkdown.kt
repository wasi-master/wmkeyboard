package com.wasimaster.wmkeyboard.core.notes.vault

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The few parts of an Obsidian or Zettel Notes vault's Markdown the keyboard
 * has to understand to show a note sensibly (#1211): the YAML front matter at
 * the top, kept as it is and never shown as text, and wiki-links (`[[Note]]`,
 * `[[Note|shown words]]`, `[[Note#Heading]]`), shown as their words and
 * followed to the note they name. Everything else is the Markdown the notes
 * read view already draws.
 *
 * Pure, so the rules are tested on the JVM.
 */
object VaultMarkdown {

    /** A note's text in two parts: the front matter block, delimiters and all, and the rest. */
    data class Split(val frontMatter: String, val body: String) {
        /** The whole file again, byte for byte when nothing was changed. */
        fun joined(): String = frontMatter + body
        fun withBody(newBody: String): Split = copy(body = newBody)
    }

    /** One wiki-link: the note it names, the heading after `#` if any, and its own words if it has them. */
    data class WikiLink(val target: String, val heading: String?, val alias: String?) {
        /** What the link shows in the text. */
        val shown: String get() = alias ?: heading?.let { if (target.isEmpty()) it else "$target › $it" } ?: target
    }

    /** The link scheme the read view claims for a wiki-link; see [forDisplay]. */
    const val LINK_SCHEME = "wmvault:"

    private val FENCE = Regex("^---[ \\t]*$")
    private val WIKI = Regex("(!?)\\[\\[([^\\[\\]\\n]+?)]]")

    /**
     * Splits [text] at the end of its front matter: a first line of `---`,
     * then lines up to the next `---` (or `...`). A file without one is all
     * body. An opening fence with no closing one is not front matter either,
     * so a note that starts with a horizontal rule loses nothing.
     */
    fun split(text: String): Split {
        if (!text.startsWith("---")) return Split("", text)
        val firstEnd = text.indexOf('\n')
        if (firstEnd < 0 || !FENCE.matches(text.substring(0, firstEnd).trimEnd('\r'))) return Split("", text)
        var at = firstEnd + 1
        while (at <= text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val line = text.substring(at, end).trimEnd('\r')
            if (line == "---" || line == "...") {
                val cut = if (end < text.length) end + 1 else end
                return Split(text.substring(0, cut), text.substring(cut))
            }
            if (end >= text.length) break
            at = end + 1
        }
        return Split("", text)
    }

    /** A front matter key's value as one line, quotes taken off; null when the key is not there. */
    fun field(frontMatter: String, key: String): String? {
        val prefix = "$key:"
        for (raw in frontMatter.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith(prefix)) continue
            val value = line.substring(prefix.length).trim()
            return value.removeSurrounding("\"").removeSurrounding("'").takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * The note's tags from its front matter: `tags: [a, b]`, `tags: a, b`,
     * `tags: a b`, or a list of `- a` lines under `tags:`. Obsidian's `tag`
     * and `#` prefixes are allowed.
     */
    fun tags(frontMatter: String): List<String> {
        val lines = frontMatter.lines().map { it.trimEnd('\r') }
        val at = lines.indexOfFirst { it.startsWith("tags:") || it.startsWith("tag:") }
        if (at < 0) return emptyList()
        val inline = lines[at].substringAfter(':').trim()
        val raw = if (inline.isNotEmpty()) {
            // A YAML list or commas split on the commas, so "to do" stays one tag; bare words on spaces.
            if (inline.startsWith("[") || ',' in inline) {
                inline.removePrefix("[").removeSuffix("]").split(',')
            } else {
                inline.split(Regex("\\s+"))
            }
        } else {
            lines.drop(at + 1).takeWhile { it.trimStart().startsWith("-") }.map { it.trimStart().removePrefix("-") }
        }
        return raw.map { it.trim().removeSurrounding("\"").removeSurrounding("'").removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** What a note is called: its front matter `title`, else its file name without the extension. */
    fun title(frontMatter: String, fileName: String): String = field(frontMatter, "title") ?: noteName(fileName)

    /** A file name without `.md` or `.txt`. */
    fun noteName(fileName: String): String =
        fileName.removeSuffix(".md").removeSuffix(".MD").removeSuffix(".markdown").removeSuffix(".txt")

    /** Whether a file is a note the vault view lists. */
    fun isNote(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
    }

    /** Every wiki-link in [body], embeds (`![[…]]`) included, in order, each note once. */
    fun wikiLinks(body: String): List<WikiLink> =
        WIKI.findAll(body).map { parse(it.groupValues[2]) }.filter { it.target.isNotEmpty() }
            .distinctBy { it.target.lowercase() }.toList()

    /**
     * [body] for the read view: each wiki-link a Markdown link under
     * [LINK_SCHEME], so the view can claim the tap, and an embed its name in
     * place of the picture or note it would pull in.
     */
    fun forDisplay(body: String): String = WIKI.replace(body) { m ->
        val link = parse(m.groupValues[2])
        val words = link.shown.replace("[", "").replace("]", "")
        if (m.groupValues[1] == "!" || link.target.isEmpty()) {
            "`$words`"
        } else {
            "[$words](" + LINK_SCHEME + URLEncoder.encode(link.target, "UTF-8").replace("+", "%20") + ")"
        }
    }

    /** The note a [forDisplay] link names, or null for any other address. */
    fun targetOf(url: String): String? {
        if (!url.startsWith(LINK_SCHEME)) return null
        return runCatching { URLDecoder.decode(url.removePrefix(LINK_SCHEME), "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * A file name for a new note called [name]: the characters no file system
     * or sync service takes turned into spaces, at most 120 long, with `.md`.
     * Null when nothing is left.
     */
    fun fileNameFor(name: String): String? {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ").trim().trimStart('.').take(MAX_NAME)
        if (clean.isEmpty()) return null
        return if (clean.lowercase().endsWith(".md")) clean else "$clean.md"
    }

    /**
     * A name for a capture sent to a folder: its first line's first words,
     * or [fallback] (a time stamp) when the text has none.
     */
    fun captureName(text: String, fallback: String): String {
        val line = text.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val words = line.split(Regex("\\s+")).take(CAPTURE_WORDS).joinToString(" ").take(CAPTURE_CHARS)
        return words.ifEmpty { fallback }
    }

    /** [text] added to the end of [existing], on a line of its own. */
    fun appended(existing: String, text: String): String {
        val add = text.trimEnd()
        if (add.isEmpty()) return existing
        if (existing.isEmpty()) return add + "\n"
        val gap = if (existing.endsWith("\n")) "" else "\n"
        return existing + gap + add + "\n"
    }

    private fun parse(inner: String): WikiLink {
        val alias = inner.substringAfter('|', "").trim().takeIf { '|' in inner && it.isNotEmpty() }
        val path = inner.substringBefore('|').trim()
        val heading = path.substringAfter('#', "").trim().takeIf { '#' in path && it.isNotEmpty() }
        val target = path.substringBefore('#').trim()
        return WikiLink(target, heading, alias)
    }

    private const val MAX_NAME = 120
    private const val CAPTURE_WORDS = 6
    private const val CAPTURE_CHARS = 60
}

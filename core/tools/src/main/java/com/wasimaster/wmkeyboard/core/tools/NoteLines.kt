package com.wasimaster.wmkeyboard.core.tools

import java.util.Locale

/**
 * The lines of the user's notes, for the chip that types a quote from them
 * line by line (#1099). Type the first words of a line in a note and the chip
 * offers the whole line; take it, or type the line out, and the chip offers
 * the line after it, and so on down the note.
 *
 * Lines are matched by their words: case, runs of spaces and a leading list,
 * quote or heading mark (`- `, `1. `, `> `, `# `, a task box) don't count, and
 * the line typed is the note's own, without that mark. A start needs
 * [MIN_CHARS] characters and two words, and so does a whole line before the
 * chip offers the next one, so "ok" or "Hi" typed in a chat never pulls a line
 * out of a note.
 *
 * Built off the main thread whenever the notes change; [match] is a binary
 * search, cheap enough for every keystroke.
 */
class NoteLines private constructor(
    private val sources: List<Source>,
    /** Every line's key, sorted by key, then by note and line. */
    private val entries: List<Entry>,
) {
    /** One note: what the chip calls it, and its lines with their marks taken off. */
    class Source(val name: String, val lines: List<String>)

    private class Entry(val key: String, val source: Int, val line: Int)

    /**
     * A line to offer. [typed] is the start it finishes, or "" when it is the
     * line after one already in the field; [replaceSpan] characters before
     * the caret give way to [insert].
     */
    data class Hit(
        val source: String,
        val line: String,
        val typed: String,
        val replaceSpan: Int,
        val insert: String,
    )

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The line for the text before the caret, [before], of which only the end
     * was read when [truncated]. The line the caret is on decides: a whole
     * line of a note asks for the next one, on a line of its own; a blank line
     * just after one (Enter pressed) asks for the next one there; the first
     * words of a line ask for the rest of it.
     */
    fun match(before: String, truncated: Boolean): Hit? {
        if (entries.isEmpty() || before.isEmpty()) return null
        val cut = before.lastIndexOf('\n')
        // With no line break in what was read, and not all of it read, where
        // the line starts is unknown.
        if (cut < 0 && truncated) return null
        val current = before.substring(cut + 1)
        if (current.isBlank()) {
            if (cut < 0) return null
            val start = before.lastIndexOf('\n', cut - 1) + 1
            if (start == 0 && truncated) return null
            val next = nextAfter(before.substring(start, cut)) ?: return null
            return Hit(sources[next.first].name, next.second, typed = "", replaceSpan = 0, insert = next.second)
        }
        nextAfter(current)?.let { (source, line) ->
            return Hit(sources[source].name, line, typed = "", replaceSpan = 0, insert = "\n" + line)
        }
        val typed = current.trimStart()
        val key = keyOf(typed)
        if (!longEnough(key)) return null
        var best: Entry? = null
        var index = lowerBound(key)
        var scanned = 0
        while (index < entries.size && scanned < MAX_SCAN) {
            val entry = entries[index]
            if (!entry.key.startsWith(key)) break
            // A line no longer than what was typed has nothing left to offer.
            if (entry.key.length > key.length && (best == null || before(entry, best))) best = entry
            index++
            scanned++
        }
        val found = best ?: return null
        val line = sources[found.source].lines[found.line]
        return Hit(sources[found.source].name, line, typed = typed, replaceSpan = typed.length, insert = line)
    }

    /** The note and the line after the note line that [text] is, the first note that has one. */
    private fun nextAfter(text: String): Pair<Int, String>? {
        val key = keyOf(text)
        if (!longEnough(key)) return null
        var index = lowerBound(key)
        while (index < entries.size && entries[index].key == key) {
            val entry = entries[index]
            val lines = sources[entry.source].lines
            if (entry.line + 1 < lines.size) return entry.source to lines[entry.line + 1]
            index++
        }
        return null
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].key < key) low = mid + 1 else high = mid
        }
        return low
    }

    private fun before(a: Entry, b: Entry): Boolean =
        a.source < b.source || (a.source == b.source && a.line < b.line)

    companion object {
        /** Characters of the field read for this chip: a line longer than this is not matched. */
        const val LOOKBEHIND = 400

        /** The shortest start, or whole line, that raises the chip. */
        const val MIN_CHARS = 10

        /** Lines read across all the notes, the first notes first. */
        const val MAX_LINES = 60_000

        /** Lines starting with the same words looked at before the first note's is taken. */
        private const val MAX_SCAN = 256

        val EMPTY = NoteLines(emptyList(), emptyList())

        /** The lines of [notes], each a name and a body, in the order they should win a tie. */
        fun build(notes: List<Pair<String, String>>): NoteLines {
            val sources = ArrayList<Source>()
            val entries = ArrayList<Entry>()
            for ((name, body) in notes) {
                if (entries.size >= MAX_LINES) break
                val lines = body.split('\n').map { stripMark(it).trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                val source = sources.size
                sources += Source(name, lines)
                lines.forEachIndexed { index, line ->
                    if (entries.size < MAX_LINES) entries += Entry(keyOf(line), source, index)
                }
            }
            entries.sortWith(compareBy<Entry>({ it.key }, { it.source }, { it.line }))
            return NoteLines(sources, entries)
        }

        private fun longEnough(key: String): Boolean = key.length >= MIN_CHARS && ' ' in key

        /** A leading list, task, quote or heading mark. */
        private val MARK by lazy {
            Regex("""^\s*(?:(?:>\s?)+|[-*+]\s+(?:\[[ xX]\]\s+)?|\d{1,3}[.)]\s+|#{1,6}\s+)""")
        }

        private fun stripMark(line: String): String {
            val match = MARK.find(line) ?: return line
            return line.substring(match.range.last + 1)
        }

        /** The line as it is matched: no mark, lowercase, one space between words. */
        internal fun keyOf(text: String): String {
            val bare = stripMark(text).lowercase(Locale.ROOT)
            val out = StringBuilder(bare.length)
            var space = false
            for (ch in bare) {
                if (ch.isWhitespace()) {
                    space = out.isNotEmpty()
                } else {
                    if (space) out.append(' ')
                    space = false
                    out.append(ch)
                }
            }
            return out.toString()
        }
    }
}

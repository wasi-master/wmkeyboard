package com.wasimaster.wmkeyboard.core.selection

/**
 * Line-wise rewrites for a selection that spans several lines: sort, drop
 * repeats, number, bullet, check box; and [moved], the lines around a
 * selection swapped with their neighbour.
 *
 * Every function answers null when it would change nothing, so the caller can
 * leave the chip off rather than draw a no-op. Numbering and bulleting are
 * toggles: a list that already carries the prefix on every line loses it, and
 * either prefix converts into the other, so a bulleted list becomes numbered
 * in one tap and a half-numbered one is renumbered. The original line ending
 * and a trailing newline both survive, and a line's own indentation stays in
 * front of the prefix.
 */
object LineEdits {

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 17 KB across this file's patterns.
    private val NUMBER_PREFIX by lazy { Regex("""^(\s*)\d+[.)]\s+""") }
    private val BULLET_PREFIX by lazy { Regex("""^(\s*)[•\-*–]\s+""") }

    /** `☐ `, `☑ `, `✅ `, or a Markdown task box (`- [ ] `, `[x] `). */
    private val CHECKBOX_PREFIX by lazy { Regex("""^(\s*)(?:[☐☑☒✅✓✔]|(?:[-*]\s+)?\[[ xX]])\s+""") }

    /**
     * Ascending in natural order (digit runs compared as numbers, case
     * ignored); a list that is already ascending turns descending instead, so
     * a second tap is the other direction rather than nothing.
     */
    fun sort(text: String): String? = edit(text) { lines ->
        val ascending = lines.sortedWith(NATURAL)
        if (ascending == lines) lines.sortedWith(NATURAL.reversed()) else ascending
    }

    /** Keeps the first of every repeated line. Blank lines are never repeats. */
    fun dedupe(text: String): String? = edit(text) { lines ->
        val seen = HashSet<String>()
        lines.filter { line -> line.isBlank() || seen.add(line) }
    }

    /** `1. `, `2. `, … on every non-blank line; strips them when every line already has one. */
    fun number(text: String): String? = prefix(text, NUMBER_PREFIX) { index -> "${index + 1}. " }

    /** `• ` on every non-blank line; strips it when every line already has one. */
    fun bullet(text: String): String? = prefix(text, BULLET_PREFIX) { "• " }

    /**
     * `☐ ` on every non-blank line, a list to tick off (#679); strips it when
     * every line already has a box, ticked or not. A plain character rather
     * than Markdown's `- [ ] `, which only a Markdown editor draws as a box.
     */
    fun checkbox(text: String): String? = prefix(text, CHECKBOX_PREFIX) { "☐ " }

    /**
     * [text] with the lines that [start] until [end] covers swapped with the
     * line above them ([up]) or below, and where the selection lands after the
     * move, keeping its place inside the lines it moved with. Null at the top
     * or bottom, where there is no line to swap with.
     */
    fun moved(text: String, start: Int, end: Int, up: Boolean): Moved? {
        if (start < 0 || end > text.length || start > end) return null
        val blockStart = text.lastIndexOf('\n', start - 1) + 1
        // A selection that ends right after a line break covers the line it
        // ends, not the empty start of the next.
        val last = if (end > start && text[end - 1] == '\n') end - 1 else end
        val blockEnd = text.indexOf('\n', last).let { if (it < 0) text.length else it }
        val block = text.substring(blockStart, blockEnd)
        if (up) {
            if (blockStart == 0) return null
            val aboveStart = text.lastIndexOf('\n', blockStart - 2) + 1
            val above = text.substring(aboveStart, blockStart - 1)
            val swapped = block + "\n" + above
            val shift = aboveStart - blockStart
            return Moved(aboveStart, blockEnd, swapped, start + shift, end + shift)
        }
        if (blockEnd == text.length) return null
        val belowEnd = text.indexOf('\n', blockEnd + 1).let { if (it < 0) text.length else it }
        val below = text.substring(blockEnd + 1, belowEnd)
        val swapped = below + "\n" + block
        val shift = below.length + 1
        return Moved(blockStart, belowEnd, swapped, start + shift, end + shift)
    }

    /**
     * What [moved] changes: [from] until [to] of the text becomes [text], and
     * the selection is then [selStart] until [selEnd].
     */
    class Moved(val from: Int, val to: Int, val text: String, val selStart: Int, val selEnd: Int)

    private fun prefix(text: String, own: Regex, label: (Int) -> String): String? = edit(text) { lines ->
        val content = lines.filter { it.isNotBlank() }
        val allPrefixed = content.isNotEmpty() && content.all { own.containsMatchIn(it) }
        var index = 0
        lines.map { line ->
            if (line.isBlank()) return@map line
            val stripped = strip(line)
            if (allPrefixed) stripped.indent + stripped.body else stripped.indent + label(index++) + stripped.body
        }
    }

    private class Stripped(val indent: String, val body: String)

    /** [line] without either kind of prefix, its indentation kept apart. */
    private fun strip(line: String): Stripped {
        for (regex in listOf(NUMBER_PREFIX, CHECKBOX_PREFIX, BULLET_PREFIX)) {
            val match = regex.find(line) ?: continue
            return Stripped(match.groupValues[1], line.substring(match.range.last + 1))
        }
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return Stripped(indent, line.substring(indent.length))
    }

    /**
     * Splits, rewrites, joins. Null when there are fewer than two lines or
     * the rewrite changed nothing.
     */
    private fun edit(text: String, rewrite: (List<String>) -> List<String>): String? {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailing = text.endsWith("\n")
        val body = if (trailing) text.substring(0, text.length - newline.length) else text
        val lines = body.split("\r\n", "\n")
        if (lines.size < 2) return null
        val result = rewrite(lines).joinToString(newline) + if (trailing) newline else ""
        return result.takeIf { it != text }
    }

    /**
     * Natural order: `item2` before `item10`, case ignored, with a
     * case-sensitive tiebreak so the order is total.
     */
    private val NATURAL = Comparator<String> { a, b ->
        val byNature = compareNatural(a, b)
        if (byNature != 0) byNature else a.compareTo(b)
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val byLength = na.length.compareTo(nb.length)
                if (byLength != 0) return byLength
                val byValue = na.compareTo(nb)
                if (byValue != 0) return byValue
                i = ei
                j = ej
            } else {
                val ca = a[i].lowercaseChar()
                val cb = b[j].lowercaseChar()
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

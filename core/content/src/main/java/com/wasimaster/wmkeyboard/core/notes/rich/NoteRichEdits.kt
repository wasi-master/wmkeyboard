package com.wasimaster.wmkeyboard.core.notes.rich

import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.LineKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.Range
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.Role

/** A note's text with its selection, `[start, end)`; [start] == [end] is a caret. */
data class NoteDraft(val text: String, val start: Int, val end: Int = start) {
    val collapsed: Boolean get() = start == end

    /** The same draft with the selection held inside the text and in order. */
    fun clamped(): NoteDraft {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return NoteDraft(text, minOf(a, b), maxOf(a, b))
    }
}

/** What [NoteRichEdits.settle] made of an edit, and whether it spent the formats waiting for the next letters. */
data class Settled(val draft: NoteDraft, val usedPending: Boolean = false)

/** The paragraph styles the format sheet and the slash list offer, by the prefix each writes. */
enum class NoteBlockStyle(val prefix: String) {
    BODY(""),
    TITLE("# "),
    SUBTITLE("## "),
    HEADING("### "),
    BULLET("- "),
    NUMBERED("1. "),
    TODO("- [ ] "),
    QUOTE("> "),
}

/** What the table menu does to the table under the caret. */
enum class NoteTableOp { ADD_ROW, ADD_COLUMN, REMOVE_ROW, REMOVE_COLUMN, DELETE }

/**
 * A pipe table as cells: [rows] holds the header first and every body row
 * after it, all as long as the header; [align] is each column's delimiter
 * cell (`---`, `:---:`, `---:`), kept as it was.
 */
data class NoteTable(val rows: List<List<String>>, val align: List<String>) {
    val columns: Int get() = align.size
}

/**
 * The rich editor's edits (#1586), all on the stored Markdown itself; see
 * [NoteMarkup] for why the editor never works on a converted copy.
 *
 * Two kinds. [settle] tidies an edit the keys made: typing that landed inside
 * a hidden marker is moved out of it, a backspace that would have taken half
 * of `**` takes the letter before it instead, Enter carries a list on, and
 * the formats picked with nothing selected wrap the first letters typed. The
 * rest ([toggleMark], [setBlock], the inserts, the table and the moves) are
 * what the format sheet and the slash list do.
 *
 * Pure functions of text and selection: the keyboard and the app call the
 * same ones, and the tests run them on the JVM.
 */
object NoteRichEdits {

    // ---- settling an edit the keys made ----

    /**
     * [after] as the editor should keep it, given it came from [before] by
     * typing, a backspace, a delete or a paste. [pending] are the marks picked
     * while nothing was selected: they wrap the next text typed.
     */
    fun settle(before: NoteDraft, after: NoteDraft, pending: List<NoteMark> = emptyList()): Settled {
        val a = before.text
        val b = after.text
        if (a == b) return Settled(after)
        val max = minOf(a.length, b.length)
        var p = 0
        while (p < max && a[p] == b[p]) p++
        var s = 0
        while (s < max - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        var rs = p
        var re = a.length - s
        var inserted = b.substring(p, b.length - s)
        // The same text can come from an edit at more than one place ("aa"):
        // the caret says which one the user made.
        if (rs == re && inserted.isNotEmpty() && after.collapsed) {
            val q = after.start - inserted.length
            if (q in 0..a.length && q != rs && a.substring(0, q) + inserted + a.substring(q) == b) {
                rs = q
                re = q
            }
        } else if (inserted.isEmpty() && before.collapsed) {
            val n = re - rs
            val back = before.start - n
            if (back >= 0 && back != rs && a.removeRange(back, before.start) == b) {
                rs = back
                re = before.start
            } else if (before.start + n <= a.length && before.start != rs && a.removeRange(before.start, before.start + n) == b) {
                rs = before.start
                re = before.start + n
            }
        }
        if (rs == re) inserted = b.substring(rs, rs + (b.length - a.length))
        val read = NoteMarkup.read(a)
        if (rs == re) return insertion(read, after, rs, inserted, pending)
        val direction = when {
            !before.collapsed -> 0
            re == before.start -> -1
            rs == before.start -> 1
            else -> 0
        }
        if (inserted.isEmpty()) deletion(read, rs, re, direction)?.let { return Settled(it) }
        if (read.hiddenIn(rs, re).isEmpty()) return Settled(after)
        return Settled(visibleEdit(read, rs, re, inserted, direction))
    }

    private fun insertion(read: NoteMarkup.Read, after: NoteDraft, at: Int, inserted: String, pending: List<NoteMark>): Settled {
        val a = read.source
        val line = read.lines[read.lineAt(at)]
        // A divider or a row of pictures holds no text: what is typed on it
        // goes on a line of its own, before or after.
        if ((line.kind == LineKind.DIVIDER || line.kind == LineKind.IMAGES) && '\n' !in inserted) {
            return if (at <= line.start) {
                Settled(NoteDraft(a.substring(0, line.start) + inserted + "\n" + a.substring(line.start), line.start + inserted.length))
            } else {
                val text = a.substring(0, line.end) + "\n" + inserted + a.substring(line.end)
                Settled(NoteDraft(text, line.end + 1 + inserted.length))
            }
        }
        var pos = at
        val inside = read.hidden.firstOrNull {
            (pos > it.start && pos < it.end) || (it.role == Role.PREFIX && pos == it.start && !inserted.startsWith("\n"))
        }
        if (inside != null) {
            pos = when (inside.role) {
                Role.CLOSE -> inside.start
                else -> inside.end
            }
        }
        if (inserted == "\n") return Settled(enter(NoteDraft(a, pos)))
        if (pending.isNotEmpty() && inserted.isNotBlank() && '\n' !in inserted) {
            return Settled(wrapPending(a, pos, inserted, pending), usedPending = true)
        }
        if (pos == at) return Settled(after)
        return Settled(NoteDraft(a.substring(0, pos) + inserted + a.substring(pos), pos + inserted.length))
    }

    /** [inserted] at [pos], wrapped in [pending]'s markers, the caret before the closing ones so typing goes on inside. */
    private fun wrapPending(a: String, pos: Int, inserted: String, pending: List<NoteMark>): NoteDraft {
        val ordered = pending.distinct().sortedBy { order(it.kind) }
        for (html in listOf(false, true)) {
            val pairs = ordered.map { NoteMarkup.markers(it, html) }
            val open = pairs.joinToString("") { it.first }
            val close = pairs.reversed().joinToString("") { it.second }
            val text = a.substring(0, pos) + open + inserted + close + a.substring(pos)
            val caret = pos + open.length + inserted.length
            val check = NoteMarkup.read(text)
            val from = pos + open.length
            val covered = ordered.all { mark -> check.marks.any { it.mark == mark && it.start <= from && it.end >= caret } }
            if (covered || html) return NoteDraft(text, caret)
        }
        return NoteDraft(a.substring(0, pos) + inserted + a.substring(pos), pos + inserted.length)
    }

    /** Outermost first: tags, then strike, bold, italic, code innermost. */
    private fun order(kind: NoteMarkKind): Int = when (kind) {
        NoteMarkKind.LINK -> 0
        NoteMarkKind.SIZE, NoteMarkKind.FONT, NoteMarkKind.COLOR, NoteMarkKind.HIGHLIGHT, NoteMarkKind.UNDERLINE -> 1
        NoteMarkKind.STRIKE -> 2
        NoteMarkKind.BOLD -> 3
        NoteMarkKind.ITALIC -> 4
        NoteMarkKind.CODE -> 5
    }

    /** The deletions that mean something other than their characters; null for the rest. */
    private fun deletion(read: NoteMarkup.Read, rs: Int, re: Int, direction: Int): NoteDraft? {
        val a = read.source
        // Inside a list's, heading's or quote's prefix: the whole prefix goes,
        // and the line is a plain one.
        read.hidden.firstOrNull { it.role == Role.PREFIX && rs >= it.start && re <= it.end }?.let { h ->
            return NoteDraft(a.removeRange(h.start, h.end), h.start)
        }
        // A divider is one thing: any of it deleted deletes it.
        read.hidden.firstOrNull { it.role == Role.WHOLE && rs >= it.start && re <= it.end }?.let { h ->
            return removeLine(a, h.start, h.end)
        }
        if (re - rs == 1 && a[rs] == '\n' && direction <= 0) {
            // Backspace at the start of a list item takes its bullet first,
            // as every editor does; a second one joins the lines.
            read.hidden.firstOrNull { it.role == Role.PREFIX && it.start == re }?.let { h ->
                return NoteDraft(a.removeRange(h.start, h.end), h.start)
            }
            // Backspace just past a divider or a row of pictures takes it away.
            val above = read.lines[read.lineAt(rs)]
            if (above.end == rs && (above.kind == LineKind.DIVIDER || above.kind == LineKind.IMAGES)) {
                return NoteDraft(a.removeRange(above.start, re), above.start)
            }
        }
        if (re - rs == 1 && a[rs] == '\n' && direction > 0) {
            val below = read.lines[read.lineAt(re)]
            if (below.start == re && (below.kind == LineKind.DIVIDER || below.kind == LineKind.IMAGES)) {
                return NoteDraft(a.removeRange(rs, below.end), rs)
            }
        }
        return null
    }

    /** The line `[start, end)` and one of the line breaks around it taken out, the caret where it was. */
    private fun removeLine(a: String, start: Int, end: Int): NoteDraft = when {
        end < a.length -> NoteDraft(a.removeRange(start, end + 1), start)
        start > 0 -> NoteDraft(a.removeRange(start - 1, end), start - 1)
        else -> NoteDraft(a.removeRange(start, end), start)
    }

    /**
     * An edit that cut through markers, redone on the text as it shows: the
     * touched lines are taken as text and marks, edited there, and written
     * back. [direction] says which way a deletion of markers alone was
     * pressed: the letter on that side goes instead.
     */
    private fun visibleEdit(read: NoteMarkup.Read, rs: Int, re: Int, inserted: String, direction: Int): NoteDraft {
        val a = read.source
        val first = read.lineAt(rs)
        val last = read.lineAt(re)
        val sb = StringBuilder()
        val src = ArrayList<Int>()
        val marks = ArrayList<Range>()
        // Each line break owns the prefix of the line after it, and whether
        // that line is one whose text is never read for marks.
        val owners = ArrayList<Pair<String, Boolean>>()
        val breaks = ArrayList<Int>()
        for (k in first..last) {
            val line = read.lines[k]
            if (k > first) {
                breaks += sb.length
                sb.append('\n')
                src += read.lines[k - 1].end
            }
            val prefix = if (line.kind.inline) a.substring(line.start, line.contentStart) else ""
            owners += prefix to !line.kind.inline
            val v = NoteMarkup.visible(read, line)
            for (r in v.marks) marks += Range(r.start + sb.length, r.end + sb.length, r.mark)
            sb.append(v.text)
            for (x in v.source) src += x
        }
        val text = sb.toString()
        var va = src.count { it < rs }
        var vb = src.count { it < re }
        if (inserted.isEmpty() && va == vb) {
            when {
                direction < 0 && va > 0 -> va = graphemeBefore(text, va)
                direction < 0 && first > 0 -> return visibleEdit(read, read.lines[first].start - 1, read.lines[first].start, "", -1)
                direction > 0 && vb < text.length -> vb = graphemeAfter(text, vb)
                else -> return NoteDraft(a, rs)
            }
        }
        val edited = text.substring(0, va) + inserted + text.substring(vb)
        val delta = inserted.length - (vb - va)
        val newMarks = marks.mapNotNull { r ->
            val s = if (r.start < va) r.start else if (r.start >= vb) r.start + delta else va
            val e = when {
                r.end <= va -> r.end
                r.end >= vb && r.end > va -> r.end + delta
                else -> va + inserted.length
            }
            // A mark that ends where the insertion starts does not grow over it.
            val end = if (r.end == va && va == vb) r.end else e
            if (end > s) Range(s, end, r.mark) else null
        }
        // The line breaks that survive, with the prefixes they carry.
        val lineOwners = ArrayList<Pair<String, Boolean>>()
        lineOwners += owners[0]
        var insertedBreaks = 0
        val keptBefore = breaks.withIndex().filter { it.value < va }.map { owners[it.index + 1] }
        val keptAfter = breaks.withIndex().filter { it.value >= vb }.map { owners[it.index + 1] }
        lineOwners += keptBefore
        for (c in inserted) if (c == '\n') insertedBreaks++
        repeat(insertedBreaks) { lineOwners += "" to false }
        lineOwners += keptAfter
        val parts = edited.split('\n')
        val out = StringBuilder()
        var offset = 0
        val caretVisible = va + inserted.length
        var caret = -1
        for ((i, content) in parts.withIndex()) {
            if (i > 0) out.append('\n')
            val (prefix, raw) = lineOwners.getOrElse(i) { "" to false }
            val lineMarks = newMarks.mapNotNull { r ->
                val s = maxOf(r.start, offset) - offset
                val e = minOf(r.end, offset + content.length) - offset
                if (e > s) Range(s, e, r.mark) else null
            }
            val written = when {
                raw -> content
                prefix.isEmpty() -> NoteMarkup.writeParagraph(content, lineMarks)
                else -> NoteMarkup.writeInline(content, lineMarks)
            }
            val lineStart = out.length
            out.append(prefix).append(written)
            if (caret < 0 && caretVisible <= offset + content.length) {
                val col = caretVisible - offset
                val inLine = if (raw || col == 0) {
                    col.coerceAtMost(written.length)
                } else {
                    val map = NoteMarkup.readInline(written).source
                    if (col - 1 < map.size) map[col - 1] + 1 else written.length
                }
                caret = lineStart + prefix.length + inLine
            }
            offset += content.length + 1
        }
        val regionStart = read.lines[first].start
        val regionEnd = read.lines[last].end
        val result = a.substring(0, regionStart) + out + a.substring(regionEnd)
        return NoteDraft(result, regionStart + caret.coerceAtLeast(0))
    }

    private fun graphemeBefore(text: String, at: Int): Int =
        if (at >= 2 && Character.isLowSurrogate(text[at - 1]) && Character.isHighSurrogate(text[at - 2])) at - 2 else at - 1

    private fun graphemeAfter(text: String, at: Int): Int =
        if (at + 1 < text.length && Character.isHighSurrogate(text[at]) && Character.isLowSurrogate(text[at + 1])) at + 2 else at + 1

    // ---- Enter ----

    private val TASK_MARK by lazy { Regex("""^( *)([-*+]) {1,4}\[[ xX]\]""") }
    private val ORDERED_MARK by lazy { Regex("""^( *)(\d{1,9})([.)])""") }

    /**
     * Enter at the caret: a line break, and inside a list or a quote the next
     * line's marker (a task's box empty, a number counted on). On an item
     * with nothing written after its marker, the marker goes instead and the
     * list ends there.
     */
    fun enter(draft: NoteDraft): NoteDraft {
        val d = draft.clamped()
        val a = if (d.collapsed) d.text else d.text.removeRange(d.start, d.end)
        val at = d.start
        val read = NoteMarkup.read(a)
        val line = read.lines[read.lineAt(at)]
        val listy = line.kind == LineKind.BULLET || line.kind == LineKind.NUMBERED || line.kind == LineKind.TODO || line.kind == LineKind.QUOTE
        if (!listy || at < line.contentStart) return NoteDraft(a.substring(0, at) + "\n" + a.substring(at), at + 1)
        if (a.substring(line.contentStart, line.end).isBlank()) {
            return NoteDraft(a.removeRange(line.start, line.contentStart), line.start)
        }
        val lineText = a.substring(line.start, line.end)
        val next = when (line.kind) {
            LineKind.TODO -> TASK_MARK.find(lineText)?.let { "${it.groupValues[1]}${it.groupValues[2]} [ ] " } ?: "- [ ] "
            LineKind.NUMBERED -> ORDERED_MARK.find(lineText)?.let {
                val n = (it.groupValues[2].toLongOrNull() ?: 0L) + 1
                "${it.groupValues[1]}$n${it.groupValues[3]} "
            } ?: "1. "
            LineKind.QUOTE -> "> "
            else -> a.substring(line.start, line.contentStart)
        }
        val text = a.substring(0, at) + "\n" + next + a.substring(at)
        return NoteDraft(text, at + 1 + next.length)
    }

    // ---- marks ----

    /**
     * [mark] on the selection, or off it when every letter selected already
     * wears it. Putting on a colour, a highlight, a font, a size, an
     * underline style or a link replaces any other of its kind. A caret alone
     * changes nothing: the caller keeps such a pick for the next letters typed.
     */
    fun toggleMark(draft: NoteDraft, mark: NoteMark): NoteDraft {
        val d = draft.clamped()
        if (d.collapsed) return d
        val read = NoteMarkup.read(d.text)
        val lines = touched(read, d)
        val views = lines.map { NoteMarkup.visible(read, it) }
        var all = true
        var any = false
        for ((i, line) in lines.withIndex()) {
            val v = views[i]
            val (va, vb) = span(v, d)
            for (x in va until vb) {
                any = true
                if (v.marks.none { it.mark == mark && it.start <= x && it.end > x }) all = false
            }
        }
        val removing = any && all
        return rewrite(read, d, lines, views) { marks, va, vb ->
            if (removing) {
                cut(marks, va, vb) { it == mark }
            } else {
                val kept = if (mark.kind.exclusive) cut(marks, va, vb) { it.kind == mark.kind } else marks
                kept + Range(va, vb, mark)
            }
        }
    }

    /** What a format picked from a sheet did: the text, when it changed, and the formats waiting for the next letters. */
    data class MarkPick(val draft: NoteDraft?, val pending: List<NoteMark>)

    /**
     * [mark] picked with [d] as the caret or selection. A selection gets it
     * (or loses it); a caret inside a word puts it on the whole word, as word
     * processors do; a caret between words keeps it in [pending] for the next
     * letters typed, and a second pick takes it back.
     */
    fun pickMark(d: NoteDraft, pending: List<NoteMark>, mark: NoteMark): MarkPick {
        val c = d.clamped()
        if (!c.collapsed) return MarkPick(toggleMark(c, mark), pending)
        val word = wordAround(c)
        if (word != null) {
            val toggled = toggleMark(word, mark)
            // The caret keeps its place in the word, counted from the word's end.
            val fromEnd = word.end - c.start
            return MarkPick(NoteDraft(toggled.text, (toggled.end - fromEnd).coerceIn(toggled.start, toggled.end)), pending)
        }
        val next = when {
            mark in pending -> pending - mark
            mark.kind.exclusive -> pending.filterNot { it.kind == mark.kind } + mark
            else -> pending + mark
        }
        return MarkPick(null, next)
    }

    /** The word the caret is inside (letters on both sides of it), as a selection, or null. */
    fun wordAround(d: NoteDraft): NoteDraft? {
        val t = d.text
        val at = d.start
        if (at <= 0 || at >= t.length || !t[at - 1].isLetterOrDigit() || !t[at].isLetterOrDigit()) return null
        var s = at
        while (s > 0 && t[s - 1].isLetterOrDigit()) s--
        var e = at
        while (e < t.length && t[e].isLetterOrDigit()) e++
        return NoteDraft(t, s, e)
    }

    /** Every mark off the selected text. */
    fun clearMarks(draft: NoteDraft): NoteDraft {
        val d = draft.clamped()
        if (d.collapsed) return d
        val read = NoteMarkup.read(d.text)
        val lines = touched(read, d)
        val views = lines.map { NoteMarkup.visible(read, it) }
        return rewrite(read, d, lines, views) { marks, va, vb -> cut(marks, va, vb) { true } }
    }

    /**
     * The marks on the letter before the caret, or on every selected letter:
     * what the format sheet shows as on.
     */
    fun marksAt(draft: NoteDraft): Set<NoteMark> {
        val d = draft.clamped()
        val read = NoteMarkup.read(d.text)
        val line = read.lines[read.lineAt(d.start)]
        if (!line.kind.inline) return emptySet()
        val v = NoteMarkup.visible(read, line)
        val (va, vb) = span(v, d)
        if (d.collapsed || vb <= va) {
            val x = (if (va > 0) va - 1 else va)
            return v.marks.filter { it.start <= x && it.end > x }.map { it.mark }.toSet()
        }
        return v.marks.map { it.mark }.distinct().filter { mark ->
            (va until vb).all { x -> v.marks.any { it.mark == mark && it.start <= x && it.end > x } }
        }.toSet()
    }

    /** The lines a selection touches; a selection ending at a line's start leaves that line out. */
    private fun touched(read: NoteMarkup.Read, d: NoteDraft): List<NoteMarkup.Line> {
        val first = read.lineAt(d.start)
        val last = read.lineAt(if (d.end > d.start) d.end - 1 else d.end)
        return (first..last).map { read.lines[it] }
    }

    /** The selection inside one line's visible text. */
    private fun span(v: NoteMarkup.Visible, d: NoteDraft): Pair<Int, Int> {
        val va = v.source.count { it < d.start }
        val vb = v.source.count { it < d.end }
        return va to vb
    }

    /** [marks] with whatever [which] picks taken off `[va, vb)`. */
    private fun cut(marks: List<Range>, va: Int, vb: Int, which: (NoteMark) -> Boolean): List<Range> {
        val out = ArrayList<Range>()
        for (r in marks) {
            if (!which(r.mark) || r.end <= va || r.start >= vb) {
                out += r
                continue
            }
            if (r.start < va) out += Range(r.start, va, r.mark)
            if (r.end > vb) out += Range(vb, r.end, r.mark)
        }
        return out
    }

    /**
     * Each of [lines] written again from its visible text with [change]'s
     * marks, and the selection put back over the same letters.
     */
    private fun rewrite(
        read: NoteMarkup.Read,
        d: NoteDraft,
        lines: List<NoteMarkup.Line>,
        views: List<NoteMarkup.Visible>,
        change: (List<Range>, Int, Int) -> List<Range>,
    ): NoteDraft {
        val a = read.source
        val out = StringBuilder()
        var copied = 0
        var newStart = -1
        var newEnd = -1
        for ((i, line) in lines.withIndex()) {
            if (!line.kind.inline) continue
            val v = views[i]
            val (va, vb) = span(v, d)
            if (vb <= va) continue
            val written = if (line.kind == LineKind.PARAGRAPH) {
                NoteMarkup.writeParagraph(v.text, change(v.marks, va, vb))
            } else {
                NoteMarkup.writeInline(v.text, change(v.marks, va, vb))
            }
            out.append(a, copied, line.contentStart)
            val base = out.length
            out.append(written)
            copied = line.end
            val map = NoteMarkup.readInline(written).source
            if (newStart < 0) newStart = base + if (va < map.size) map[va] else written.length
            newEnd = base + if (vb > 0 && vb - 1 < map.size) map[vb - 1] + 1 else written.length
        }
        if (newStart < 0) return d
        out.append(a, copied, a.length)
        return NoteDraft(out.toString(), newStart, newEnd)
    }

    // ---- paragraph styles ----

    /** The style of the line the caret is on, or null for a line that is not text (a table, a picture). */
    fun blockAt(draft: NoteDraft): NoteBlockStyle? {
        val d = draft.clamped()
        val read = NoteMarkup.read(d.text)
        val line = read.lines[read.lineAt(d.start)]
        return styleOf(line)
    }

    private fun styleOf(line: NoteMarkup.Line): NoteBlockStyle? = when (line.kind) {
        LineKind.PARAGRAPH -> NoteBlockStyle.BODY
        LineKind.HEADING -> when (line.level) {
            1 -> NoteBlockStyle.TITLE
            2 -> NoteBlockStyle.SUBTITLE
            else -> NoteBlockStyle.HEADING
        }
        LineKind.BULLET -> NoteBlockStyle.BULLET
        LineKind.NUMBERED -> NoteBlockStyle.NUMBERED
        LineKind.TODO -> NoteBlockStyle.TODO
        LineKind.QUOTE -> NoteBlockStyle.QUOTE
        else -> null
    }

    /**
     * [style] on every text line the selection touches; picking the style
     * those lines already have makes them body text again. A list keeps its
     * indent, and numbered lines are counted from 1.
     */
    fun setBlock(draft: NoteDraft, style: NoteBlockStyle): NoteDraft {
        val d = draft.clamped()
        val read = NoteMarkup.read(d.text)
        val lines = touched(read, d).filter { it.kind.inline }
        if (lines.isEmpty()) return d
        val target = if (lines.all { styleOf(it) == style }) NoteBlockStyle.BODY else style
        val edits = ArrayList<Triple<Int, Int, String>>()
        var number = 1
        for (line in lines) {
            val old = d.text.substring(line.start, line.contentStart)
            val indent = if (line.kind == LineKind.BULLET || line.kind == LineKind.TODO || line.kind == LineKind.NUMBERED) {
                old.takeWhile { it == ' ' }
            } else {
                ""
            }
            val prefix = when (target) {
                NoteBlockStyle.BULLET, NoteBlockStyle.TODO -> indent + target.prefix
                NoteBlockStyle.NUMBERED -> indent + "${number++}. "
                else -> target.prefix
            }
            edits += Triple(line.start, line.contentStart, prefix)
        }
        return applyEdits(d, edits)
    }

    /** [edits] (start, end, replacement; in order, apart) applied, the selection moved with the text around it. */
    private fun applyEdits(d: NoteDraft, edits: List<Triple<Int, Int, String>>): NoteDraft {
        val out = StringBuilder()
        var copied = 0
        for ((s, e, r) in edits) {
            out.append(d.text, copied, s).append(r)
            copied = e
        }
        out.append(d.text, copied, d.text.length)
        fun map(x: Int): Int {
            var shift = 0
            for ((s, e, r) in edits) {
                if (x < s) break
                if (x < e) return s + shift + r.length
                shift += r.length - (e - s)
            }
            return x + shift
        }
        return NoteDraft(out.toString(), map(d.start), map(d.end))
    }

    // ---- inserts ----

    /** A divider. `***` rather than `---`: under a line of text, `---` would make that line a heading. */
    const val DIVIDER = "***"

    /** A two-by-two table, the shape [NoteMarkup] reads as one. */
    const val TABLE_TEMPLATE = "|  |  |\n| --- | --- |\n|  |  |"

    /**
     * [lines] on lines of their own at the caret: a line break before them
     * unless the caret starts a line, and after them unless it ends one. The
     * caret lands in a table's first cell, else on the line after the block.
     */
    fun insertBlock(draft: NoteDraft, lines: String): NoteDraft {
        val d = draft.clamped()
        val base = if (d.collapsed) d.text else d.text.removeRange(d.start, d.end)
        val at = d.start
        val before = if (at > 0 && base[at - 1] != '\n') "\n" else ""
        // The block always ends its own line; a break already there is the one it uses.
        val tail = base.substring(at).let { if (it.startsWith("\n")) it.substring(1) else it }
        val text = base.substring(0, at) + before + lines + "\n" + tail
        val caret = if (lines.startsWith("| ") || lines.startsWith("|  ")) {
            at + before.length + 2
        } else {
            at + before.length + lines.length + 1
        }
        return NoteDraft(text, caret.coerceAtMost(text.length))
    }

    // ---- tables ----

    /** A row split on its unescaped pipes, the outer pipes dropped and each cell trimmed. */
    fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') {
                cell.append("\\|")
                k += 2
                continue
            }
            if (c == '|') {
                out += cell.toString().trim()
                cell.clear()
            } else {
                cell.append(c)
            }
            k++
        }
        out += cell.toString().trim()
        return out
    }

    /** The table in [text], a run of table lines; null when it is not one. */
    fun table(text: String): NoteTable? {
        val lines = text.split('\n')
        if (lines.size < 2) return null
        val align = cells(lines[1]).map { it.ifBlank { "---" } }
        val columns = align.size
        if (columns == 0) return null
        val rows = (listOf(lines[0]) + lines.drop(2)).map { line ->
            val c = cells(line)
            List(columns) { c.getOrElse(it) { "" } }
        }
        return NoteTable(rows, align)
    }

    /** [table] as pipe-table lines. A pipe typed into a cell is escaped so it stays in its cell. */
    fun tableText(table: NoteTable): String {
        fun row(cells: List<String>) = "| " + cells.joinToString(" | ") { safeCell(it) } + " |"
        val lines = ArrayList<String>()
        lines += row(table.rows.firstOrNull() ?: List(table.columns) { "" })
        lines += "| " + table.align.joinToString(" | ") + " |"
        for (r in table.rows.drop(1)) lines += row(r)
        return lines.joinToString("\n")
    }

    private fun safeCell(cell: String): String {
        val one = cell.replace('\n', ' ')
        val sb = StringBuilder()
        for ((i, c) in one.withIndex()) {
            if (c == '|' && (i == 0 || one[i - 1] != '\\')) sb.append("\\|") else sb.append(c)
        }
        return sb.toString()
    }

    /** The table under the caret, changed by [op]; the caret goes to the cell the change is about. */
    fun tableEdit(draft: NoteDraft, op: NoteTableOp): NoteDraft {
        val d = draft.clamped()
        val read = NoteMarkup.read(d.text)
        val li = read.lineAt(d.start)
        if (read.lines[li].kind != LineKind.TABLE) return d
        var ts = li
        while (ts > 0 && read.lines[ts - 1].kind == LineKind.TABLE) ts--
        var te = li
        while (te < read.lines.lastIndex && read.lines[te + 1].kind == LineKind.TABLE) te++
        val start = read.lines[ts].start
        val end = read.lines[te].end
        if (op == NoteTableOp.DELETE) return removeLine(d.text, start, end)
        val table = table(d.text.substring(start, end)) ?: return d
        val lineIndex = li - ts
        // The caret's row among the header and body rows (the delimiter counts as the header).
        var row = if (lineIndex <= 1) 0 else lineIndex - 1
        val line = read.lines[li]
        val beforeCaret = d.text.substring(line.start, d.start)
        var column = beforeCaret.trimStart().let { t ->
            var pipes = 0
            for ((i, c) in t.withIndex()) if (c == '|' && (i == 0 || t[i - 1] != '\\')) pipes++
            if (t.startsWith("|")) pipes - 1 else pipes
        }.coerceIn(0, table.columns - 1)
        val rows = table.rows.map { it.toMutableList() }.toMutableList()
        val align = table.align.toMutableList()
        when (op) {
            NoteTableOp.ADD_ROW -> {
                rows.add(row + 1, MutableList(table.columns) { "" })
                row += 1
            }
            NoteTableOp.ADD_COLUMN -> {
                for (r in rows) r.add(column + 1, "")
                align.add(column + 1, "---")
                column += 1
            }
            NoteTableOp.REMOVE_ROW -> {
                if (row == 0 || rows.size <= 2) return d
                rows.removeAt(row)
                row = (row - 1).coerceAtLeast(1)
            }
            NoteTableOp.REMOVE_COLUMN -> {
                if (table.columns <= 1) return d
                for (r in rows) r.removeAt(column)
                align.removeAt(column)
                column = column.coerceAtMost(align.size - 1)
            }
            NoteTableOp.DELETE -> Unit
        }
        val written = tableText(NoteTable(rows, align))
        val text = d.text.substring(0, start) + written + d.text.substring(end)
        val writtenLines = written.split('\n')
        val targetLine = if (row == 0) 0 else row + 1
        var offset = start
        for (k in 0 until targetLine) offset += writtenLines[k].length + 1
        val cellsOfRow = rows[row]
        var inLine = 2
        for (k in 0 until column) inLine += safeCell(cellsOfRow[k]).length + 3
        return NoteDraft(text, (offset + inLine).coerceAtMost(text.length))
    }

    // ---- pictures ----

    /** The address a note's own picture is written with: the attachments folder beside `notes.json`. */
    fun imageUrl(name: String): String = "attachments/$name"

    /** [names] (attachment file names) at the caret, side by side in pairs. */
    fun insertImages(draft: NoteDraft, names: List<String>): NoteDraft {
        if (names.isEmpty()) return draft
        val lines = names.chunked(2).joinToString("\n") { pair -> NoteMarkup.imageLine(pair.map(::imageUrl)) }
        return insertBlock(draft, lines)
    }

    /**
     * Line [lineIndex]'s pictures one to a line ([sideBySide] false), or
     * joined with the pictures on the next line, else the line before, into
     * one row ([sideBySide] true).
     */
    fun imagesLayout(text: String, lineIndex: Int, sideBySide: Boolean): String {
        val read = NoteMarkup.read(text)
        val line = read.lines.getOrNull(lineIndex)?.takeIf { it.kind == LineKind.IMAGES } ?: return text
        val urls = NoteMarkup.images(text.substring(line.start, line.end)).map { it.second }
        if (!sideBySide) {
            if (urls.size < 2) return text
            return text.substring(0, line.start) + urls.joinToString("\n") { NoteMarkup.imageLine(listOf(it)) } + text.substring(line.end)
        }
        val next = read.lines.getOrNull(lineIndex + 1)?.takeIf { it.kind == LineKind.IMAGES }
        val prev = read.lines.getOrNull(lineIndex - 1)?.takeIf { it.kind == LineKind.IMAGES }
        return when {
            next != null -> {
                val more = NoteMarkup.images(text.substring(next.start, next.end)).map { it.second }
                text.substring(0, line.start) + NoteMarkup.imageLine(urls + more) + text.substring(next.end)
            }
            prev != null -> {
                val more = NoteMarkup.images(text.substring(prev.start, prev.end)).map { it.second }
                text.substring(0, prev.start) + NoteMarkup.imageLine(more + urls) + text.substring(line.end)
            }
            else -> text
        }
    }

    /** Picture [index] off line [lineIndex]; the line goes with its last picture. */
    fun removeImage(text: String, lineIndex: Int, index: Int): String {
        val read = NoteMarkup.read(text)
        val line = read.lines.getOrNull(lineIndex)?.takeIf { it.kind == LineKind.IMAGES } ?: return text
        val urls = NoteMarkup.images(text.substring(line.start, line.end)).map { it.second }.toMutableList()
        if (index !in urls.indices) return text
        urls.removeAt(index)
        if (urls.isEmpty()) return removeLine(text, line.start, line.end).text
        return text.substring(0, line.start) + NoteMarkup.imageLine(urls) + text.substring(line.end)
    }

    // ---- moving ----

    /**
     * The block under the caret (a line, or a whole table or code block)
     * swapped with the one above or below it; the caret moves with it.
     */
    fun moveBlock(draft: NoteDraft, up: Boolean): NoteDraft {
        val d = draft.clamped()
        val read = NoteMarkup.read(d.text)
        val units = units(read)
        val li = read.lineAt(d.start)
        val u = units.indexOfFirst { li in it }
        val other = if (up) u - 1 else u + 1
        if (u < 0 || other !in units.indices) return d
        val first = if (up) units[other] else units[u]
        val second = if (up) units[u] else units[other]
        val a = d.text
        val s1 = read.lines[first.first].start
        val e1 = read.lines[first.last].end
        val s2 = read.lines[second.first].start
        val e2 = read.lines[second.last].end
        val t1 = a.substring(s1, e1)
        val t2 = a.substring(s2, e2)
        val text = a.substring(0, s1) + t2 + "\n" + t1 + a.substring(e2)
        val shift = if (up) {
            s1 - s2
        } else {
            t2.length + 1
        }
        return NoteDraft(text, d.start + shift, d.end + shift).clamped()
    }

    /** The lines in moving units: a table or a code block moves whole. */
    fun units(read: NoteMarkup.Read): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < read.lines.size) {
            val kind = read.lines[i].kind
            var j = i
            if (kind == LineKind.TABLE || kind == LineKind.CODE) {
                while (j + 1 < read.lines.size && read.lines[j + 1].kind == kind) j++
            }
            out += i..j
            i = j + 1
        }
        return out
    }

    /**
     * The blocks of [text] reordered as [order] lists them (indexes into
     * [units]): what the Rearrange view's drag writes back.
     */
    fun reorder(text: String, order: List<Int>): String {
        val read = NoteMarkup.read(text)
        val units = units(read)
        if (order.sorted() != units.indices.toList()) return text
        return order.joinToString("\n") { k ->
            val unit = units[k]
            text.substring(read.lines[unit.first].start, read.lines[unit.last].end)
        }
    }
}

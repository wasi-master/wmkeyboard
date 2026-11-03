package com.wasimaster.wmkeyboard.core.notes

/**
 * A stretch of a note's body that holds a search's match (#1099): the
 * paragraph around it, or less of it when the paragraph is long. Offsets are
 * UTF-16 indices into the body, ends exclusive.
 */
data class NotePassage(val start: Int, val end: Int, val matchStart: Int, val matchEnd: Int)

/**
 * The passages a search finds inside a note, so a quote can be typed out of
 * a text the user keeps as a note without opening it and selecting by hand.
 *
 * A passage is the paragraph the match sits in, blank lines apart. A
 * paragraph longer than [MAX_CHARS] narrows to the lines the match covers,
 * and a line still longer than that to the sentences around the match. Two
 * matches in one passage give it once.
 */
object NotePassages {

    /** Shortest query that looks inside notes: one letter would match almost every paragraph. */
    const val MIN_QUERY_LENGTH = 2

    /** Longest passage before it narrows to lines, then sentences. */
    const val MAX_CHARS = 600

    /** Most passages one note gives. */
    const val PER_NOTE_MAX = 3

    /** [body]'s passages holding [query], ignoring case, in text order; at most [limit]. */
    fun find(body: String, query: String, limit: Int = PER_NOTE_MAX): List<NotePassage> {
        val needle = query.trim()
        if (needle.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        val out = ArrayList<NotePassage>()
        var from = 0
        while (out.size < limit) {
            val at = body.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val end = at + needle.length
            val passage = around(body, at, end)
            out.add(passage)
            from = maxOf(passage.end, end)
        }
        return out
    }

    private fun around(body: String, matchStart: Int, matchEnd: Int): NotePassage {
        var (start, end) = paragraph(body, matchStart, matchEnd)
        if (end - start > MAX_CHARS) {
            start = lineStart(body, matchStart)
            end = lineEnd(body, matchEnd)
        }
        if (end - start > MAX_CHARS) {
            // What the cap leaves after the match itself, half on either side.
            val side = (MAX_CHARS - (matchEnd - matchStart)).coerceAtLeast(0) / 2
            start = sentenceStart(body, matchStart, floor = maxOf(start, matchStart - side))
            end = sentenceEnd(body, matchEnd, ceiling = minOf(end, matchEnd + side))
        }
        while (start < matchStart && body[start].isWhitespace()) start++
        while (end > matchEnd && body[end - 1].isWhitespace()) end--
        return NotePassage(start, end, matchStart, matchEnd)
    }

    /** The run of non-blank lines around the match. */
    private fun paragraph(body: String, matchStart: Int, matchEnd: Int): Pair<Int, Int> {
        var start = lineStart(body, matchStart)
        while (start > 0) {
            val previous = lineStart(body, start - 1)
            if (body.substring(previous, start - 1).isBlank()) break
            start = previous
        }
        var end = lineEnd(body, matchEnd)
        while (end < body.length) {
            val next = lineEnd(body, end + 1)
            if (body.substring(end + 1, next).isBlank()) break
            end = next
        }
        return start to end
    }

    private fun lineStart(body: String, at: Int): Int = body.lastIndexOf('\n', at - 1) + 1

    private fun lineEnd(body: String, at: Int): Int = body.indexOf('\n', at).let { if (it < 0) body.length else it }

    /** Just after the last sentence end before [at], no earlier than [floor] (moved on to a word's start). */
    private fun sentenceStart(body: String, at: Int, floor: Int): Int {
        var i = at - 1
        while (i >= floor) {
            if (endsSentence(body, i)) return i + 1
            i--
        }
        if (floor <= 0 || body[floor - 1] == '\n') return floor
        val space = body.indexOf(' ', floor)
        val start = if (space in floor until at) space + 1 else floor
        return if (start in 1 until body.length && body[start].isLowSurrogate()) start + 1 else start
    }

    /** Just after the first sentence end from [at], no later than [ceiling] (moved back to a word's end). */
    private fun sentenceEnd(body: String, at: Int, ceiling: Int): Int {
        var i = at
        while (i < ceiling) {
            if (endsSentence(body, i)) return i + 1
            i++
        }
        if (ceiling >= body.length || body[ceiling] == '\n') return ceiling
        val space = body.lastIndexOf(' ', ceiling)
        val end = if (space >= at) space else ceiling
        return if (end in 1 until body.length && body[end].isLowSurrogate()) end - 1 else end
    }

    /** A terminator at [i] that ends a sentence: followed by a space or the end, or one of the CJK stops, which need neither. */
    private fun endsSentence(body: String, i: Int): Boolean {
        val c = body[i]
        if (c !in TERMINATORS) return false
        return c in SPACELESS_TERMINATORS || i + 1 >= body.length || body[i + 1].isWhitespace()
    }

    /** Latin, Devanagari and Bengali danda, CJK and Arabic full stops, and the question and exclamation marks. */
    private val TERMINATORS = setOf('.', '!', '?', '।', '॥', '。', '！', '？', '؟', '۔')
    private val SPACELESS_TERMINATORS = setOf('。', '！', '？')
}

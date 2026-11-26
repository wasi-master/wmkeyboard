package com.wasimaster.wmkeyboard.core.selection

import java.text.BreakIterator
import java.util.Locale

/**
 * What [SelectionMacro.STATS] shows about a selection (#1569), the counts a
 * word processor gives: words, characters with and without spaces, spaces,
 * lines, paragraphs and sentences, and the longest and shortest word and line.
 *
 * A character is what the eye counts as one, a grapheme: an emoji with its
 * skin tone, or a letter with its accent, is one. Words and sentences are
 * Android's own breaks for [locale], so Thai or Japanese, written without
 * spaces, still count words; a word is a run with a letter or a digit in it.
 * A paragraph is a run of lines with no blank line in it, and the line
 * figures leave blank lines out.
 */
data class SelectionStats(
    val words: Int,
    val characters: Int,
    val charactersNoSpaces: Int,
    val spaces: Int,
    val lines: Int,
    val paragraphs: Int,
    val sentences: Int,
    val longestWord: String?,
    val shortestWord: String?,
    /** The longest and shortest non-blank line's length, in characters. */
    val longestLine: Int,
    val shortestLine: Int,
) {
    companion object {
        /** Longest text read; past it the figures would cost a noticeable pause on the bar. */
        const val MAX_CHARS = 200_000

        fun of(text: String, locale: Locale = Locale.getDefault()): SelectionStats {
            val body = text.take(MAX_CHARS)
            var characters = 0
            var noSpaces = 0
            var spaces = 0
            graphemes(body) { g ->
                characters++
                val c = g[0]
                when {
                    c == '\n' || c == '\r' -> {}
                    g.isBlank() -> spaces++
                    else -> noSpaces++
                }
            }
            val words = ArrayList<String>()
            segments(body, BreakIterator.getWordInstance(locale)) { s ->
                if (s.any { it.isLetterOrDigit() }) words += s
            }
            var sentences = 0
            segments(body, BreakIterator.getSentenceInstance(locale)) { s ->
                if (s.any { it.isLetterOrDigit() }) sentences++
            }
            val allLines = body.split("\r\n", "\n", "\r")
            val filled = allLines.filter { it.isNotBlank() }
            var paragraphs = 0
            var inParagraph = false
            for (line in allLines) {
                if (line.isBlank()) {
                    inParagraph = false
                } else if (!inParagraph) {
                    paragraphs++
                    inParagraph = true
                }
            }
            val lineLengths = filled.map { graphemeCount(it.trim()) }
            return SelectionStats(
                words = words.size,
                characters = characters,
                charactersNoSpaces = noSpaces,
                spaces = spaces,
                lines = if (body.isEmpty()) 0 else allLines.size,
                paragraphs = paragraphs,
                sentences = sentences,
                longestWord = words.maxByOrNull { graphemeCount(it) },
                shortestWord = words.minByOrNull { graphemeCount(it) },
                longestLine = lineLengths.maxOrNull() ?: 0,
                shortestLine = lineLengths.minOrNull() ?: 0,
            )
        }

        private fun graphemeCount(text: String): Int {
            var n = 0
            graphemes(text) { n++ }
            return n
        }

        private inline fun graphemes(text: String, each: (String) -> Unit) =
            segments(text, BreakIterator.getCharacterInstance(), each)

        private inline fun segments(text: String, iterator: BreakIterator, each: (String) -> Unit) {
            if (text.isEmpty()) return
            iterator.setText(text)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                each(text.substring(start, end))
                start = end
                end = iterator.next()
            }
        }
    }
}

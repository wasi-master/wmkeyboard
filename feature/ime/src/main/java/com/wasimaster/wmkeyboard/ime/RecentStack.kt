package com.wasimaster.wmkeyboard.ime

/**
 * The sums behind the recent words stacked in the strip, Nintype's word
 * history: each word written in the field is a column, its other readings
 * above and below it. Pure, so the service's half of it can be tested without
 * a field.
 *
 * A column has a place in the field as well as a spelling, and the stacked
 * strip shows the words after the caret too, not only the ones before it: tap
 * an earlier word, change it, and the words after it stay on the strip. Text
 * typed or deleted in front of those words moves them, and nothing tells the
 * keyboard by how much, so [relocate] finds them again by reading the text
 * after the caret.
 */
internal object RecentStack {

    /**
     * The words in [text], read from the field at [base], as (start, word)
     * pairs in field order. A word is a run of [isComposingWordChar] with a
     * letter in it, as the recent words count them. When [cut] the read
     * stopped short of the text's end, so a word running into the end of
     * [text] may be longer and is left out.
     */
    fun wordsIn(text: CharSequence, base: Int, cut: Boolean): List<Pair<Int, String>> {
        val found = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < text.length) {
            if (!isComposingWordChar(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < text.length && isComposingWordChar(text[i])) i++
            if (cut && i == text.length) break
            val word = text.substring(start, i)
            if (word.any { it.isLetter() }) found += (base + start) to word
        }
        return found
    }

    /**
     * Where each of [entries] (spellings, in field order) stands among
     * [found] (what the field reads, in field order), or null for one that
     * is not there any more. Matched as the longest run of the same words in
     * the same order, so a deleted word drops out without taking a later word
     * of the same spelling for itself, and a word the user typed among them
     * is skipped.
     */
    fun relocate(entries: List<String>, found: List<Pair<Int, String>>): List<Int?> {
        val n = entries.size
        val m = found.size
        if (n == 0) return emptyList()
        if (m == 0) return List(n) { null }
        // lcs[i][j]: the longest common run of entries[i..] and found[j..].
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (entries[i] == found[j].second) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }
        val starts = arrayOfNulls<Int>(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                entries[i] == found[j].second && lcs[i][j] == lcs[i + 1][j + 1] + 1 -> {
                    starts[i] = found[j].first
                    i++
                    j++
                }
                // Keep the entry and pass the field's word when that loses
                // nothing: the entry may be the next word along.
                lcs[i][j + 1] >= lcs[i + 1][j] -> j++
                else -> i++
            }
        }
        return starts.toList()
    }

    /**
     * Where the strip's Back arrow puts the caret: [wordEnd] is where the
     * last word written ends, as an index into [after], the field's text
     * read from some point before it. The caret goes past that word, the
     * marks that close up onto it (`great?`, `here.`) and one space after
     * them, which is where the next word would have gone.
     */
    fun returnPoint(after: CharSequence, wordEnd: Int): Int {
        var i = wordEnd.coerceIn(0, after.length)
        while (i < after.length && !after[i].isWhitespace() && !isComposingWordChar(after[i])) i++
        if (i < after.length && after[i] == ' ') i++
        return i
    }
}

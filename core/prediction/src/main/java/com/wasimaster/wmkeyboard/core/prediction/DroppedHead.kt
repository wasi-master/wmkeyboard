package com.wasimaster.wmkeyboard.core.prediction

/**
 * Whether a fix reads a typed word with its first letters thrown away:
 * `unintereste` as *interested*, `неинтересно` as *интересно*.
 *
 * The walk may spend its two edits on the first two letters, and a word that
 * starts with a prefix the lists did not count (`uninterested` is not in the
 * bundled English list, and Russian has a не- form of nearly every adjective)
 * then has the bare stem as its only near match. A thumb does not tap two
 * stray keys before a word; what stands there is a prefix, usually one that
 * negates (un-, in-, dis-, non-, не-, ни-, без-) or otherwise changes what the
 * word says (re-, pre-), so the fix turns the word into its opposite. Such a
 * fix is neither applied nor offered; the stem still shows on the strip.
 *
 * One letter is left to the walk: a stray tap before a word is a real slip.
 */
internal object DroppedHead {

    /** Letters a dropped head must have, at least and at most. */
    private const val MIN_HEAD = 2
    private const val MAX_HEAD = 4

    /** Letters after the head that must open the fix as typed. */
    private const val MIN_REST = 3

    fun drops(typed: String, fixed: String): Boolean {
        val t = typed.lowercase()
        val f = fixed.lowercase()
        if (' ' in f || f == t) return false
        for (k in MIN_HEAD..MAX_HEAD) {
            val rest = t.drop(k)
            if (rest.length < MIN_REST) break
            // The fix is the rest of the word, maybe completed by a letter,
            // not the whole of it with a letter changed (`babana`, `banana`).
            if (f.length > rest.length + 1) continue
            if (!f.startsWith(rest.take(MIN_REST))) continue
            val near = (rest.length - 1..rest.length + 1).any { m -> m >= 0 && osa(rest, f.take(m)) <= 1 }
            if (near) return true
        }
        return false
    }

    /** Edits (insert, delete, change, swap of neighbours) from [a] to [b]. */
    private fun osa(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
            }
        }
        return d[a.length][b.length]
    }
}

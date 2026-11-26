package com.wasimaster.wmkeyboard.core.prediction

import java.text.Normalizer

/**
 * Which of a strip's words an ambiguous board (T9, Compact QWERTY, One-row)
 * shows in the field and commits for the keys pressed (#1572).
 *
 * The strip's first word used to be the reading, whatever it was: a longer
 * word the keys only begin ("this" for three keys), or a word one edit away
 * that drops a key or changes a letter the user picked out of a key's
 * long-press popup. So a space completed words with autocorrect switched
 * off, and a letter spelled out by hand was read back as another one.
 *
 * Now a word qualifies only if it keeps every key: each keystroke's letter is
 * one its key carries, and a letter picked by hand (or typed on a key with one
 * letter) is that letter. With [corrects] on, the engine's order stands among
 * such words, a longer word the keys begin included, and so does a word with
 * one letter from a neighbouring key's place on an ambiguous key. With it off,
 * only what the keys spell: a word of exactly that length, or else the
 * beginning of the likeliest word they start.
 */
object AmbiguousReading {

    /**
     * The reading for [typed], the buffer of anchor letters, among
     * [candidates] (best first, as the strip ranked them), or null when there
     * are none. When no candidate keeps the keys at all, the first one, as
     * before: a script this reads wrongly loses nothing.
     */
    fun pick(candidates: List<String>, typed: String, keys: KeySets, corrects: Boolean): String? {
        if (typed.isEmpty() || candidates.isEmpty()) return null
        if (corrects) {
            for (c in candidates) {
                val spelled = consumed(c, typed, keys, 0, 0, 0)
                if (spelled != null) return c
                if (consumed(c, typed, keys, 0, 0, 1) == c.length) return c
            }
        } else {
            for (c in candidates) if (consumed(c, typed, keys, 0, 0, 0) == c.length) return c
            for (c in candidates) consumed(c, typed, keys, 0, 0, 0)?.let { return c.substring(0, it) }
        }
        return candidates.first()
    }

    /**
     * How many characters of [word] spell keystrokes [pos] onward from
     * [at], or null when they cannot: every keystroke spends one letter of
     * its set (or a longer spelling of one, [KeySets.spellingsAt]), a
     * keystroke with no set spends the letter typed, and up to [slips]
     * keystrokes on a key with a set may spend any one letter instead.
     */
    private fun consumed(word: String, typed: String, keys: KeySets, pos: Int, at: Int, slips: Int): Int? {
        if (pos == typed.length) return at
        if (at >= word.length) return null
        val set = keys.at(pos)
        val c = word[at]
        if (set == null) {
            return if (same(c, typed[pos])) consumed(word, typed, keys, pos + 1, at + 1, slips) else null
        }
        if (set.indexOf(c.lowercaseChar()) >= 0 || set.indexOf(base(c)) >= 0) {
            consumed(word, typed, keys, pos + 1, at + 1, slips)?.let { return it }
        }
        keys.spellingsAt(pos)?.forEach { spelling ->
            if (word.startsWith(spelling, at, ignoreCase = true)) {
                consumed(word, typed, keys, pos + 1, at + spelling.length, slips)?.let { return it }
            }
        }
        return if (slips > 0 && c.isLetter()) consumed(word, typed, keys, pos + 1, at + 1, slips - 1) else null
    }

    /** A letter picked by hand is that letter, its accent included: `é` is not `e`. */
    private fun same(a: Char, b: Char): Boolean = a.lowercaseChar() == b.lowercaseChar()

    /** [c] in lower case without its accents: the letter a key carries for é. */
    private fun base(c: Char): Char =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: c
}

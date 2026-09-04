package com.wasimaster.wmkeyboard.core.prediction

import java.text.Normalizer

/**
 * The one spelling a word is stored and looked up under.
 *
 * Every store here matches words by their characters — a hash key in
 * [UserLexicon], a binary search over a follower run in [MappedNgramPack], a
 * walk down a [Trie] — so two spellings of the same word are two different
 * words to all of them, and the lookup does not fail, it simply misses.
 *
 * Case was always folded for that reason. Composition is the other half of the
 * same problem, and Bengali is where it stops being theoretical: য় has both a
 * precomposed spelling (U+09DF) and a decomposed one (য + U+09BC NUKTA). NFC
 * keeps the decomposed one, because U+09DF and its siblings U+09DC and U+09DD
 * are on Unicode's composition-exclusion list, so NFC takes them apart and
 * never puts them back. The word lists write that form; every layout and
 * `AvroPhonetic` commit the precomposed one. They render identically, so every
 * disagreement between them looks, on screen, exactly like a word nothing had
 * ever seen.
 *
 * Folding at the store boundary rather than at the producer is deliberate.
 * Words do not only arrive from the layout the user is typing on: the context
 * for the next suggestion is re-read from the text field itself, which may hold
 * text pasted from elsewhere, typed on another keyboard, or left by the app. No
 * fix applied to a composer could reach those; this reaches all of them.
 *
 * Applying it on both sides of every store is what makes it safe — a store
 * written through here and read through here cannot disagree with itself.
 *
 * Cost is a quick-check per word: a table lookup per character that exits on
 * the first ASCII one, and no allocation at all unless a word really is
 * mis-composed.
 */
object WordKey {

    fun of(word: String): String = normalize(word.lowercase())

    /**
     * The same normalisation without the case fold: the spelling a word is
     * *shown* in, next to the key it is stored under.
     *
     * Case memory (#44) keeps both — "Boston" beside "boston" — and the two
     * are only comparable if they went through the same composition pass, so
     * a surface form recorded here always satisfies `of(surface(w)) == of(w)`.
     */
    fun surface(word: String): String = normalize(word)

    private fun normalize(word: String): String =
        if (Normalizer.isNormalized(word, Normalizer.Form.NFC)) {
            word
        } else {
            Normalizer.normalize(word, Normalizer.Form.NFC)
        }
}

package com.wasimaster.wmkeyboard.core.prediction

/**
 * Derives bigram context from the text before the caret — extracted from the
 * IME so it is pure and JVM-testable, and so sentence boundaries become part
 * of the contract instead of an accident: "Hello. " used to hand "hello" to
 * the bigram store, polluting it with pairs that cross a full stop.
 */
object WordContext {

    /** U+0001, built rather than written literally so no editor or tool can
     * corrupt an invisible control character in this file. */
    private val SENTINEL_CHAR: Char = 1.toChar()

    /**
     * Pseudo-word marking "the caret is at a sentence start". Learned as a
     * bigram *previous* (so sentence openers become predictable) but never as
     * a word or a follower; U+0001 can never be typed, so it collides with
     * nothing and any store it leaks into simply never matches.
     */
    val SENTENCE_START: String = SENTINEL_CHAR + "s"

    /**
     * True for a character that belongs to the word around it.
     *
     * `Char.isLetter()` on its own answers this only for the scripts that
     * spell a word out of letters alone. Bengali হয়েছে ends in U+09C7 VOWEL
     * SIGN E, a combining mark, so a letters-only boundary reads that word as
     * having ended three characters earlier: `trim { !it.isLetter() }` hands
     * back হয়েছ and `takeLastWhile { it.isLetter() }` hands back a bare ছ. The
     * same holds for Devanagari matras, Tamil and Thai vowel signs, Arabic
     * harakat and Hebrew niqqud. A combining mark never separates two words —
     * it is part of what the letter before it spells.
     */
    fun isWordChar(c: Char): Boolean = c.isLetter() || when (c.category) {
        CharCategory.NON_SPACING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        -> true
        else -> false
    }

    /**
     * Whether [c] separates two words the way a space does.
     *
     * `Char.isWhitespace()` is not that test, and the gap is not academic:
     * Java excludes every no-break space from it by design, so U+00A0 — the
     * space French keeps in front of `!`, `?`, `;` and `:`
     * (`SpacedPunctuation`) — reads as an ordinary character and glues
     * "Bonjour" and "!" into one nine-character "word". Adding
     * [Char.isSpaceChar] puts the no-break and typographic spaces back;
     * keeping [Char.isWhitespace] keeps tab, newline and carriage return,
     * which `isSpaceChar` leaves out. Both are needed.
     */
    fun isSpaceLike(c: Char): Boolean = c.isWhitespace() || Character.isSpaceChar(c)

    /**
     * Whether [word] is a word the keyboard may learn *on its own* (#185).
     *
     * Letters and digits, with at least one letter ("mp3" and "b2b" are words;
     * "2024" is a number), starting and ending on one of those; inside, single
     * joiners are allowed too — an apostrophe ("don't"), a hyphen
     * ("well-known"), or the zero-width joiners Indic scripts spell with —
     * never two in a row and never anything else. What it refuses is the
     * class of thing that used to reach the personal dictionary through a
     * side door: `manager"`, `man"ager`, a token with a symbol glued on.
     *
     * Only the automatic paths ask: a word the user adds by hand is theirs to
     * spell however they like. Length is the caller's business — every store
     * has its own floor and ceiling already.
     */
    fun isLearnableWord(word: String): Boolean {
        if (word.isEmpty()) return false
        fun letterOrDigit(c: Char) = isWordChar(c) || c.isDigit()
        if (!letterOrDigit(word[0]) || !letterOrDigit(word[word.length - 1])) return false
        var afterJoiner = false
        var letters = 0
        for ((i, c) in word.withIndex()) {
            // Han then Latin is two words nobody spaced, not one (#1050). `IP地址`
            // is a real word, which is why only learning on its own refuses it.
            if (i > 0 && scriptChangesAt(word, i)) return false
            if (isWordChar(c)) letters++
            afterJoiner = when {
                letterOrDigit(c) -> false
                c in WORD_JOINERS && !afterJoiner -> true
                else -> return false
            }
        }
        return letters > 0
    }

    /**
     * Every character that spells an apostrophe *inside* a word, across the
     * scripts that write one.
     *
     * More than the two an English keyboard types, because the field is read
     * back from whatever put text in it. Breton spells `c'hoar` with a plain
     * U+0027 and the digraph is a letter of the alphabet; Ukrainian writes
     * `об'єкт`; Dutch pluralises `auto's`; Hebrew makes `ג׳ינס` with a GERESH
     * and Western Armenian uses its own APOSTROPHE, and neither of those is
     * the ASCII one. A keyboard with smart quotes turns the lot into U+2019,
     * and a careless one into U+2018.
     *
     * Deliberately not here: U+02BB ʻOKINA, U+02BC MODIFIER LETTER APOSTROPHE
     * and U+02BD. Unicode calls those modifier *letters*, so Hawaiian,
     * Kazakh and Uzbek already pass [isWordChar] without asking, and adding
     * them would say they need the letter on each side that a real letter
     * never needs.
     */
    fun isApostrophe(c: Char): Boolean = when (c) {
        '\'', '\u2019', '\u2018', '\u05F3', '\u055A' -> true
        else -> false
    }

    /** What may sit between two letters of one word: the apostrophes, the hyphen, ZWNJ and ZWJ. */
    const val WORD_JOINERS = "'\u2019\u2018\u05F3\u055A-\u200C\u200D"

    /**
     * Whether the character at [i] of [text] belongs to the word around it —
     * [isWordChar], plus an apostrophe with a letter on each side.
     *
     * A contraction is one word, and every store here keys on it as one:
     * [isLearnableWord] admits the apostrophe as a joiner, the lexicon holds
     * `don't` under that spelling, and the English list carries the
     * contractions themselves (#128). The scans below read words back *out*
     * of the text field, and they used to stop at the apostrophe — so
     * "that's " handed the next suggestion the context word "s", and the
     * pair the keyboard learned was `s` followed by whatever came next
     * (#240). Not an English problem: Breton `c'hoar`, Ukrainian `об'єкт`,
     * Catalan `l'home` and Dutch `auto's` were each read as their last
     * fragment. See [isApostrophe] for which characters count.
     *
     * Only medial: a quote around a word, or the one a possessive ends on,
     * is punctuation and still ends it.
     */
    /**
     * Whether a word ends between [i] - 1 and [i] because the script changes
     * into or out of one written with no spaces and no segmenter behind it
     * (#1050). Thai and its neighbours are [WordSegments]' to split. Common and
     * inherited characters, the kana length mark and combining marks among
     * them, belong to whatever they sit beside.
     */
    private fun scriptChangesAt(text: CharSequence, i: Int): Boolean {
        val a = text[i - 1]
        val b = text[i]
        if (a.code < 0x80 && b.code < 0x80) return false
        val left = unsplitGroup(a)
        val right = unsplitGroup(b)
        return left != GROUP_NEUTRAL && right != GROUP_NEUTRAL && left != right && (left > 0 || right > 0)
    }

    private fun unsplitGroup(c: Char): Int = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> GROUP_NEUTRAL
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 1
        Character.UnicodeScript.TIBETAN -> 2
        Character.UnicodeScript.JAVANESE -> 3
        else -> GROUP_OTHER
    }

    private const val GROUP_NEUTRAL = 0
    private const val GROUP_OTHER = -1

    /** The start of the word ending at [end]: back through its letters, stopping where the script changes. */
    private fun wordStart(text: CharSequence, end: Int): Int {
        var start = end
        while (start > 0 && isWordCharAt(text, start - 1) && (start == end || !scriptChangesAt(text, start))) start--
        return start
    }

    /** [text] up to [end], read as ending on a whole word when a script change is what stopped the scan there. */
    private fun upTo(text: String, end: Int): String =
        if (end in 1 until text.length && scriptChangesAt(text, end)) text.substring(0, end) + " " else text.substring(0, end)

    private fun isWordCharAt(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (isWordChar(c)) return true
        if (!isApostrophe(c)) return false
        return i > 0 && i + 1 < text.length && isWordChar(text[i - 1]) && isWordChar(text[i + 1])
    }

    /**
     * The completed word ending [text], for next-word context:
     *  - null while still inside a word, and null for null text, which is an
     *    editor saying it cannot answer rather than saying there is nothing;
     *  - [SENTENCE_START] for **empty** text — the caret sits at the very top
     *    of the field, which is the most sentence-start there is. It used to
     *    answer null here, and null means "no context at all", so the strip on
     *    an empty field could not offer a first word however many sentence
     *    openers had been learned (#119);
     *  - [SENTENCE_START] when a sentence ender lies between the last word
     *    and the caret ("Hello. " — the next word starts a sentence and
     *    "hello" is not its context). Known limitation, accepted: "Dr. "
     *    reads as a sentence start too. A line break counts the same way: a
     *    new line is a new item, line or paragraph, not the rest of the one
     *    above, so "Hearty\nBall\n" offers openers rather than words that
     *    follow "ball" (#889);
         *  - otherwise the last word, in the one spelling every store keys on
     *    ("Hello, " -> "hello"). See [WordKey]: the text here is read back out
     *    of the field, so it can hold anything any keyboard or paste put there.
     *
     * Thai, Lao, Khmer and Burmese are the exception to "null while inside a
     * word": they put no space after a word, so text ending in their letters
     * is how the field looks *between* words, and the last word is the last
     * one [WordSegments] finds in the run ("ไปกินข้าว" -> "ข้าว").
     */
    fun completedWordBefore(text: CharSequence?, enders: CharArray): String? {
        if (text == null) return null
        if (text.isEmpty()) return SENTENCE_START
        segmentedContext(text, enders)?.let { return it.first }
        if (isWordChar(text.last()) || text.last().isDigit()) return null
        // The run of separators between the last word and the caret.
        var i = text.length - 1
        while (i >= 0 && !isWordCharAt(text, i)) {
            if (text[i] in enders || text[i] == '\n') return SENTENCE_START
            i--
        }
        if (i < 0) return null
        val start = wordStart(text, i + 1)
        return WordKey.of(text.subSequence(start, i + 1).toString()).ifEmpty { null }
    }

    /**
     * The context words when the run of letters nearest the caret is Thai,
     * Lao, Khmer or Burmese: its last three words by [WordSegments], most
     * recent first, the words before the run filling in when it holds fewer.
     * Null for any other text, which the scans above read as before.
     *
     * The run is the one [completedWordBefore] would take as the last word:
     * the letters at the very end of [text], or the last ones before the
     * separators there. A sentence ender among those separators is a
     * sentence start, which the plain path already answers.
     */
    private fun segmentedContext(text: CharSequence?, enders: CharArray): Triple<String?, String?, String?>? {
        if (text.isNullOrEmpty() || text.last().isDigit()) return null
        var end = text.length
        if (!isWordChar(text.last())) {
            var i = end - 1
            while (i >= 0 && !isWordCharAt(text, i)) {
                if (text[i] in enders) return null
                i--
            }
            if (i < 0) return null
            end = i + 1
        }
        var start = end
        while (start > 0 && isWordCharAt(text, start - 1)) start--
        // The segmenter's LSTM costs about 15 µs a character on a laptop, and
        // backspace re-reads the context, so a long run is read only to its
        // last [SEGMENTED_WINDOW] characters; the first piece of a cut window
        // may be half a word, and is dropped.
        val cut = end - start > SEGMENTED_WINDOW
        val run = text.subSequence(if (cut) end - SEGMENTED_WINDOW else start, end).toString()
        if (!WordSegments.hasSegmentedScript(run)) return null
        val at = WordSegments.ranges(run)
        val words = (at.indices step 2)
            .drop(if (cut) 1 else 0)
            .map { WordKey.of(run.substring(at[it], at[it + 1])) }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val n = words.size
        if (n >= 3) return Triple(words[n - 1], words[n - 2], words[n - 3])
        // A cut window that held fewer than three whole words: the rest of
        // the run is unread, so there is nothing reliable before them.
        if (cut) return Triple(words[n - 1], words.getOrNull(n - 2), null)
        // Fewer than three: the words before the run fill in, with any
        // boundary there (a sentence start) ending the context as usual.
        val (outer1, outer2) = lastThreeWords(text.subSequence(0, start), enders)
        val first = outer1?.takeUnless { isSentinel(it) }
        val second = outer2?.takeIf { first != null }
        return if (n == 2) {
            Triple(words[1], words[0], first)
        } else {
            Triple(words[0], first, second)
        }
    }

    /** How much of a Thai, Lao, Khmer or Burmese run [segmentedContext] reads: three long words and more. */
    private const val SEGMENTED_WINDOW = 32

    /** True for the sentinel (or anything in its reserved control plane). */
    fun isSentinel(word: String?): Boolean = word != null && word.startsWith(SENTINEL_CHAR)

    /**
     * The last two completed words before the caret, most recent first:
     * `(prev1, prev2)`. prev2 is null when unknown or when any boundary
     * (sentence ender, or prev1 itself being the sentinel) intervenes —
     * trigram context silently degrades to bigram rather than guessing.
     */
    fun lastTwoWords(text: CharSequence?, enders: CharArray): Pair<String?, String?> {
        segmentedContext(text, enders)?.let { return it.first to it.second }
        val prev1 = completedWordBefore(text, enders)
        if (prev1 == null || isSentinel(prev1)) return prev1 to null
        // Strip the trailing separators and prev1's own letters, then ask the
        // same question of what remains.
        val s = text.toString()
        var end = s.length
        while (end > 0 && !isWordCharAt(s, end - 1)) end--
        end = wordStart(s, end)
        val prev2 = completedWordBefore(upTo(s, end), enders)
        return prev1 to prev2?.takeUnless { isSentinel(it) }
    }

    /**
     * The last three completed words before the caret, most recent first:
     * `(prev1, prev2, prev3)`. Same contract as [lastTwoWords] one word
     * further back: prev3 is null when unknown or when any boundary lies
     * between it and the caret, so the 2-skip bigrams (#195) reads
     * nothing across a sentence end.
     */
    fun lastThreeWords(text: CharSequence?, enders: CharArray): Triple<String?, String?, String?> {
        segmentedContext(text, enders)?.let { return it }
        val (prev1, prev2) = lastTwoWords(text, enders)
        if (prev2 == null) return Triple(prev1, null, null)
        val s = text.toString()
        var end = s.length
        repeat(2) {
            while (end > 0 && !isWordCharAt(s, end - 1)) end--
            end = wordStart(s, end)
        }
        val prev3 = completedWordBefore(upTo(s, end), enders)
        return Triple(prev1, prev2, prev3?.takeUnless { isSentinel(it) })
    }
}

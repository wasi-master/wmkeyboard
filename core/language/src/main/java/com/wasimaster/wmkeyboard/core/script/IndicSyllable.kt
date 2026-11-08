package com.wasimaster.wmkeyboard.core.script

import java.text.Normalizer

/**
 * The written syllable at the end of some Indic text, and where a vowel sign,
 * a virama, a nukta or a bindu goes when it is typed onto it.
 *
 * Samsung's Indic keyboards turn their vowel keys into the consonant just typed
 * wearing each vowel sign: after क the keys read का, कि, की, and a tap on one of
 * them *replaces* the vowel sign the syllable already has, so का then की gives
 * की rather than the broken काी. This is that rule, for every script whose
 * layouts can ask for it (`LayerSpec.afterConsonant`): Devanagari, Bengali,
 * Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam and Sinhala.
 *
 * A syllable is read as three parts, in the order Unicode stores them:
 *
 *  - the consonant cluster, consonants joined by viramas (a joiner may sit
 *    after a virama) with any nukta each one carries;
 *  - the vowel slot: a dependent vowel sign, or a virama on the last consonant;
 *  - the bindu slot: candrabindu, anusvara, visarga, and the rarer marks that
 *    sit on top of a finished syllable (Gurmukhi tippi and addak, Vedic accents).
 *
 * A mark typed onto the syllable takes over its own slot and leaves the others
 * alone, so a vowel sign keeps an anusvara already there (कां then ि gives किं).
 * Where the two signs in the vowel slot write one sign together, the result is
 * that sign: Tamil கெ then ா is கொ, which is how those vowels have always been
 * typed in two strokes, and Devanagari का then ॅ is कॉ, the candra O that
 * Marathi writes English loanwords with.
 *
 * Pure text in, text out, so the keyboard's key labels and its typing path
 * read the same answer.
 */
object IndicSyllable {

    /** Which part of a syllable a mark belongs to. See the class comment. */
    enum class Slot { NUKTA, VOWEL, BINDU }

    /**
     * The syllable at the end of a text. [cluster] always ends in a consonant
     * (with its nukta, if any); [vowel] and [bindu] can be empty.
     */
    data class Syllable(val cluster: String, val vowel: String, val bindu: String) {
        /** The syllable as it is written. */
        val text: String get() = cluster + vowel + bindu
    }

    /**
     * One change to the text before the caret: delete [delete] characters,
     * then type [text]. Only the tail that differs, so placing a bindu on
     * का deletes nothing and types the bindu.
     */
    data class Edit(val delete: Int, val text: String)

    /**
     * How much text before the caret is enough to find the syllable: four
     * consonants, each with a nukta, a virama and a joiner, a vowel sign that
     * may be two code points and two bindus.
     */
    const val CONTEXT: Int = 24

    /** Consonants per cluster; a longer run is read from its last four. */
    private const val MAX_CONSONANTS = 4

    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'

    /**
     * The syllable [before] ends with, or null when it does not end in a
     * consonant that a vowel sign could go on: a word start, a space, an
     * independent vowel, a joiner left after a virama for a half form.
     */
    fun trailing(before: CharSequence): Syllable? {
        val end = before.length
        var i = end
        while (i > 0 && slotOf(before[i - 1]) == Slot.BINDU) i--
        val binduStart = i
        while (i > 0 && slotOf(before[i - 1]) == Slot.VOWEL) i--
        val vowelStart = i
        var clusterStart = -1
        var count = 0
        var scanEnd = vowelStart
        while (count < MAX_CONSONANTS) {
            var k = scanEnd
            while (k > 0 && slotOf(before[k - 1]) == Slot.NUKTA) k--
            if (k == 0 || !isConsonant(before[k - 1])) break
            clusterStart = k - 1
            count++
            // Joined to a consonant before it: a virama, maybe with a joiner
            // on either side of it.
            var m = clusterStart
            if (m > 0 && isJoiner(before[m - 1])) m--
            if (m == 0 || !isVirama(before[m - 1])) break
            m--
            if (m > 0 && isJoiner(before[m - 1])) m--
            scanEnd = m
        }
        if (clusterStart < 0) return null
        return Syllable(
            cluster = before.substring(clusterStart, vowelStart),
            vowel = before.substring(vowelStart, binduStart),
            bindu = before.substring(binduStart, end),
        )
    }

    /**
     * [syllable] with [mark] in its slot, or null when [mark] is not a mark of
     * any slot. Whatever was in that slot goes; the other slots stay, except
     * that a virama ends the syllable, so a bindu after it goes too, and a bindu
     * takes the place of a virama rather than sitting on top of one.
     *
     * Also what a key typing [mark] shows while the text ends in [syllable]:
     * the syllable a tap would leave.
     */
    fun place(syllable: Syllable, mark: Char): String? = when (slotOf(mark)) {
        null -> null
        Slot.NUKTA -> {
            val carries = syllable.cluster.lastOrNull()?.let { slotOf(it) == Slot.NUKTA } == true
            if (carries) syllable.text else syllable.cluster + mark + syllable.vowel + syllable.bindu
        }
        Slot.VOWEL -> {
            val merged = merge(syllable.vowel, mark)
            when {
                merged != null -> syllable.cluster + merged + syllable.bindu
                isVirama(mark) -> syllable.cluster + mark
                else -> syllable.cluster + mark + syllable.bindu
            }
        }
        Slot.BINDU -> {
            val vowel = syllable.vowel.takeUnless { it.length == 1 && isVirama(it[0]) }.orEmpty()
            syllable.cluster + vowel + mark
        }
    }

    /**
     * What typing [mark] after [before] changes, or null when [before] does not
     * end in a syllable or [mark] is not a syllable mark, so the mark is typed
     * the ordinary way.
     */
    fun edit(before: CharSequence, mark: Char): Edit? {
        val syllable = trailing(before) ?: return null
        val placed = place(syllable, mark) ?: return null
        val old = syllable.text
        var same = 0
        while (same < old.length && same < placed.length && old[same] == placed[same]) same++
        return Edit(delete = old.length - same, text = placed.substring(same))
    }

    /**
     * The slot [c] goes in, or null for anything that is not a combining mark
     * of one of these scripts.
     *
     * The nine scripts from Devanagari to Malayalam share one layout of their
     * Unicode blocks, inherited from ISCII, so one table of offsets serves all
     * of them: 0x3C is the nukta, 0x3E to 0x4C the vowel signs, 0x4D the
     * virama, 0x00 to 0x03 the bindus. Malayalam's two extra viramas and
     * Sinhala, which follows a layout of its own, are listed by hand.
     */
    fun slotOf(c: Char): Slot? {
        val code = c.code
        if (!isMark(c)) return null
        when (code) {
            in 0x0D81..0x0D83 -> return Slot.BINDU
            0x0DCA, in 0x0DCF..0x0DDF, 0x0DF2, 0x0DF3 -> return Slot.VOWEL
            0x0D3B, 0x0D3C -> return Slot.VOWEL
            // Gurmukhi yakash, a ya written under the consonant.
            0x0A75 -> return Slot.NUKTA
        }
        if (code !in 0x0900..0x0D7F) return null
        val offset = code and 0x7F
        return when (offset) {
            0x3C -> Slot.NUKTA
            in 0x3A..0x4F, in 0x55..0x57, in 0x62..0x63 -> Slot.VOWEL
            else -> Slot.BINDU
        }
    }

    /** Whether [c] is a virama, the mark that kills a consonant's vowel. */
    fun isVirama(c: Char): Boolean = c.code in VIRAMAS

    /**
     * Whether [c] is a consonant a vowel sign can go on. Independent vowels,
     * Malayalam's chillus and Bengali's khanda ta are letters but take no
     * vowel sign, so they are not.
     */
    fun isConsonant(c: Char): Boolean = c.isLetter() && CONSONANTS.any { c.code in it }

    private fun isJoiner(c: Char): Boolean = c == ZWJ || c == ZWNJ

    private fun isMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    /**
     * The one vowel sign [vowel] and [mark] write together, or null when they
     * do not make one. The canonical pairs come from Unicode composition (Tamil
     * ெ + ா, Bengali ে + া, Sinhala ෙ + ්); the candra O is not canonical, so it
     * is listed.
     */
    private fun merge(vowel: String, mark: Char): String? {
        if (vowel.isEmpty()) return null
        val composed = Normalizer.normalize(vowel + mark, Normalizer.Form.NFC)
        if (composed.length == 1 && slotOf(composed[0]) == Slot.VOWEL) return composed
        return CANDRA_O[vowel + mark]
    }

    private val VIRAMAS: Set<Int> = setOf(
        0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D,
        0x0D3B, 0x0D3C, 0x0DCA,
    )

    private val CONSONANTS: List<IntRange> = listOf(
        // Devanagari, with the nukta letters and the later additions.
        0x0915..0x0939, 0x0958..0x095F, 0x0978..0x097F,
        // Bengali, with Assamese ra and wa.
        0x0995..0x09B9, 0x09DC..0x09DF, 0x09F0..0x09F1,
        // Gurmukhi.
        0x0A15..0x0A39, 0x0A59..0x0A5E,
        // Gujarati.
        0x0A95..0x0AB9, 0x0AF9..0x0AF9,
        // Odia.
        0x0B15..0x0B39, 0x0B5C..0x0B5F, 0x0B71..0x0B71,
        // Tamil.
        0x0B95..0x0BB9,
        // Telugu.
        0x0C15..0x0C39, 0x0C58..0x0C5A,
        // Kannada.
        0x0C95..0x0CB9, 0x0CDE..0x0CDE,
        // Malayalam.
        0x0D15..0x0D3A,
        // Sinhala.
        0x0D9A..0x0DC6,
    )

    /** A then candra E, written as candra O: Devanagari ा + ॅ, Gujarati ા + ૅ. */
    private val CANDRA_O: Map<String, String> = mapOf(
        "ाॅ" to "ॉ",
        "ાૅ" to "ૉ",
    )
}

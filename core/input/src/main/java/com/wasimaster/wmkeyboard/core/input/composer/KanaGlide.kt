package com.wasimaster.wmkeyboard.core.input.composer

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * What a swipe over a kana board can spell (#958): the readings in the
 * Japanese table, written the way a finger can draw them on the JIS kana
 * layout and its relatives.
 *
 * As with [PinyinGlide], a swipe decodes into a *reading*, not a word. The
 * reading joins the composing buffer exactly as if its kana had been tapped,
 * and the candidate strip converts it, so the decoder needs no idea of kanji.
 *
 * The catch is the marks. が, ぱ and っ have no key of their own on these
 * boards: they are か, は and つ plus the ゛ or ゜ key, or a shift. So every
 * reading is offered twice:
 *
 *  - **Without its marks** (がっこう as かつこう), which is what a stroke that
 *    never visits ゛ draws. The buffer then holds かつこう, and the loose-mark
 *    lattice (#293, on by default) reads 学校 back out of it, the way it does
 *    for the same reading tapped out without marks.
 *  - **With its marks drawn through the mark keys** (がっこう as か゛つこう),
 *    for the stroke that does pass over ゛ after か. Only readings common
 *    enough to be worth the memory get this form ([DRAWN_FLOOR]): it is the
 *    fallback for when the unmarked spelling loses, not the main road.
 *
 * Small kana are always written as their full-size key. JIS puts them on
 * shift, other layouts on a long press or nowhere, and no stroke says which;
 * the lattice restores っ and ょ the same way it restores ゛.
 */
object KanaGlide {

    /** The spacing dakuten, as the JIS layout's ゛ key is labelled. */
    const val DAKUTEN = '゛'

    /** The spacing handakuten, the ゜ key's label. */
    const val HANDAKUTEN = '゜'

    /** Every character a spelling may hold: the keys a stroke can pass through. */
    val KEYS: String = buildString {
        for (c in 'ぁ'..'ゖ') append(c)
        append('ー')
        append(DAKUTEN)
        append(HANDAKUTEN)
    }

    /**
     * The spellings and their weights. Each reading keeps the weight of the
     * commonest word behind it, and so does each spelling two readings fold
     * into (かき for both 書き and 鍵's かぎ).
     */
    fun spellings(dict: ConversionDictionary): List<Pair<String, Int>> {
        if (dict.isEmpty) return emptyList()
        val best = HashMap<String, Int>()
        fun keep(spelling: String, weight: Int) {
            if ((best[spelling] ?: -1) < weight) best[spelling] = weight
        }
        dict.forEachRow(FLOOR) { reading, _, score ->
            if (reading.length < MIN_LENGTH || !reading.all(::isReadingChar)) return@forEachRow
            val weight = weight(score)
            keep(unmarked(reading), weight)
            if (score >= DRAWN_FLOOR) drawn(reading)?.let { keep(it, weight) }
        }
        return best.entries.map { it.key to it.value }
    }

    /**
     * [reading] with every mark taken off and every small kana written full
     * size: the keys a stroke that never leaves the kana visits.
     */
    fun unmarked(reading: String): String = buildString(reading.length) {
        for (c in reading) append(BASE[c] ?: c)
    }

    /**
     * [reading] with its dakuten and handakuten drawn through the mark keys
     * (が as か゛), small kana full size, or null when it has neither mark: its
     * [unmarked] spelling is already the only one.
     */
    fun drawn(reading: String): String? {
        if (reading.none { it in MARK }) return null
        return buildString(reading.length + 2) {
            for (c in reading) {
                append(BASE[c] ?: c)
                MARK[c]?.let(::append)
            }
        }
    }

    /**
     * The reading a decoded [spelling] goes into the buffer as: a kana
     * followed by ゛ or ゜ becomes the marked kana. A mark the kana before it
     * cannot take is dropped, as the 小゛゜ key leaves such a kana alone.
     */
    fun reading(spelling: String): String = buildString(spelling.length) {
        for (c in spelling) {
            if (c != DAKUTEN && c != HANDAKUTEN) {
                append(c)
                continue
            }
            val last = lastOrNull() ?: continue
            COMPOSE[last to c]?.let { setCharAt(length - 1, it) }
        }
    }

    private fun isReadingChar(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ー'

    /**
     * A table score as a word count. `ja_kana` carries `30000 - cost` from
     * Mozc, and a Mozc cost is about 500 × −ln p, so the score is already a
     * logarithm: taken as it stands, 学校 (26742) and a rare given name
     * (23287) would sit 0.14 apart once the decoder takes its own log, and a
     * swipe would be decided by shape alone. Undone here, the gap is the one
     * the two words really have, which is the kind of number the decoder's
     * weights were set against. Re-check this if the pack is ever rebuilt on
     * real counts (see `build_mozc_ja_kana.py` in the data repo).
     *
     * Scores above [CEILING] all weigh the same. The top of the table is where
     * the pack is least honest: its 1,280 rows at a full 30000 are mostly
     * inflection pieces (たらしけれ, じまわ, ほかれ) whose real cost lives in
     * Mozc's connection matrix, which the pack drops. Left alone they would
     * outweigh 学校 by e^6.5 and win any short stroke near them.
     */
    private fun weight(score: Int): Int =
        exp((minOf(score, CEILING) - FLOOR) / COST_SCALE).roundToInt().coerceAtLeast(1)

    /**
     * Readings scored under this are left out. The table holds 744,000
     * readings, most of them names and rare compounds, and a trie of all of
     * them is 35 MB of heap; from here up it is about 200,000 nodes' worth
     * (6 MB with the drawn forms), holding 学校, 勉強, ありがとう and the
     * like with room to spare. A reading below it still converts when tapped.
     */
    private const val FLOOR = 24_000

    /** Readings this common also get their marks-drawn spelling ([drawn]). */
    private const val DRAWN_FLOOR = 25_000

    /** Where [weight] stops telling scores apart. */
    private const val CEILING = 28_000

    private const val COST_SCALE = 500.0

    /** A one-kana reading is tapped, not swiped. */
    private const val MIN_LENGTH = 2

    /** Marked or small kana → the key that types it (が → か, っ → つ). */
    private val BASE: Map<Char, Char>

    /** Marked kana → the mark key a stroke passes after its base (が → ゛). */
    private val MARK: Map<Char, Char>

    /** (base, mark key) → marked kana, the way back from [MARK]. */
    private val COMPOSE: Map<Pair<Char, Char>, Char>

    init {
        val base = HashMap<Char, Char>()
        val mark = HashMap<Char, Char>()
        val compose = HashMap<Pair<Char, Char>, Char>()
        // The 小゛゜ rings are the one list of which kana take which form;
        // NFD says whether a form is a mark (か + U+3099) or a small kana.
        for (plain in 'ぁ'..'ゖ') {
            for (form in Kana.markedForms(plain)) {
                base[form] = plain
                val nfd = Normalizer.normalize(form.toString(), Normalizer.Form.NFD)
                val key = when (nfd.getOrNull(1)) {
                    '゙' -> DAKUTEN
                    '゚' -> HANDAKUTEN
                    else -> null
                } ?: continue
                mark[form] = key
                compose[plain to key] = form
            }
        }
        BASE = base
        MARK = mark
        COMPOSE = compose
    }
}

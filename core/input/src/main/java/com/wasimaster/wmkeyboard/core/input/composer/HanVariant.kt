package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Simplified → Traditional Han conversion for candidate output.
 *
 * The conversion tables are all keyed to Simplified characters, but three of the
 * input methods here are used mostly by Traditional writers — Zhuyin in Taiwan,
 * Cangjie and Jyutping in Hong Kong. Rather than ship a second copy of every
 * dictionary, candidates pass through this conversion on the way out when
 * [CjkConfig.traditionalOutput] is on.
 *
 * **Where this is applied matters.** It runs *inside* each composer, at the point
 * a dictionary word becomes a candidate — never on the list a composer has
 * already returned. `Composer.consumedFor` finds the chosen candidate by string
 * equality against what the composer produced, so converting afterwards would
 * leave it unable to match, silently falling back to consuming the whole buffer
 * and breaking prefix commit. The converted text may be longer or shorter than
 * the original; the input a candidate consumes comes from its reading, never
 * from its length.
 *
 * ### Words, not characters (#609)
 *
 * One Simplified character can stand for several Traditional ones, and only the
 * word decides which: 发 is 發 in 發展 but 髮 in 頭髮, 后 is 後 in 後來 but stays
 * 后 in 皇后, 干 is 幹 in 幹部 but 乾 in 乾淨. A character map has to pick one
 * and is wrong in every word that wants the other, so this runs OpenCC's own
 * conversion over the bundled OpenCC tables (pinned in `scripts/update-opencc.py`)
 * with OpenCC's matching semantics:
 *
 * 1. **Segment** the candidate by longest match against the phrase table
 *    ([s2tPhrases]); characters no phrase starts at gather into one run.
 * 2. **Convert** each segment: at each position the longest phrase match wins,
 *    else the character table ([s2t]), else the character passes through.
 * 3. **Localize** each converted segment the same way through the region's
 *    tables, which are keyed to *Traditional* text (出租車, not 出租车), so this
 *    step cannot run first. Taiwan is OpenCC's `s2tw` (variant phrases, then
 *    variant characters: 爲 → 為); Taiwan wording is `s2twp`, which puts the
 *    vocabulary table in front of those (出租車 → 計程車); Hong Kong is `s2hk`
 *    (character preferences only); Standard is `s2t` and stops after step 2.
 *
 * ### Characters, not words (#641)
 *
 * Plain Taiwan is `s2tw`, not `s2twp`. A candidate is the word whose reading
 * the user typed; swapping it for a regional synonym read differently (typing
 * `chuzuche` and getting 計程車, jì chéng chē) is autocorrecting "lorry" to
 * "truck". The vocabulary layer is its own choice, [HanRegion.TAIWAN_WORDING],
 * for someone who wants Mainland wording adapted on the way out.
 *
 * Every table group is short-circuit, as in OpenCC: the first table that has
 * *any* match at a position wins, even over a longer match in a later table.
 *
 * The character and regional tables are bundled; the phrase table is the
 * downloadable `s2t_phrases` pack ([CjkDictCatalog]), since it is a megabyte
 * nobody writing Simplified needs. Without it, conversion still works, one
 * character at a time. Tables load only once the toggle is on; until then each
 * is empty and [toTraditional] is the identity, so the toggle is inert rather
 * than destructive. Every assignment invalidates the candidate caches.
 */
object HanVariant {

    /**
     * Which Traditional-using region's conventions to follow. Persisted by name,
     * so entries can be added anywhere but never renamed.
     */
    enum class HanRegion { GENERIC, TAIWAN, TAIWAN_WORDING, HONG_KONG }

    /** OpenCC STCharacters: Simplified → Traditional, one character each. */
    @Volatile
    var s2t: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** OpenCC STPhrases, with the Simplified spellings of the regional phrase keys; the `s2t_phrases` pack. */
    @Volatile
    var s2tPhrases: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Standard Traditional phrase → its Taiwan form (出租車 → 計程車). */
    @Volatile
    var twPhrases: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Words whose Taiwan spelling keeps a character [twVariants] would change. */
    @Volatile
    var twVariantsPhrases: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Standard Traditional → Taiwan character preference (僞 → 偽). */
    @Volatile
    var twVariants: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Words whose Hong Kong spelling keeps a character [hkVariants] would change. */
    @Volatile
    var hkVariantsPhrases: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Standard Traditional → Hong Kong character preference. */
    @Volatile
    var hkVariants: HanPhraseTable = HanPhraseTable.EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** The user's region; only [HanRegion.TAIWAN_WORDING] changes vocabulary. */
    @Volatile
    var region: HanRegion = HanRegion.GENERIC
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * [text] in Traditional when the toggle is on and the tables are loaded;
     * [text] itself otherwise. Characters in no table — most of them, since the
     * two scripts share the majority of their characters — pass through as they
     * are, and when nothing changes the original instance comes back, which
     * keeps the common case allocation-light: this runs per candidate, per
     * keystroke.
     */
    fun toTraditional(text: String): String {
        if (!CjkConfig.traditionalOutput || text.isEmpty()) return text
        val phrases = s2tPhrases
        val standard = arrayOf(phrases, s2t)
        if (standard.all { it.isEmpty() }) return text
        val regional = regionalGroup()
        val out = StringBuilder(text.length + 2)
        var run = 0
        var i = 0
        while (i < text.length) {
            val entry = phrases.matchPrefix(text, i, text.length)
            if (entry < 0) {
                i += Character.charCount(text.codePointAt(i))
                continue
            }
            if (run < i) segment(text, run, i, standard, regional, out)
            val end = i + phrases.keyLength(entry)
            segment(text, i, end, standard, regional, out)
            i = end
            run = end
        }
        if (run < text.length) segment(text, run, text.length, standard, regional, out)
        return if (text.contentEquals(out)) text else out.toString()
    }

    /**
     * [text], already Traditional, in the region's forms. For an engine that
     * writes Traditional by itself and stops at OpenCC's standard forms, as
     * Rime Ice does: 裏 and 爲 under Taiwan (#1083). Harmless over text already
     * localized, since the regional tables have no entry for their own output.
     */
    fun localize(text: String): String {
        if (!CjkConfig.traditionalOutput || text.isEmpty()) return text
        val regional = regionalGroup() ?: return text
        val out = StringBuilder(text.length + 2)
        convert(text, 0, text.length, regional, out)
        return if (text.contentEquals(out)) text else out.toString()
    }

    /**
     * Every Traditional form a candidate can be written in, the conversion
     * first (#608).
     *
     * A word has one right spelling and the phrase table finds it, but a lone
     * character has no word around it to decide: 发 alone is as much 髮 as 發.
     * [toTraditional] has to pick OpenCC's first value, which left 髮, 乾, 后,
     * 里 and 鐘 untypable on their own, so for a single-character candidate the
     * character table's other values follow it, each through the region's
     * tables (里 stays 里, but the first form 裏 becomes Taiwan's 裡). Longer
     * candidates get only [toTraditional]: the phrase table already chose, and
     * multiplying their characters' forms out would bury the strip.
     *
     * Only for reading-based composers. The forms share the reading, which is
     * why they belong under it; a shape code (Cangjie, strokes) describes one
     * glyph and the others would not match it.
     */
    fun traditionalForms(text: String): List<String> {
        val primary = toTraditional(text)
        if (!CjkConfig.traditionalOutput || text.isEmpty() ||
            Character.charCount(text.codePointAt(0)) != text.length
        ) {
            return listOf(primary)
        }
        val alternatives = s2t.alternatives(text)
        if (alternatives.isEmpty()) return listOf(primary)
        val regional = regionalGroup()
        val forms = ArrayList<String>(alternatives.size + 1)
        forms.add(primary)
        for (alternative in alternatives) {
            val form = if (regional == null) {
                alternative
            } else {
                StringBuilder(alternative.length).also { convert(alternative, 0, alternative.length, regional, it) }.toString()
            }
            if (form !in forms) forms.add(form)
        }
        return forms
    }

    /** The region's table group, run over Traditional text; null for Standard. */
    private fun regionalGroup(): Array<HanPhraseTable>? = when (region) {
        HanRegion.GENERIC -> null
        HanRegion.TAIWAN -> arrayOf(twVariantsPhrases, twVariants)
        HanRegion.TAIWAN_WORDING -> arrayOf(twPhrases, twVariantsPhrases, twVariants)
        HanRegion.HONG_KONG -> arrayOf(hkVariantsPhrases, hkVariants)
    }

    private fun segment(
        text: String,
        start: Int,
        end: Int,
        standard: Array<HanPhraseTable>,
        regional: Array<HanPhraseTable>?,
        out: StringBuilder,
    ) {
        if (regional == null) {
            convert(text, start, end, standard, out)
            return
        }
        val traditional = StringBuilder(end - start)
        convert(text, start, end, standard, traditional)
        convert(traditional, 0, traditional.length, regional, out)
    }

    /** OpenCC's `Conversion::Convert` over `[start, end)` with a short-circuit table group. */
    private fun convert(
        text: CharSequence,
        start: Int,
        end: Int,
        group: Array<HanPhraseTable>,
        out: StringBuilder,
    ) {
        var i = start
        while (i < end) {
            var matched = false
            for (table in group) {
                val entry = table.matchPrefix(text, i, end)
                if (entry < 0) continue
                table.appendValue(entry, out)
                i += table.keyLength(entry)
                matched = true
                break
            }
            if (!matched) {
                val n = Character.charCount(Character.codePointAt(text, i))
                out.append(text, i, i + n)
                i += n
            }
        }
    }
}

package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Zhuyin (注音 / Bopomofo) syllables, derived from the pinyin inventory.
 *
 * Zhuyin and toneless pinyin are two spellings of the same Mandarin syllable set,
 * so rather than ship a second reference table this encodes every syllable in
 * [PinyinSyllables.valid] to bopomofo once ([encode]) and keeps the reverse map
 * in [table]. Typing then segments the bopomofo buffer against `table.keys` and
 * looks the *pinyin* value up in the conversion pack the full keyboard already
 * uses — Zhuyin needs no dictionary of its own. Same derive-don't-ship trick as
 * [T9Pinyin], which encodes the same inventory to keypad digits.
 *
 * Tone marks never reach the lookup: the pinyin pack is toneless, and a tone
 * mark is a natural syllable terminator, so [segment] folds it into the span of
 * the syllable it follows and keeps it out of the reading. It records the tone
 * on the [Seg] instead, for [ZhuyinTones] to rank by.
 */
object ZhuyinSyllables {

    /** Bopomofo syllable → toneless pinyin. Empty until [buildTable] runs. */
    @Volatile
    var table: Map<String, String> = emptyMap()

    /** Initial + medial + final: ㄓㄨㄤ (zhuang) is the longest a syllable gets. */
    private const val MAX_SYLLABLE = 3

    /**
     * The tone each mark types. The first tone is unmarked in writing; typing,
     * it is the space bar after a syllable, as on Taiwan's Zhuyin keyboards,
     * and the buffer keeps that space ([FIRST_TONE]). ˉ is its printed mark,
     * which some layouts have a key for. An untoned syllable is complete on its
     * own too — a following initial can only begin a new syllable, since
     * bopomofo's initials, medials and finals are disjoint character classes —
     * and then any tone fits it.
     */
    private val TONES = mapOf(FIRST_TONE to 1, 'ˉ' to 1, 'ˊ' to 2, 'ˇ' to 3, 'ˋ' to 4, '˙' to 5)

    /** The buffer's first-tone mark: the space bar's own character. */
    const val FIRST_TONE = ' '

    /** Whether [c] is a tone mark the buffer can hold, the first tone's space included. */
    fun isTone(c: Char): Boolean = c in TONES

    /**
     * One segmented syllable, its pinyin reading, the bopomofo chars it spanned,
     * and the tone typed after it: 1 to 4, 5 for the neutral tone, 0 for none.
     */
    data class Seg(val syllable: String, val pinyin: String, val inputLen: Int, val tone: Int = 0)

    /**
     * Splits [buffer] into leading syllables against [table], folding any trailing
     * tone mark into that syllable's [Seg.inputLen] — the mirror of the way
     * [PinyinSyllables.segment] folds a *leading* apostrophe. Stops where nothing
     * covers, so a half-typed syllable is left for the caller as raw input.
     *
     * Shares [SyllableSegmenter] with the other conversion scripts, then attaches
     * each unit's pinyin reading. Bopomofo cannot actually mis-split — its
     * initials, medials and finals are disjoint character classes, so no syllable
     * can borrow the next one's first character — but running the same search
     * keeps one walk to reason about rather than three, and lowercasing is off
     * because bopomofo has no case.
     */
    fun segment(buffer: String, table: Map<String, String>): List<Seg> {
        var start = 0
        return SyllableSegmenter.segment(
            buffer,
            table.keys,
            maxUnit = MAX_SYLLABLE,
            skipAfter = { it in TONES },
            lowercase = false,
        ).map {
            // A span longer than its syllable ends in the tone mark folded into it.
            val tone = if (it.inputLen > it.syllable.length) TONES[buffer[start + it.inputLen - 1]] ?: 0 else 0
            start += it.inputLen
            Seg(it.syllable, table.getValue(it.syllable), it.inputLen, tone)
        }
    }

    /** [segment] against the loaded global table. */
    fun segment(buffer: String): List<Seg> = segment(buffer, table)

    /** Groups an inventory into bopomofo → pinyin, skipping anything unencodable. */
    fun buildTable(inventory: Set<String>): Map<String, String> {
        if (inventory.isEmpty()) return emptyMap()
        val acc = HashMap<String, String>()
        for (syllable in inventory) {
            val zhuyin = encode(syllable)
            // Two pinyin spellings never share a bopomofo form, so first-wins is
            // only a guard against a malformed inventory, not a real collision.
            if (zhuyin.isNotEmpty()) acc.putIfAbsent(zhuyin, syllable.lowercase())
        }
        return acc
    }

    /**
     * The bopomofo spelling of a toneless pinyin [syllable] (`ni` → `ㄋㄧ`,
     * `zhuang` → `ㄓㄨㄤ`), or an empty string for anything outside the standard
     * syllable set — interjections like `hm`/`ng` have no bopomofo form and are
     * simply left out of the table.
     */
    fun encode(syllable: String): String {
        val s = syllable.lowercase()
        WHOLE[s]?.let { return it }
        // A syllable with no initial at all is written as its final alone
        // (a → ㄚ, ang → ㄤ, ou → ㄡ). Safe to try before the initial split: no
        // final begins with a consonant, so this can never shadow a real one.
        FINALS[s]?.let { return it }
        // Longest initial first, so zh/ch/sh are not read as z/c/s.
        for (len in 2 downTo 1) {
            if (s.length <= len) continue
            val initial = INITIALS[s.substring(0, len)] ?: continue
            var rest = s.substring(len)
            // After j/q/x the letter u always spells ü — there is no plain -u
            // final in that series, so ju/jue/juan/jun are ㄐㄩ/ㄐㄩㄝ/ㄐㄩㄢ/ㄐㄩㄣ.
            if (s[0] in "jqx" && rest.startsWith("u")) rest = "v" + rest.drop(1)
            val final = FINALS[rest] ?: return ""
            return initial + final
        }
        return ""
    }

    /**
     * Syllables that are not initial+final: the seven whose written -i is no vowel
     * at all (`zhi` is bare ㄓ), the standalone ㄦ, and the y-/w-/yu- spellings
     * pinyin uses when a medial carries the syllable on its own.
     */
    private val WHOLE: Map<String, String> = buildMap {
        put("zhi", "ㄓ"); put("chi", "ㄔ"); put("shi", "ㄕ"); put("ri", "ㄖ")
        put("zi", "ㄗ"); put("ci", "ㄘ"); put("si", "ㄙ")
        put("er", "ㄦ")
        put("yi", "ㄧ"); put("ya", "ㄧㄚ"); put("yo", "ㄧㄛ"); put("ye", "ㄧㄝ")
        put("yai", "ㄧㄞ"); put("yao", "ㄧㄠ"); put("you", "ㄧㄡ"); put("yan", "ㄧㄢ")
        put("yin", "ㄧㄣ"); put("yang", "ㄧㄤ"); put("ying", "ㄧㄥ"); put("yong", "ㄩㄥ")
        put("yu", "ㄩ"); put("yue", "ㄩㄝ"); put("yuan", "ㄩㄢ"); put("yun", "ㄩㄣ")
        put("wu", "ㄨ"); put("wa", "ㄨㄚ"); put("wo", "ㄨㄛ"); put("wai", "ㄨㄞ")
        put("wei", "ㄨㄟ"); put("wan", "ㄨㄢ"); put("wen", "ㄨㄣ"); put("wang", "ㄨㄤ")
        put("weng", "ㄨㄥ")
    }

    private val INITIALS: Map<String, String> = buildMap {
        put("b", "ㄅ"); put("p", "ㄆ"); put("m", "ㄇ"); put("f", "ㄈ")
        put("d", "ㄉ"); put("t", "ㄊ"); put("n", "ㄋ"); put("l", "ㄌ")
        put("g", "ㄍ"); put("k", "ㄎ"); put("h", "ㄏ")
        put("j", "ㄐ"); put("q", "ㄑ"); put("x", "ㄒ")
        put("zh", "ㄓ"); put("ch", "ㄔ"); put("sh", "ㄕ"); put("r", "ㄖ")
        put("z", "ㄗ"); put("c", "ㄘ"); put("s", "ㄙ")
    }

    private val FINALS: Map<String, String> = buildMap {
        put("a", "ㄚ"); put("o", "ㄛ"); put("e", "ㄜ"); put("ê", "ㄝ")
        put("ai", "ㄞ"); put("ei", "ㄟ"); put("ao", "ㄠ"); put("ou", "ㄡ")
        put("an", "ㄢ"); put("en", "ㄣ"); put("ang", "ㄤ"); put("eng", "ㄥ")
        put("i", "ㄧ"); put("ia", "ㄧㄚ"); put("ie", "ㄧㄝ"); put("iao", "ㄧㄠ")
        put("iu", "ㄧㄡ"); put("ian", "ㄧㄢ"); put("in", "ㄧㄣ"); put("iang", "ㄧㄤ")
        put("ing", "ㄧㄥ"); put("iong", "ㄩㄥ")
        put("u", "ㄨ"); put("ua", "ㄨㄚ"); put("uo", "ㄨㄛ"); put("uai", "ㄨㄞ")
        put("ui", "ㄨㄟ"); put("uan", "ㄨㄢ"); put("un", "ㄨㄣ"); put("uang", "ㄨㄤ")
        put("ong", "ㄨㄥ")
        put("v", "ㄩ"); put("ve", "ㄩㄝ"); put("van", "ㄩㄢ"); put("vn", "ㄩㄣ")
    }
}

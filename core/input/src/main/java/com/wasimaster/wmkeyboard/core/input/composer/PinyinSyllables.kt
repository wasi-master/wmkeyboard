package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Toneless-pinyin syllable segmentation. A conversion buffer like `nihao` is one
 * opaque string until it is split into syllables (`ni` | `hao`); only then can a
 * commit consume the leading syllable(s) and leave the tail to re-convert.
 *
 * The valid-syllable inventory (~410 entries) ships as the `pinyin_syllables.txt`
 * asset and is swapped into [valid] off the main thread, mirroring how
 * [CjkDictionaries] loads its conversion tables. Until it loads, [valid] is empty
 * and [segment] returns nothing, so the composer falls back to its raw-buffer
 * behaviour — no crash, no regression.
 *
 * [segment] takes the inventory as a parameter so the pure logic is testable with
 * a tiny fixture set; the no-arg overload uses the loaded global.
 */
object PinyinSyllables {

    /** The loaded valid-syllable inventory; empty until the asset is parsed in. */
    @Volatile
    var valid: Set<String> = emptySet()

    /** Longest real toneless pinyin syllable is 6 chars (`zhuang`, `chuang`, `shuang`). */
    private const val MAX_SYLLABLE = 6

    /**
     * Splits [buffer] into leading syllables using [inventory], honouring `'` as a
     * forced boundary. A leading apostrophe is folded into the next syllable's
     * [Seg.inputLen] so a prefix commit deletes it along with the syllable.
     *
     * Backtracks, via [SyllableSegmenter] — pinyin finals ending in -n/-ng let a
     * syllable borrow the first letter of a vowel-initial one that follows, so
     * greedy both mis-splits (`pingan` never reaching `pin`+`gan`) and drops the
     * tail outright (`sanguo` matching `sang`, then dead-ending on `uo`).
     */
    fun segment(buffer: String, inventory: Set<String>): List<Seg> =
        SyllableSegmenter.segment(
            buffer,
            inventory,
            maxUnit = MAX_SYLLABLE,
            skipBefore = { it == '\'' },
        )

    /**
     * The next initial of a reading [typed] abbreviates (#1028): `nh` of
     * ni hao shi jie gives `s`. Each syllable is typed whole or as its first
     * letter; null when [typed] does not spell it that way or has reached its end.
     */
    fun nextInitial(typed: String, syllables: List<String>): Char? {
        var at = 0
        for (syllable in syllables) {
            if (at == typed.length) return syllable.first()
            when {
                typed.startsWith(syllable, at) -> at += syllable.length
                typed[at] == syllable.first() -> at++
                else -> return null
            }
        }
        return null
    }

    /** [segment] against the loaded global inventory. */
    fun segment(buffer: String): List<Seg> = segment(buffer, valid)

    /** Parses one-syllable-per-line asset text into an inventory set. */
    fun parse(lines: Sequence<String>): Set<String> =
        lines.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
}

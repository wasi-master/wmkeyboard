package com.wasimaster.wmkeyboard.core.input.composer

/**
 * What a swipe over the Pinyin board can spell (#580): every reading in the
 * pinyin table, and the initials of every reading of two syllables or more,
 * each with the frequency of the commonest word behind it.
 *
 * A swipe on a Latin board decodes into a word; here it decodes into a
 * *reading* — `nihao`, or `wbxh` for a run of initials — which goes into the
 * composing buffer exactly as if it had been typed, and the candidate strip
 * converts it the usual way. So the glide decoder needs no idea of Hanzi at
 * all, only of which letter sequences people spell, and how often.
 */
object PinyinGlide {

    /**
     * The spellings and their weights, from [dict] read with [syllables] for
     * the initials. Readings of one syllable are kept: they are the commonest
     * thing typed, and `ni`, `wo` are swiped as often as tapped.
     */
    fun spellings(dict: ConversionDictionary, syllables: Set<String>): List<Pair<String, Int>> {
        if (dict.isEmpty) return emptyList()
        val best = HashMap<String, Int>(dict.size)
        fun keep(spelling: String, freq: Int) {
            if (spelling.isEmpty() || !spelling.all { it in 'a'..'z' }) return
            if ((best[spelling] ?: -1) < freq) best[spelling] = freq
        }
        dict.forEachRow { reading, wordLength, frequency ->
            val freq = frequency.coerceAtLeast(1)
            keep(reading, freq)
            if (wordLength < 2 || syllables.isEmpty()) return@forEachRow
            val segs = PinyinSyllables.segment(reading, syllables)
            // Only a reading the inventory spells whole has initials to take.
            if (segs.size < 2 || segs.sumOf { it.inputLen } != reading.length) return@forEachRow
            keep(segs.joinToString("") { it.syllable.take(1) }, freq)
        }
        return best.entries.map { it.key to it.value }
    }
}

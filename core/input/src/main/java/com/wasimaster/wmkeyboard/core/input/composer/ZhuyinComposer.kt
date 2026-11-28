package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Zhuyin / Bopomofo (注音) input, the standard method in Taiwan. The buffer holds
 * bopomofo symbols typed straight off the pad, and the strip offers Hanzi from
 * the same pinyin pack full-keyboard pinyin uses — the syllable sets are
 * identical, so [ZhuyinSyllables] translates each segmented syllable to its
 * pinyin spelling and looks that up. No Zhuyin dictionary is downloaded.
 *
 * Structurally this is [PinyinComposer] with the reading translated per segment.
 * It is simpler than [JapaneseComposer] in the one place that matters: the buffer
 * *is* the segmentation alphabet, so consumed lengths need no mapping back to
 * keystrokes and [consumedFor] just sums spans. Only the lookup key is
 * translated, never a length.
 *
 * Tones rank rather than look up: the pack has none, so [ZhuyinTones] says
 * which characters can carry the tone typed after each syllable, and those go
 * first. The space bar after a syllable is its first tone, as on Taiwan's
 * Zhuyin keyboards; a second space converts.
 */
object ZhuyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams

    /** Reading space for learned picks: bopomofo is its own notation. */
    private const val NAMESPACE = "zhuyin"

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /**
     * The buffer is already bopomofo — what the user typed is what is shown,
     * with the first tone's space drawn as its mark, ˉ, one for one.
     */
    override fun composeBuffer(buffer: String): String =
        buffer.replace(ZhuyinSyllables.FIRST_TONE, 'ˉ')

    /**
     * The space bar, once a syllable is complete and has no tone yet, is its
     * first tone, kept in the buffer; a second space converts as before. Also
     * the neutral tone's ˙, which is no letter, mid-reading.
     */
    override fun buffersChar(c: Char, composing: CharSequence): Boolean = when {
        composing.isEmpty() -> false
        c == ZhuyinSyllables.FIRST_TONE -> endsInUntonedSyllable(composing.toString())
        else -> c == '˙'
    }

    private fun endsInUntonedSyllable(buffer: String): Boolean {
        if (ZhuyinSyllables.isTone(buffer.last())) return false
        return ZhuyinSyllables.segment(buffer).sumOf { it.inputLen } == buffer.length
    }

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }

    override val adjustsChoices: Boolean get() = true

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.adjust(NAMESPACE, cand.reading, cand.text, cand.text, adjustment)
    }


    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

    /** A candidate, the bopomofo chars (tone marks included) it covers, and its reading. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    /**
     * Candidates for [buffer], longest reading first so a whole phrase outranks
     * its leading syllable, each tagged with its consumed bopomofo length. With
     * nothing segmentable — the table unloaded, or a half-typed syllable — it
     * yields nothing and the raw bopomofo commits, so the buffer never traps the
     * user.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val segs = ZhuyinSyllables.segment(buffer)
        if (segs.isEmpty()) return emptyList()
        // Bopomofo is a second spelling of the same Mandarin syllables, so the
        // decoder sees the pinyin reading and the pack needs no Zhuyin of its own.
        val input = Lattice.input(segs.map { it.pinyin }, segs.map { it.inputLen })
        val decoded = Lattice.decode(
            input,
            CjkDictionaries.pinyin,
            CjkDictionaries.pinyinNgrams,
            Lattice.Opts(limit = LOOKUP_LIMIT, hanLevels = true),
            user = CjkLearning.userData(NAMESPACE),
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading) } }
            .distinctBy { it.text }
        // After the learned picks, so a character learned under the toneless
        // reading cannot climb back over the tone just typed (系 over 吸 for ㄒㄧ ).
        return byTone(CjkLearning.rank(NAMESPACE, decoded, { it.text }, { it.reading }), segs)
    }

    /**
     * [cands] with those whose characters cannot carry the tones typed put
     * after the rest that cover as much of the buffer. Only within that
     * group: each group keeps the places it held, so the whole phrase still
     * comes before its first syllable whatever their tones, and a candidate
     * the tone table has no answer for keeps its place. Nothing is dropped —
     * the table can lack a reading (Taiwan reads some characters with another
     * tone than the mainland), and the character must still be there to pick.
     */
    private fun byTone(cands: List<Cand>, segs: List<ZhuyinSyllables.Seg>): List<Cand> {
        if (cands.size < 2 || segs.none { it.tone in 1..4 }) return cands
        val tones = ZhuyinTones.table
        if (tones.isEmpty) return cands
        val off = BooleanArray(cands.size) { ZhuyinTones.contradicts(cands[it].text, segs, cands[it].consumed, tones) }
        if (off.none { it }) return cands
        val out = cands.toMutableList()
        for (slots in cands.indices.groupBy { cands[it].consumed }.values) {
            val order = slots.filter { !off[it] } + slots.filter { off[it] }
            slots.forEachIndexed { k, slot -> out[slot] = cands[order[k]] }
        }
        return out
    }

    private val cache = RankCache<List<Cand>>()
}

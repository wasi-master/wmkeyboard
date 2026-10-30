package com.wasimaster.wmkeyboard.core.input.composer

/**
 * 12-key Zhuyin (注音九鍵): bopomofo on Samsung's keypad, ten keys of three or four
 * symbols each, with candidates from the same pinyin pack [ZhuyinComposer] and
 * [T9PinyinComposer] use. The buffer is digits, and each syllable's code stands
 * for every syllable sharing it, which the decoder sorts out as it does for T9
 * Pinyin. The tone key types `'`: the pack has no tones, so all a tone can say is
 * that the syllable ends there.
 */
object T9ZhuyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams
    override val bufferDigits: Boolean get() = true
    override val digitsStartBuffer: Boolean get() = true

    private const val NAMESPACE = "zhuyin_t9"

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /** The table the index below was built from, and that index. */
    @Volatile
    private var indexCache: Pair<Map<String, String>, Map<String, List<String>>>? = null

    /** Rebuilt only when [ZhuyinSyllables.table] is, which the pinyin inventory loading does. */
    private fun index(): Map<String, List<String>> {
        val table = ZhuyinSyllables.table
        indexCache?.let { if (it.first === table) return it.second }
        return T9Zhuyin.buildIndex(table).also { indexCache = table to it }
    }

    /**
     * The composing region shows the bopomofo of the best candidate, syllables set
     * apart with `'` so the decoder's split can be seen. Past what that candidate
     * covers a code reads as its first syllable, and with nothing to convert at all
     * the digits stand.
     */
    override fun composeBuffer(buffer: String): String {
        val segs = segments(buffer)
        if (segs.isEmpty()) return buffer
        val index = index()
        val reading = ranked(buffer).firstOrNull()?.reading.orEmpty()
        val out = StringBuilder(buffer.length + segs.size)
        var pos = 0
        var covered = 0
        for (seg in segs) {
            val options = index[seg.syllable] ?: break
            val syllable = options.filter { reading.startsWith(it, pos) }.maxByOrNull { it.length } ?: options.first()
            if (out.isNotEmpty()) out.append('\'')
            out.append(ZhuyinSyllables.encode(syllable))
            pos += syllable.length
            covered += seg.inputLen
        }
        if (covered < buffer.length) out.append(buffer, covered, buffer.length)
        return out.toString()
    }

    /** Enter writes the bopomofo spelled, without the syllable marks [composeBuffer] adds. */
    override fun typedReading(buffer: String): String = composeBuffer(buffer).replace("'", "")

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

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

    /** A candidate, the digits it covers, and the reading that produced it. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    private fun segments(buffer: String): List<Seg> =
        PinyinSyllables.segment(buffer, index().keys)

    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val segs = segments(buffer)
        if (segs.isEmpty()) return emptyList()
        val index = index()
        val options = segs.map { index[it.syllable]?.toTypedArray() ?: return emptyList() }
        return Lattice.decode(
            Lattice.options(options, segs.map { it.inputLen }),
            CjkDictionaries.pinyin,
            CjkDictionaries.pinyinNgrams,
            // Every digit is ambiguous, as on T9 Pinyin, so the cap comes off here too.
            Lattice.Opts(limit = LOOKUP_LIMIT, maxAmbiguousSpan = Int.MAX_VALUE, hanLevels = true),
            user = CjkLearning.userData(NAMESPACE),
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading) } }
            .distinctBy { it.text }
            .let { CjkLearning.rank(NAMESPACE, it, { c -> c.text }, { c -> c.reading }) }
    }

    private val cache = RankCache<List<Cand>>()
}

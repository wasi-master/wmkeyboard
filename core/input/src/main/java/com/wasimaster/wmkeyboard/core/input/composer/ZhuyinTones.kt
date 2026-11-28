package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Which tones each Chinese character is read with, syllable by syllable, so
 * Zhuyin can rank by the tone typed after a syllable.
 *
 * The pinyin pack Zhuyin converts through has toneless readings, and without
 * this the tone marks did nothing: ㄒㄧ with the first tone listed 系 (xì)
 * ahead of 吸 (xī), so 吸血鬼 came out as 系血鬼. [ZhuyinComposer] asks
 * [contradicts] of each candidate and puts the ones that fit the typed tones
 * first among those covering the same syllables.
 *
 * Bundled as `dictionaries/zh_tones.txt`, one line per toned syllable
 * (`xi1<TAB>西希息吸…`), built by `scripts/build_zh_tones.py` from Rime's
 * terra_pinyin (地球拼音). A character takes the tones of its Simplified and
 * Traditional forms together, so the table answers for either. Empty until
 * the service loads it, and empty changes no ranking.
 */
object ZhuyinTones {

    /** Per syllable: sorted code points, each with a bit set for every tone (1 shl 1 … 1 shl 5). */
    class Table internal constructor(private val bySyllable: Map<String, Pair<IntArray, ByteArray>>) {

        val isEmpty: Boolean get() = bySyllable.isEmpty()

        /** The tone bits [codePoint] has under [syllable]; 0 when the table does not know it there. */
        fun tones(codePoint: Int, syllable: String): Int {
            val (points, masks) = bySyllable[syllable] ?: return 0
            val i = points.binarySearch(codePoint)
            return if (i >= 0) masks[i].toInt() else 0
        }
    }

    val EMPTY = Table(emptyMap())

    @Volatile
    var table: Table = EMPTY
        set(value) { field = value; CjkDictionaries.invalidate() }

    /** Lines of `zh_tones.txt`; `#` comments and malformed lines skipped. */
    fun parse(lines: Sequence<String>): Table {
        val acc = HashMap<String, HashMap<Int, Int>>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab < 2) continue
            val tone = line[tab - 1] - '0'
            if (tone !in 1..5) continue
            val syllable = line.substring(0, tab - 1)
            val chars = acc.getOrPut(syllable) { HashMap() }
            var i = tab + 1
            while (i < line.length) {
                val cp = line.codePointAt(i)
                i += Character.charCount(cp)
                chars[cp] = (chars[cp] ?: 0) or (1 shl tone)
            }
        }
        val out = HashMap<String, Pair<IntArray, ByteArray>>(acc.size)
        for ((syllable, chars) in acc) {
            val points = chars.keys.toIntArray().also { it.sort() }
            out[syllable] = points to ByteArray(points.size) { chars.getValue(points[it]).toByte() }
        }
        return Table(out)
    }

    /**
     * Whether [text], read over the leading [segs] that add up to [consumed]
     * buffer characters, has a character the table knows under its syllable
     * but never with the tone typed there. Only tones 1 to 4 count: the
     * neutral tone (˙) depends on the word a character is in, which the table
     * does not record. Anything the table cannot answer, an unknown
     * character or a [text] that is not one character per syllable, is no
     * contradiction.
     */
    fun contradicts(text: String, segs: List<ZhuyinSyllables.Seg>, consumed: Int, table: Table = this.table): Boolean {
        if (table.isEmpty) return false
        var covered = 0
        var used = 0
        while (covered < segs.size && used < consumed) used += segs[covered++].inputLen
        if (used != consumed || text.codePointCount(0, text.length) != covered) return false
        var offset = 0
        for (k in 0 until covered) {
            val cp = text.codePointAt(offset)
            offset += Character.charCount(cp)
            val tone = segs[k].tone
            if (tone !in 1..4) continue
            val known = table.tones(cp, segs[k].pinyin)
            if (known != 0 && known and (1 shl tone) == 0) return true
        }
        return false
    }
}

package com.wasimaster.wmkeyboard.core.input.composer

/**
 * T9 / 九宫格 pinyin: Chinese on a 9-key phone pad. The buffer is digits, each
 * standing for three or four pinyin letters, and the strip offers Hanzi from the
 * same pinyin pack full-keyboard pinyin uses — no separate dictionary.
 *
 * Structurally this is [PinyinComposer] with one extra degree of freedom. There,
 * a segment resolves to exactly one syllable; here a segment resolves to a digit
 * *code* that several syllables share ([T9Pinyin.index]), so each reading is a
 * capped cartesian product over the segments' alternatives — the same widening
 * [PinyinComposer] already does for Fuzzy Pinyin, applied to keypad ambiguity
 * instead of confusable spellings.
 *
 * Because the input alphabet (digits) *is* the segmentation alphabet, consumed
 * lengths need no translation back to keystrokes — unlike [JapaneseComposer],
 * which segments kana but must report romaji. [consumedFor] counts digits
 * directly, so prefix commit works unchanged.
 *
 * A syllable the user picked from [readings] (issue #635) is written into the
 * buffer as its letters in place of its digits: `9426` picked as `xiao` leaves
 * `xiao` composing, and `xiao'94426` once more is typed. Letters and digits never
 * share a unit, so the segmenter splits a mixed buffer with no help, and a letter
 * unit reaches the decoder as the one reading it can be. The buffer stays the
 * whole state — prefix commit, the cache and backspace need nothing new to keep.
 */
object T9PinyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams

    /** The whole input alphabet is digits, and they must be able to *start* a buffer. */
    override val bufferDigits: Boolean get() = true
    override val digitsStartBuffer: Boolean get() = true

    /** Reading space for learned picks: digit codes, not pinyin letters. */
    private const val NAMESPACE = "pinyin_t9"

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /** Longest pinyin syllable, and so the longest digit code (`zhuang`). */
    private const val MAX_SYLLABLE = 6

    /**
     * The composing region shows the pinyin of the best candidate rather than the
     * raw digits: `426` reads back as `hao`, which is what the user is spelling.
     * Syllables are set apart with `'` (`you'dian'xiao`), so the user can see
     * where the decoder split the digits — the split [readings] lets them
     * overrule. Past what the best candidate covers, a unit reads as its code's
     * first syllable, and digits no syllable spells yet stay digits. Falls back to
     * the digits themselves when nothing converts at all.
     */
    override fun composeBuffer(buffer: String): String {
        val best = ranked(buffer).firstOrNull() ?: return buffer
        val segs = segments(buffer)
        val out = StringBuilder(buffer.length + segs.size)
        var pos = 0
        var covered = 0
        for (seg in segs) {
            // One letter to a key, so a syllable is exactly as long as its code.
            val len = seg.syllable.length
            val syllable = if (pos + len <= best.reading.length) {
                best.reading.substring(pos, pos + len)
            } else {
                optionsFor(seg.syllable)?.firstOrNull() ?: seg.syllable
            }
            if (out.isNotEmpty()) out.append('\'')
            out.append(syllable)
            pos += len
            covered += seg.inputLen
        }
        if (covered < buffer.length) out.append(buffer, covered, buffer.length)
        return out.toString()
    }

    /** Enter writes the letters spelled, without the syllable marks [composeBuffer] adds. */
    override fun typedReading(buffer: String): String = composeBuffer(buffer).replace("'", "")

    /**
     * The syllables the first undecided code of [buffer] could be, for the user
     * to pick one (issue #635): every syllable whose code starts the digits
     * after the picked ones, longest first, the way 九宫格 pads list them — `968`
     * offers you and zou, then wo and yo. The one the decoder is already using
     * leads, since that is what the strip shows; ties go to the syllable with the
     * commoner characters. Empty when nothing is left undecided.
     */
    override fun readings(buffer: String): List<String> {
        val run = undecided(buffer).run
        if (run.isEmpty()) return emptyList()
        val index = T9Pinyin.index
        val dict = CjkDictionaries.pinyin
        val found = ArrayList<String>()
        for (len in minOf(MAX_SYLLABLE, run.length) downTo 1) {
            val options = index[run.substring(0, len)] ?: continue
            found += options.sortedByDescending { syllableWeight(dict, it) }
        }
        val leading = decodedNext(buffer)
        return if (leading != null && leading in found) listOf(leading) + (found - leading) else found
    }

    /**
     * [buffer] with its first undecided code written as [reading], one of
     * [readings]: the digits it spelled become its letters, set off from a
     * syllable picked before it by `'` so `xi` then `an` never reads back as
     * `xian`. Null when [reading] does not spell the digits there.
     */
    override fun pickReading(buffer: String, reading: String): String? {
        val (head, run, rest) = undecided(buffer)
        val code = T9Pinyin.encode(reading)
        if (code.isEmpty() || !run.startsWith(code)) return null
        val picked = head.trimEnd('\'')
        val sep = if (picked.isEmpty()) "" else "'"
        return picked + sep + reading.lowercase() + run.substring(code.length) + rest
    }

    /**
     * One backspace takes back the last keypress — and once only picked
     * syllables are left, the last pick: the syllable goes back to the digits it
     * was picked from, so the next press deletes those one at a time again and
     * [readings] can offer it afresh. Never half a syllable, which would read as
     * a different one (`xian` less a letter is `xia`).
     */
    override fun backspaceBuffer(buffer: String): String {
        if (buffer.isEmpty() || !buffer.last().isLetter()) return super.backspaceBuffer(buffer)
        val syllable = buffer.takeLastWhile { it.isLetter() }
        val code = T9Pinyin.encode(syllable)
        if (code.isEmpty()) return super.backspaceBuffer(buffer)
        return buffer.dropLast(syllable.length).trimEnd('\'') + code
    }

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    /** One letter of the reading to a key, so the buffer's length says how far it has got. */
    override fun nextReadingLetter(buffer: String, index: Int): Char? {
        val cand = ranked(buffer).getOrNull(index)?.takeIf { it.consumed == buffer.length } ?: return null
        val letter = cand.reading.getOrNull(buffer.count { it != '\'' }) ?: return null
        return T9Pinyin.encode(letter.toString()).firstOrNull()
    }

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

    /**
     * [buffer] cut where the picking stands: the syllables already picked (with
     * any `'` after them), the first digit run after them, which is what
     * [readings] offers syllables for, and everything past that run's own `'`.
     */
    private data class Undecided(val head: String, val run: String, val rest: String)

    private fun undecided(buffer: String): Undecided {
        val digit = buffer.indexOfFirst { it.isDigit() }
        if (digit < 0) return Undecided(buffer, "", "")
        val end = buffer.indexOf('\'', digit).let { if (it < 0) buffer.length else it }
        return Undecided(buffer.substring(0, digit), buffer.substring(digit, end), buffer.substring(end))
    }

    /** The syllable the best candidate reads the first undecided code as, if it reaches it. */
    private fun decodedNext(buffer: String): String? {
        val best = ranked(buffer).firstOrNull() ?: return null
        var pos = 0
        for (seg in segments(buffer)) {
            val len = seg.syllable.length
            if (seg.syllable.first().isDigit()) {
                return if (pos + len <= best.reading.length) best.reading.substring(pos, pos + len) else null
            }
            pos += len
        }
        return null
    }

    /** How common [syllable]'s commonest single character is, to order equally long readings. */
    private fun syllableWeight(dict: ConversionDictionary, syllable: String): Int {
        return dict.bestFrequency(syllable, 1)
    }

    /**
     * What a unit can be read as: the syllables of a digit code, or the one
     * syllable the user picked for a letter unit. Null for a code no syllable
     * spells, which cannot happen for a unit the segmenter returned.
     */
    private fun optionsFor(unit: String): List<String>? =
        if (unit.first().isDigit()) T9Pinyin.index[unit] else listOf(unit)

    /** The index the inventory below was built from, and that inventory. */
    @Volatile
    private var inventoryCache: Pair<Map<String, List<String>>, Set<String>>? = null

    /**
     * Every unit a buffer can hold: each syllable's digit code, and the syllable
     * itself for one already picked. Rebuilt only when [T9Pinyin.index] is.
     */
    private fun inventory(): Set<String> {
        val index = T9Pinyin.index
        inventoryCache?.let { if (it.first === index) return it.second }
        val units = HashSet<String>(index.size * 3)
        units += index.keys
        for (syllables in index.values) units += syllables
        inventoryCache = index to units
        return units
    }

    /**
     * Splits the digit buffer against the digit codes of real syllables. Reuses
     * [PinyinSyllables.segment] verbatim, because segmenting digit codes and
     * segmenting syllables are the same problem over a different alphabet. Each
     * returned [Seg.syllable] is therefore a digit code, not a syllable.
     *
     * The backtracking search matters more here than anywhere: 412 syllables
     * collapse onto ~230 codes over an eight-symbol alphabet, so ambiguous splits
     * are far commoner among digits than among letters.
     *
     * A syllable picked from [readings] is in the buffer as letters, and letters
     * and digits never meet in one unit, so the same walk splits it off too.
     */
    private fun segments(buffer: String): List<Seg> =
        PinyinSyllables.segment(buffer, inventory())

    /**
     * Candidates for [buffer], longest reading first so a whole-phrase entry
     * outranks its leading syllable, each tagged with the number of *digits* it
     * consumed. Mirrors [PinyinComposer.ranked]; with no segmentable code (a
     * digit run that spells no syllable) it yields nothing and the raw digits
     * commit, so the buffer never traps the user.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val segs = segments(buffer)
        if (segs.isEmpty()) return emptyList()
        // Each digit code stands for several syllables, and that ambiguity goes
        // to the decoder as options rather than being multiplied out into
        // readings. The old cartesian product needed a cap to stay bounded, and
        // the cap cut alphabetically — for 726726726 every surviving reading
        // began `pan`, so 三三三 was unreachable. Here an option no word starts
        // with is pruned by one binary search and costs nothing.
        val options = segs.map { optionsFor(it.syllable)?.toTypedArray() ?: return emptyList() }
        val input = Lattice.options(options, segs.map { it.inputLen })
        return Lattice.decode(
            input,
            CjkDictionaries.pinyin,
            CjkDictionaries.pinyinNgrams,
            // Every digit is ambiguous, so the ambiguity cap has to come off or
            // later syllables get pinned to whichever one sorted first — the
            // alphabetical bias this whole change removes.
            Lattice.Opts(limit = LOOKUP_LIMIT, maxAmbiguousSpan = Int.MAX_VALUE, hanLevels = true),
            user = CjkLearning.userData(NAMESPACE),
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading) } }
            .distinctBy { it.text }
            .let { CjkLearning.rank(NAMESPACE, it, { c -> c.text }, { c -> c.reading }) }
    }

    private val cache = RankCache<List<Cand>>()
}

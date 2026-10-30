package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/** A Cantonese romanisation typed in place of Jyutping, over the same pack. */
enum class CantoneseScheme(internal val tones: String) {
    /**
     * Yale: `j` and `ch` for Jyutping's `z` and `c`, `y` for its `j`, `eu` for
     * its `oe` and `eo`, a bare `a` for `aa`. An `h` after the vowel marks a low
     * tone, so 你 is `nei` or `neih`.
     */
    YALE("123456"),

    /**
     * Cantonese Pinyin (教院拼音), the Hong Kong Education Bureau's scheme and
     * what Gboard calls Cantonese: `dz` and `ts` for `z` and `c`, `y` for the
     * vowel `yu`, `oey` for `eoi`. The entering tones can be written 7, 8 and 9.
     */
    CANTONESE_PINYIN("123456789"),
}

/**
 * How each Jyutping syllable is spelled in another scheme. Worked from the
 * Jyutping inventory rather than listed, so a scheme can only ever offer a
 * syllable the pack can look up. A spelling may stand for two syllables: Yale's
 * `eu` is both `oe` and the rare `eu`, and `ma` both `maa` and `ma`.
 */
object CantoneseSpellings {

    /** Longest first, so `ng`, `gw` and `kw` are never read as `n`, `g` and `k`. */
    private val INITIALS = listOf(
        "ng", "gw", "kw", "b", "p", "m", "f", "d", "t",
        "n", "l", "g", "k", "h", "z", "c", "s", "j", "w",
    )

    private val YALE_INITIALS = mapOf("z" to "j", "c" to "ch", "j" to "y")
    private val YALE_FINALS = mapOf(
        "oe" to "eu", "oeng" to "eung", "oek" to "euk",
        "eoi" to "eui", "eon" to "eun", "eot" to "eut",
    )
    private val PINYIN_INITIALS = mapOf("z" to "dz", "c" to "ts")
    private val PINYIN_FINALS = mapOf(
        "yu" to "y", "yun" to "yn", "yut" to "yt",
        "eoi" to "oey", "eon" to "oen", "eot" to "oet",
    )

    /** Every way [scheme] writes the Jyutping [syllable], without a tone. */
    fun of(scheme: CantoneseScheme, syllable: String): List<String> {
        val initial = INITIALS.firstOrNull { syllable.startsWith(it) }.orEmpty()
        val final = syllable.substring(initial.length)
        return when (scheme) {
            CantoneseScheme.CANTONESE_PINYIN ->
                listOf(PINYIN_INITIALS.getOrDefault(initial, initial) + PINYIN_FINALS.getOrDefault(final, final))
            CantoneseScheme.YALE -> yale(initial, final)
        }
    }

    private fun yale(initial: String, final: String): List<String> {
        val head = YALE_INITIALS.getOrDefault(initial, initial)
        val rest = YALE_FINALS.getOrDefault(final, final)
        // Yale's y and the vowel yu share a letter: 魚 is `yu`, never `yyu`.
        val plain = if (head == "y" && rest.startsWith("y")) head + rest.drop(1) else head + rest
        // An open aa is written a (媽 `ma`), though plenty of people type it long.
        val forms = if (final == "aa") listOf(head + "a", plain) else listOf(plain)
        return forms.flatMap { listOf(it, lowTone(it)) }.distinct()
    }

    /** The `h` of a low tone goes after the vowels: `sihk`, `neih`. A bare nasal takes it last: `mh`. */
    private fun lowTone(spelled: String): String {
        val vowel = spelled.indexOfLast { it in "aeiou" }
        return if (vowel < 0) spelled + "h" else spelled.substring(0, vowel + 1) + "h" + spelled.substring(vowel + 1)
    }

    /** [valid]'s syllables by how [scheme] spells them. */
    fun table(scheme: CantoneseScheme, valid: Set<String>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>(valid.size * 2)
        for (syllable in valid) {
            for (spelled in of(scheme, syllable)) out.getOrPut(spelled) { ArrayList(1) }.add(syllable)
        }
        return out
    }
}

/**
 * Cantonese typed in Yale or Cantonese Pinyin. Each syllable is read back to
 * Jyutping and decoded over the Jyutping pack, so no scheme needs a dictionary
 * of its own and a pick here also teaches [JyutpingComposer], whose history is
 * kept by the same readings.
 *
 * Decodes a graph rather than one segmentation, which is what Yale's tone mark
 * asks for: `neihou` is `nei`+`hou` (你好) and also `neih`+`ou`, and only the
 * words can say which.
 */
class RomanizedCantoneseComposer private constructor(private val scheme: CantoneseScheme) : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingJyutping
    override val readsLatinLetters: Boolean get() = true

    /** Tone digits, as on Jyutping: they continue a syllable and never start one. */
    override val bufferDigits: Boolean get() = true

    override val adjustsChoices: Boolean get() = true

    override fun composeBuffer(buffer: String): String = buffer.lowercase()

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer.lowercase()).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int {
        val b = buffer.lowercase()
        return ranked(b).firstOrNull { it.text == chosen }?.consumed ?: b.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val b = buffer.lowercase()
        return ranked(b).getOrNull(index)?.consumed ?: b.length
    }

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.adjust(NAMESPACE, cand.reading, cand.text, cand.text, adjustment)
    }

    /** A candidate, the input chars it covers and its Jyutping reading. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val table = spellings()
        val graph = graphOf(buffer, table) ?: unfinished(buffer, table) ?: return emptyList()
        val decoded = Lattice.decode(
            graph,
            CjkDictionaries.jyutping,
            CjkDictionaries.ngrams,
            Lattice.Opts(limit = LOOKUP_LIMIT),
            CjkLearning.userData(NAMESPACE),
        )
            .flatMap { c -> HanVariant.traditionalForms(c.text).map { Cand(it, c.consumed, c.reading) } }
            .distinctBy { it.text }
        return CjkLearning.rank(NAMESPACE, decoded, { it.text }, { it.reading })
    }

    /**
     * Every way of splitting the longest stretch of [buffer] that splits at all.
     * Only positions on a complete split become nodes, or the decoder would
     * follow a reading into a dead end. Null when nothing splits.
     */
    private fun graphOf(buffer: String, table: Map<String, List<String>>): Lattice.Graph? {
        val n = buffer.length
        val steps = Array(n) { from -> stepsFrom(buffer, from, table) }
        val reach = BooleanArray(n + 1)
        reach[0] = true
        for (i in 0 until n) if (reach[i]) steps[i].forEach { reach[it.to] = true }
        val end = (n downTo 1).firstOrNull { reach[it] } ?: return null
        val toEnd = BooleanArray(end + 1)
        toEnd[end] = true
        for (i in end - 1 downTo 0) toEnd[i] = steps[i].any { it.to <= end && toEnd[it.to] }
        val positions = (0..end).filter { reach[it] && toEnd[it] }
        val node = IntArray(end + 1)
        positions.forEachIndexed { k, position -> node[position] = k }
        val arcs = Array(positions.size) { k ->
            val position = positions[k]
            if (position == end) return@Array emptyList<Lattice.Arc>()
            steps[position].filter { it.to <= end && toEnd[it.to] }.flatMap { arcsFor(it, node[it.to]) }
        }
        return Lattice.Graph(positions.toIntArray(), arcs)
    }

    /** A spelling starting at or just after a position, and where it lands with its tone digit. */
    private class Step(val syllables: List<String>, val to: Int)

    private fun stepsFrom(buffer: String, from: Int, table: Map<String, List<String>>): List<Step> {
        val n = buffer.length
        var start = from
        while (start < n && buffer[start] == '\'') start++
        val out = ArrayList<Step>(2)
        for (len in 1..minOf(MAX_SPELLING, n - start)) {
            val syllables = table[buffer.substring(start, start + len)] ?: continue
            val after = start + len
            out.add(Step(syllables, if (after < n && buffer[after] in scheme.tones) after + 1 else after))
        }
        return out
    }

    /** The syllables a step can be, then with lazy pronunciation on their merged spellings. */
    private fun arcsFor(step: Step, to: Int): List<Lattice.Arc> {
        val typed = step.syllables.map { Lattice.Arc(to, it) }
        if (!CjkConfig.lazyJyutping) return typed
        val merged = step.syllables
            .flatMap { syllable ->
                JyutpingFuzzy.expand(syllable, JyutpingSyllables.valid)
                    .filter { it != syllable && !sameKeyAsJyutping(syllable, it) }
                    .take(LAZY_VARIANTS)
            }
            .filter { it !in step.syllables }
            .distinct()
        return typed + merged.map { Lattice.Arc(to, it, LAZY_PENALTY, guess = true) }
    }

    /**
     * [JyutpingFuzzy]'s z↔j is there for someone typing Yale's `j` on a Jyutping
     * board. Here the scheme has already read that key, so 知 `ji` must not also
     * offer 二.
     */
    private fun sameKeyAsJyutping(syllable: String, variant: String): Boolean {
        val z = syllable.first() == 'z' && variant.first() == 'j'
        return z || (syllable.first() == 'j' && variant.first() == 'z')
    }

    /**
     * A first syllable still being typed: `ch` or `dz` is no syllable yet, and
     * the pack cannot be asked for it directly since it spells them `c` and `z`.
     * Reads as every syllable those letters start.
     */
    private fun unfinished(buffer: String, table: Map<String, List<String>>): Lattice.Graph? {
        val syllables = table.entries.filter { it.key.startsWith(buffer) }.flatMap { it.value }.distinct()
        if (syllables.isEmpty()) return null
        return Lattice.Graph(
            intArrayOf(0, buffer.length),
            arrayOf(syllables.map { Lattice.Arc(1, it) }, emptyList()),
        )
    }

    /** The inventory [spellingsCache] was built from, and what it gave. */
    @Volatile
    private var spellingsCache: Pair<Set<String>, Map<String, List<String>>>? = null

    private fun spellings(): Map<String, List<String>> {
        val valid = JyutpingSyllables.valid
        spellingsCache?.let { if (it.first === valid) return it.second }
        return CantoneseSpellings.table(scheme, valid).also { spellingsCache = valid to it }
    }

    private val cache = RankCache<List<Cand>>()

    companion object {
        val YALE = RomanizedCantoneseComposer(CantoneseScheme.YALE)
        val CANTONESE_PINYIN = RomanizedCantoneseComposer(CantoneseScheme.CANTONESE_PINYIN)

        /** Picks are kept under the Jyutping reading, whichever scheme typed it. */
        private const val NAMESPACE = "jyutping"
        private const val LIMIT = 12
        private const val LOOKUP_LIMIT = 128

        /** Longest spelling anywhere: Yale's `gwaahng`. */
        private const val MAX_SPELLING = 7

        private const val LAZY_VARIANTS = 2

        /** The lean [JyutpingComposer] gives a merged spelling. */
        private val LAZY_PENALTY = ln(0.15)
    }
}

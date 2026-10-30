package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.CandidateAdjustment
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.Lattice
import com.wasimaster.wmkeyboard.core.input.composer.RankCache
import com.wasimaster.wmkeyboard.core.input.composer.SyllableSegmenter

/**
 * A code table (`.cin`, a Rime `table_translator` schema): each run of keys
 * is a code, and the strip offers the entries whose code starts with it, an
 * exact code first. The same shape as this app's own Cangjie, over any table.
 */
internal class TableMethodComposer(private val id: String) : Composer {

    private val data: ImportedInputMethods.Loaded.Table?
        get() = ImportedInputMethods.loaded(id) as? ImportedInputMethods.Loaded.Table

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true

    /** Array and the Zhuyin tables spell codes with digits too. */
    override val bufferDigits: Boolean get() = data?.takesDigits == true
    override val digitsStartBuffer: Boolean get() = data?.meta?.digitsStart == true

    /** Array's `,./;` and Boshiamy's `[]'` are code keys, not punctuation. */
    override fun buffersChar(c: Char): Boolean = data?.keys?.contains(c) == true

    /** The keys drawn the way the table names them (日月 for Cangjie's `ab`). */
    override fun composeBuffer(buffer: String): String {
        val names = data?.keyNames.orEmpty()
        if (names.isEmpty()) return buffer
        return buildString { for (c in code(buffer)) append(names[c] ?: c.toString()) }
    }

    /** Enter types the keys themselves; a cap like Array's `1-` is not text anybody wants. */
    override fun typedReading(buffer: String): String = buffer

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> {
        val table = data ?: return emptyList()
        val code = code(buffer)
        if (code.isEmpty()) return emptyList()
        // One fixed depth, cut to the caller's: the strip and the expanded grid
        // must agree about which candidate is which.
        return cache.get(code) { table.dict.candidates(code, LOOKUP) }.take(limit)
    }

    private fun code(buffer: String): String = if (data?.meta?.keepCase == true) buffer else buffer.lowercase()

    private val cache = RankCache<List<String>>()

    private companion object {
        const val LIMIT = 12
        const val LOOKUP = 128
    }
}

/**
 * A Rime `script_translator` schema: syllables spelled one after another and
 * converted as a phrase, through the same decoder this app's pinyin and
 * Jyutping use. What a typed spelling may stand for comes from the schema's
 * own spelling rules, worked out at import: tone-free Jyutping, abbreviated
 * initials, Zhuyin keys that spell pinyin syllables.
 */
internal class ReadingMethodComposer(private val id: String) : Composer {

    private val data: ImportedInputMethods.Loaded.Reading?
        get() = ImportedInputMethods.loaded(id) as? ImportedInputMethods.Loaded.Reading

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true

    /**
     * Tone digits follow a syllable, so a plain number still types; a schema
     * whose syllables start with digit keys (Zhuyin on a keyboard map) is the
     * exception.
     */
    override val bufferDigits: Boolean get() = data?.takesDigits == true
    override val digitsStartBuffer: Boolean get() = data?.meta?.digitsStart == true

    override fun buffersChar(c: Char): Boolean = data?.keys?.contains(c) == true

    /**
     * The schema's own display rules (Zhuyin symbols for keys, full pinyin for
     * double-pinyin pairs), applied syllable by syllable with a space between,
     * the way Rime shows its composition: the rules are written for one
     * syllable at a time and anchor on its start and end.
     */
    override fun composeBuffer(buffer: String): String {
        val reading = data ?: return buffer
        val rules = reading.meta.preeditFormat
        if (rules.isEmpty()) return buffer
        val segs = segment(reading, buffer)
        val out = StringBuilder()
        var used = 0
        for (seg in segs) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(formatted(seg.syllable, rules))
            used += seg.inputLen
        }
        val rest = buffer.substring(used.coerceAtMost(buffer.length)).trimStart { it in reading.meta.delimiters }
        if (rest.isNotEmpty()) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(formatted(rest, rules))
        }
        // Rules that change nothing here (pinyin's ü rules on a word without
        // one) leave the keys exactly as typed, unspaced.
        val shown = out.toString()
        return if (shown.replace(" ", "") == buffer.filter { it !in reading.meta.delimiters }) buffer else shown
    }

    /** Rime puts a comment after a tab; only the reading belongs in the field. */
    private fun formatted(text: String, rules: List<String>): String =
        SpellingAlgebra.format(text, rules).substringBefore('\t')

    private fun segment(reading: ImportedInputMethods.Loaded.Reading, buffer: String) =
        SyllableSegmenter.segment(
            buffer,
            reading.options.keys,
            maxUnit = reading.maxUnit.coerceAtLeast(1),
            skipBefore = { it in reading.meta.delimiters },
            lowercase = reading.caseless,
        )

    override fun typedReading(buffer: String): String = buffer

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.learn(namespace, cand.reading, cand.text)
    }

    override val adjustsChoices: Boolean get() = true

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.adjust(namespace, cand.reading, cand.text, cand.text, adjustment)
    }

    /** Picks are kept per method: one schema's `ni` has nothing to do with another's. */
    private val namespace = "im:$id"

    private data class Cand(val text: String, val consumed: Int, val reading: String)

    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val reading = data ?: return emptyList()
        val segs = segment(reading, buffer)
        if (segs.isEmpty()) return emptyList()
        val n = segs.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + segs[i].inputLen
        val units = segs.map { reading.options.getValue(it.syllable) }
        val input = Lattice.Input(
            boundaries,
            Array(n) { units[it].syllables },
            Array(n) { units[it].penalties },
            partialFrom = IntArray(n) { units[it].partialFrom },
        )
        val decoded = Lattice.decode(
            input,
            reading.dict,
            CjkDictionaries.ngrams,
            Lattice.Opts(limit = LOOKUP),
            user = CjkLearning.userData(namespace),
            scope = Lattice.UserScope.PICKS,
        ).map { Cand(it.text, it.consumed, it.reading) }.distinctBy { it.text }
        return CjkLearning.rank(namespace, decoded, { it.text }, { it.reading })
    }

    private val cache = RankCache<List<Cand>>()

    private companion object {
        const val LIMIT = 12
        const val LOOKUP = 128
    }
}

/**
 * An m17n method: the rules write the text in place, as a phonetic layout
 * does, so there is no strip to choose from. The composing region shows what
 * the rules have written so far, and a space commits what they leave when the
 * word closes ([finishBuffer]).
 */
internal class RulesMethodComposer(private val id: String) : Composer {

    private val data: ImportedInputMethods.Loaded.Rules?
        get() = ImportedInputMethods.loaded(id) as? ImportedInputMethods.Loaded.Rules

    override val isTransliterating: Boolean get() = true

    /** ITRANS spells native digits and marks with digits and punctuation keys. */
    override val bufferDigits: Boolean get() = data?.takesDigits == true
    override val digitsStartBuffer: Boolean get() = data?.meta?.digitsStart == true

    override fun buffersChar(c: Char): Boolean = data?.keys?.contains(c) == true

    override fun composeBuffer(buffer: String): String = compose(buffer)

    override fun finishBuffer(buffer: String): String {
        val rules = data ?: return buffer
        return rules.engine.finish(buffer)
    }

    /**
     * What one more key writes, read off the rules: the run with and without
     * it, compared from the front, since a key may rewrite the letter before
     * it (`k` then `h` turns क् into ख्).
     */
    override fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? {
        if (key.isEmpty() || data == null) return null
        val before = compose(buffer)
        val after = compose(buffer + key)
        var shared = 0
        while (shared < before.length && shared < after.length && before[shared] == after[shared]) shared++
        return after.substring(shared)
    }

    private class Composed(val rules: ImportedInputMethods.Loaded.Rules, val buffer: String, val text: String)

    /**
     * One entry: the corners of the keyboard ask what every key would write
     * after the same buffer, which is forty runs that share it.
     */
    @Volatile
    private var last: Composed? = null

    private fun compose(buffer: String): String {
        val rules = data ?: return buffer
        last?.let { if (it.rules === rules && it.buffer == buffer) return it.text }
        val text = rules.engine.run(buffer).text
        last = Composed(rules, buffer, text)
        return text
    }
}

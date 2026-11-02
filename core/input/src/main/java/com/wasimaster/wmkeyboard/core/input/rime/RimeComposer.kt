package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.T9Pinyin

/**
 * The letter that carries on a candidate's reading, from the spelling RIME
 * hints for it (`xi an` for 西安). Null when [buffer] does not spell that far.
 */
internal fun commentNextLetter(comment: String, buffer: String): Char? {
    val syllables = comment.split(' ').filter { it.isNotEmpty() }
    // Only a plain spelling: another schema's comment is a gloss, and a gloss
    // would put a word over a key its reading does not reach.
    if (syllables.isEmpty() || syllables.any { s -> s.any { it !in 'a'..'z' } }) return null
    val reading = syllables.joinToString("")
    val typed = buffer.filter { it.isLetter() }.lowercase()
    return if (reading.startsWith(typed)) reading.getOrNull(typed.length) else null
}

/**
 * A Chinese conversion composer backed by librime: the candidates, their
 * spans and the learning are RIME's, under [schema].
 *
 * Everything about *keys* comes from [base], the built-in composer the layout
 * asked for: which characters the buffer admits, whether digits start it,
 * what the composing region shows, what Enter writes, what backspace takes.
 * The layout is unchanged, so its keys must keep behaving exactly as they
 * did; only the conversion is RIME's. When RIME cannot answer (a deploy is
 * running, the library was dropped), [base] converts instead, so the
 * keyboard never stops typing Chinese.
 *
 * The prefix-commit contract holds by construction: a candidate's span is
 * the end RIME's own menu gives it, mapped back through [RimeKeys] to buffer
 * characters, and indexed by position, never by text. RIME's Simplified and
 * Traditional switches run inside its menu ([RimeOptions]). The one rewrite
 * after that is the region's character forms ([HanVariant.localize]), since not
 * every schema has a switch for them; it keeps each candidate's span.
 *
 * On the 9-key pad the syllable column is [base]'s, since what a digit run
 * can spell is the keypad's business, but led by the syllable RIME's own
 * first candidate reads there, and the composing region shows RIME's
 * spelling, so the column, the region and the strip agree.
 *
 * RIME has no 联想 of its own (librime-predict keys on the exact text of the
 * last commit, and RIME commits whole phrases), so after a commit [predictor]
 * answers: the built-in Mandarin one, over the word pairs and the user's own
 * habits, whichever schema typed the text.
 */
class RimeComposer internal constructor(
    val base: Composer,
    val schema: String,
    private val method: RimeMethod,
    private val options: () -> RimeOptions,
    /** What follows a commit (联想); null where nothing predicts (Cantonese). */
    private val predictor: Composer? = null,
) : Composer {

    private class Ranked(
        val key: String,
        val generation: Int,
        val texts: List<String>,
        /** Buffer characters each candidate consumes; null when [base] ranked it. */
        val consumed: IntArray?,
        /** Each candidate's spelling as RIME hints it (`xi an`), or empty; null when [base] ranked it. */
        val comments: List<String>?,
        /** RIME's own menu position for each candidate; null when [base] ranked it. */
        val menuIndex: IntArray? = null,
    )

    @Volatile private var last: Ranked? = null

    override val isTransliterating: Boolean get() = base.isTransliterating
    override val resumesComposedText: Boolean get() = base.resumesComposedText
    override fun resumeBuffer(text: String): String? = base.resumeBuffer(text)
    override fun backspaceBuffer(buffer: String): String = base.backspaceBuffer(buffer)
    override fun deleteLength(before: CharSequence): Int = base.deleteLength(before)
    override val isRomanBuffer: Boolean get() = base.isRomanBuffer
    override val bufferDigits: Boolean get() = base.bufferDigits
    override val digitsStartBuffer: Boolean get() = base.digitsStartBuffer
    override val spaceEndsMultitap: Boolean get() = base.spaceEndsMultitap
    override fun buffersChar(c: Char): Boolean = base.buffersChar(c)
    override fun buffersChar(c: Char, composing: CharSequence): Boolean = base.buffersChar(c, composing)
    override val glideKeys: Set<Int> get() = base.glideKeys
    override val isConversion: Boolean get() = true
    override val keepsUnconvertedReading: Boolean get() = base.keepsUnconvertedReading
    override val readsLatinLetters: Boolean get() = base.readsLatinLetters
    override fun pickReading(buffer: String, reading: String): String? = base.pickReading(buffer, reading)

    /** On the 9-key pad, RIME's spelling of what its first candidate covers (`xi'an`); else [base]'s. */
    override fun composeBuffer(buffer: String): String =
        if (method == RimeMethod.T9_PINYIN) spelled(buffer) ?: base.composeBuffer(buffer) else base.composeBuffer(buffer)

    override fun finishBuffer(buffer: String): String =
        if (method == RimeMethod.T9_PINYIN) composeBuffer(buffer) else base.finishBuffer(buffer)

    override fun typedReading(buffer: String): String =
        if (method == RimeMethod.T9_PINYIN) composeBuffer(buffer).replace("'", "") else base.typedReading(buffer)

    /**
     * [base]'s syllables for the first undecided digit run, with the one
     * RIME's first candidate reads there moved to the top: the column leads
     * with what the strip is showing.
     */
    override fun readings(buffer: String): List<String> {
        val found = base.readings(buffer)
        if (method != RimeMethod.T9_PINYIN || found.isEmpty()) return found
        val next = spelledNext(buffer) ?: return found
        return if (next in found) listOf(next) + (found - next) else found
    }

    override fun predictions(context: String, limit: Int): List<String> =
        predictor?.predictions(context, limit).orEmpty()

    override fun learnPrediction(context: String, picked: String) {
        predictor?.learnPrediction(context, picked)
    }

    // RIME needs no CJK pack of the built-in kind, and offers none.
    override val missingPack: String? get() = null
    override val improvingPack: String? get() = null

    override fun candidates(buffer: String): List<String> = candidates(buffer, STRIP)

    /** Ranked once to [DEPTH] and cut, so a shorter list is always a prefix of a longer one. */
    override fun candidates(buffer: String, limit: Int): List<String> = ranked(buffer).texts.take(limit)

    override fun consumedFor(buffer: String, chosen: String): Int {
        val ranked = ranked(buffer)
        val index = ranked.texts.indexOf(chosen)
        return if (index >= 0) consumedForIndex(buffer, index) else buffer.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val ranked = ranked(buffer)
        val consumed = ranked.consumed ?: return base.consumedForIndex(buffer, index)
        return consumed.getOrNull(index) ?: buffer.length
    }

    /**
     * Tells RIME the pick, which is what its user dictionary learns from. The
     * keyboard calls this only where learning is allowed (not in incognito or
     * a password field), after it has asked [consumedForIndex].
     */
    override fun learnChoice(buffer: String, index: Int) {
        val ranked = ranked(buffer)
        if (ranked.consumed == null) {
            base.learnChoice(buffer, index)
            return
        }
        val consumed = ranked.consumed.getOrNull(index) ?: return
        val input = RimeKeys.translate(method, buffer)
        val menuIndex = ranked.menuIndex?.getOrNull(index) ?: return
        RimeRuntime.pick(schema, options(), input.input, menuIndex, inputCharsFor(input, consumed))
        last = null
    }

    private fun ranked(buffer: String): Ranked {
        val opts = options()
        // The region and whether its tables have landed change the text too.
        val key = "$schema|$opts|${HanVariant.region}|${HanVariant.s2t.isEmpty()}|$buffer"
        val generation = RimeRuntime.generation
        last?.takeIf { it.key == key && it.generation == generation }?.let { return it }
        val input = RimeKeys.translate(method, buffer)
        val menu = if (input.input.isEmpty()) null else RimeRuntime.menu(schema, opts, input.input, DEPTH)
        val ranked = if (menu != null) {
            // Two forms can come out as one (裏 and 裡 under Taiwan), and the
            // strip wants each word once: the first keeps its place.
            val localized = menu.texts.map(HanVariant::localize)
            val seen = HashSet<String>()
            val kept = localized.indices.filter { seen.add(localized[it]) }
            Ranked(
                key,
                generation,
                kept.map { localized[it] },
                IntArray(kept.size) { i -> input.bufferChars(menu.consumed[kept[i]]).coerceIn(1, buffer.length) },
                kept.map { menu.comments[it] },
                kept.toIntArray(),
            )
        } else {
            // Not cached under the RIME key: the next call may find RIME back.
            return Ranked("", -1, base.candidates(buffer, DEPTH), null, null)
        }
        last = ranked
        return ranked
    }

    /**
     * The key a candidate floats over (#1063), from RIME's own spelling of it.
     * Full pinyin only: on the other boards the keys do not spell that comment.
     */
    override fun nextReadingLetter(buffer: String, index: Int): Char? {
        if (method != RimeMethod.PINYIN) return null
        val comments = ranked(buffer).comments ?: return base.nextReadingLetter(buffer, index)
        return commentNextLetter(comments.getOrNull(index) ?: return null, buffer)
    }

    /** After a commit the words are [predictor]'s, so it knows their readings. */
    override fun predictionKey(word: String): Char? =
        if (method == RimeMethod.PINYIN) predictor?.predictionKey(word) else null

    override fun primePredictionKeys() {
        if (method == RimeMethod.PINYIN) predictor?.primePredictionKeys()
    }

    /**
     * The first candidate RIME found under a spelling of its keys, as that
     * spelling's syllables and the buffer characters it covers. A spelling
     * counts only when it is exactly the keys typed, letter for digit: Rime
     * Ice also finds words by their initials and by loose spellings, and
     * `9` shown as `yi` (一, found by its initial) would put letters on
     * screen nobody typed.
     */
    private fun spelling(buffer: String): Pair<List<String>, Int>? {
        val ranked = ranked(buffer)
        val comments = ranked.comments ?: return null
        val consumed = ranked.consumed ?: return null
        for (i in comments.indices) {
            val syllables = comments[i].split(' ').filter { it.isNotEmpty() }
            if (syllables.isEmpty() || syllables.any { s -> s.any { it !in 'a'..'z' } }) continue
            val covered = consumed[i]
            return if (keys(buffer.substring(0, covered)) == keys(syllables.joinToString(""))) syllables to covered else null
        }
        return null
    }

    /** [buffer] as [spelling] spells it, `'` between syllables, with what it does not cover as [base] shows it. */
    private fun spelled(buffer: String): String? {
        val (syllables, covered) = spelling(buffer) ?: return null
        val rest = buffer.substring(covered).trimStart('\'')
        val head = syllables.joinToString("'")
        return if (rest.isEmpty()) head else head + "'" + base.composeBuffer(rest)
    }

    /**
     * The syllable [spelling] reads the first undecided digit run as: past
     * the syllables already picked, which sit in the buffer as letters.
     */
    private fun spelledNext(buffer: String): String? {
        val digit = buffer.indexOfFirst { it.isDigit() }
        if (digit < 0) return null
        val (syllables, _) = spelling(buffer) ?: return null
        val picked = buffer.substring(0, digit).filter { it.isLetter() }.lowercase()
        var spelled = ""
        var k = 0
        while (spelled.length < picked.length && k < syllables.size) spelled += syllables[k++]
        if (spelled != picked) return null
        return syllables.getOrNull(k)
    }

    /** The input characters behind [bufferChars] buffer characters. */
    private fun inputCharsFor(input: RimeInput, bufferChars: Int): Int {
        var k = 0
        while (k < input.input.length && input.bufferChars(k + 1) <= bufferChars) k++
        return k.coerceAtLeast(1)
    }

    private companion object {
        /** [text]'s keys on the 9-key pad: letters as their digits, digits as they are, marks dropped. */
        fun keys(text: String): String = buildString {
            for (c in text) {
                if (c.isDigit()) append(c) else if (c.isLetter()) append(T9Pinyin.encode(c.toString()))
            }
        }

        /** What the strip shows, as the built-in composers do. */
        const val STRIP = 12

        /** How deep a reading is ranked; the expanded grid pages through this. */
        const val DEPTH = 100
    }
}

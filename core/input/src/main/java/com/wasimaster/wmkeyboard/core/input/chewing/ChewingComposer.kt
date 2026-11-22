package com.wasimaster.wmkeyboard.core.input.chewing

import com.wasimaster.wmkeyboard.core.input.composer.Composer

/**
 * A Zhuyin conversion composer backed by libchewing: the sentence, the
 * phrases and the learning are Chewing's (新酷音), the way Taiwan's own
 * Zhuyin keyboards convert. A whole reading is typed and converts as one
 * sentence, phrase by phrase, instead of a word at a time.
 *
 * Everything about *keys* comes from [base], the built-in Zhuyin composer:
 * the buffer is the bopomofo and tone marks typed, and backspace, what goes
 * into the buffer and what a digit does are its answers. libchewing is
 * handed that bopomofo on every call and replays it from empty
 * ([ChewingConverter.convert]), so nothing here can drift from what the
 * field shows. When libchewing cannot answer (opening, closing, dropped),
 * [base] converts instead, so the keyboard never stops typing Chinese.
 *
 * The list is libchewing's: the whole sentence first, its next two
 * conversions, then what the reading can start with, longest phrase first.
 * A sentence commits the whole reading; a phrase commits its syllables and
 * the rest converts again, as with the built-in composers.
 *
 * The composing region shows what Chewing shows: the syllables already
 * complete, converted, and the one still being typed as its bopomofo. Enter
 * writes what the region shows ([typedReading]), as Enter does in Chewing.
 */
internal class ChewingComposer(
    val base: Composer,
    private val converter: ChewingConverter,
) : Composer {

    private class Ranked(
        val buffer: String,
        val generation: Int,
        val texts: List<String>,
        /** Buffer characters each candidate consumes; null when [base] ranked it. */
        val consumed: IntArray?,
        /** Which whole-sentence conversion a candidate is, -1 for a phrase. */
        val sentence: IntArray,
        /** The composing region's text; null when [base] ranked it. */
        val shown: String?,
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

    // The region shows converted text, not one glyph per key, so a caret put
    // inside it has no key to land in front of: the reading is not edited in
    // place, and positions map as the keys' own.
    override val editsReadingInPlace: Boolean get() = false
    override fun previewOffset(buffer: String, index: Int): Int = base.previewOffset(buffer, index)
    override fun bufferIndex(buffer: String, offset: Int): Int = base.bufferIndex(buffer, offset)

    // libchewing brings its own dictionary and needs none of the built-in packs.
    override val missingPack: String? get() = null
    override val improvingPack: String? get() = null

    override fun composeBuffer(buffer: String): String {
        val ranked = ranked(buffer)
        return ranked.shown ?: base.composeBuffer(buffer)
    }

    override fun finishBuffer(buffer: String): String = composeBuffer(buffer)

    /** What the region shows, as Enter writes it in Chewing; the keys when nothing converted. */
    override fun typedReading(buffer: String): String = composeBuffer(buffer)

    override fun candidates(buffer: String): List<String> = candidates(buffer, STRIP)

    /** Ranked once to [DEPTH] and cut, so a shorter list is always a prefix of a longer one. */
    override fun candidates(buffer: String, limit: Int): List<String> = ranked(buffer).texts.take(limit)

    override fun consumedFor(buffer: String, chosen: String): Int {
        val index = ranked(buffer).texts.indexOf(chosen)
        return if (index >= 0) consumedForIndex(buffer, index) else buffer.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val ranked = ranked(buffer)
        val consumed = ranked.consumed ?: return base.consumedForIndex(buffer, index)
        return consumed.getOrNull(index) ?: buffer.length
    }

    /**
     * Tells libchewing the pick, which is what it learns from: a sentence's
     * phrases and its runs of single characters, or the one phrase picked
     * for the start of the reading. The keyboard calls this only where
     * learning is allowed (not in incognito or a password field).
     */
    override fun learnChoice(buffer: String, index: Int) {
        val ranked = ranked(buffer)
        val consumed = ranked.consumed
        if (consumed == null) {
            base.learnChoice(buffer, index)
            return
        }
        val text = ranked.texts.getOrNull(index) ?: return
        val sentence = ranked.sentence.getOrElse(index) { -1 }
        if (sentence >= 0) {
            converter.learnSentence(buffer, sentence)
        } else {
            converter.learnPhrase(buffer, consumed[index], text)
        }
        last = null
    }

    override fun predictions(context: String, limit: Int): List<String> = base.predictions(context, limit)

    override fun learnPrediction(context: String, picked: String) = base.learnPrediction(context, picked)

    private fun ranked(buffer: String): Ranked {
        val generation = converter.generation
        last?.takeIf { it.buffer == buffer && it.generation == generation }?.let { return it }
        if (buffer.isEmpty()) return Ranked(buffer, generation, emptyList(), IntArray(0), IntArray(0), "")
        // Not cached when libchewing did not answer: the next call may find it back.
        val menu = converter.convert(buffer, DEPTH)
            ?: return Ranked(buffer, -1, base.candidates(buffer, DEPTH), null, IntArray(0), null)
        val tail = menu.tail.coerceIn(0, buffer.length)
        val ranked = Ranked(
            buffer = buffer,
            generation = generation,
            texts = menu.texts,
            consumed = menu.consumed,
            sentence = menu.sentence,
            // Nothing libchewing has a word for: the keys stay as typed, and
            // that is also what a flush then writes.
            shown = if (menu.texts.isEmpty()) {
                base.composeBuffer(buffer)
            } else {
                menu.preedit + base.composeBuffer(buffer.substring(tail))
            },
        )
        last = ranked
        return ranked
    }

    internal companion object {
        /** What the strip shows, as the built-in composers do. */
        const val STRIP = 12

        /** How deep a reading is ranked; the expanded grid pages through this. */
        const val DEPTH = 100
    }
}

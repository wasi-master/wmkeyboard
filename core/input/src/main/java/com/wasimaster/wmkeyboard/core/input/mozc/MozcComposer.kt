package com.wasimaster.wmkeyboard.core.input.mozc

import com.wasimaster.wmkeyboard.core.input.composer.CjkContext
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.Kana

/**
 * A Japanese conversion composer backed by Mozc: the candidates, how much
 * of the reading each covers, the segments and the learning are Mozc's.
 *
 * Everything about *keys* comes from [base], the built-in composer the
 * layout asked for: romaji, flick, 12-key and JIS kana all compose kana
 * through it unchanged, and what the composing region shows, what Enter
 * writes and what backspace takes are its answers. Mozc is handed the kana
 * it composed ([Kana.transduce]), never the keys, so a span Mozc reports in
 * kana maps back to buffer characters through the spans the transducer
 * kept. When Mozc cannot answer (opening, closing, dropped), [base]
 * converts instead, so the keyboard never stops typing Japanese.
 *
 * Two lists, as in Mozc's own Android keyboard:
 *
 * - While the reading is typed, Mozc's mobile prediction: whole-reading
 *   conversions and predictions first, then partial ones that convert only
 *   the start of the reading (its "auto partial suggestion"). A pick
 *   commits that much and the rest converts again.
 * - Once the user moves a segment boundary or the focus ([resizeSegment],
 *   [moveSegment]), Mozc's segmented conversion: the composing region shows
 *   every segment converted, and the candidates are the focused segment's.
 *   A pick commits every segment up to the focused one.
 *
 * The typing list also carries the words the user imported for Japanese
 * with readings ([CjkDictionaries.japaneseUser], #1254), which Mozc's own
 * dictionary has never heard of: a Gboard personal dictionary's `よろ →
 * よろしくお願いします` comes first when `よろ` is typed, and a word filed
 * under a longer reading shows a few places down as a completion once the
 * first two kana of it are typed.
 */
class MozcComposer internal constructor(val base: Composer) : Composer {

    /** [buffer] split into segments, after these [MozcOps] from Mozc's own split. */
    private class Segmenting(val buffer: String, val ops: IntArray)

    private class Ranked(
        val key: String,
        val generation: Int,
        val texts: List<String>,
        /** Buffer characters each candidate consumes; null when [base] ranked it. */
        val consumed: IntArray?,
        /** Mozc's id per candidate, -1 for the kana forms added here. */
        val ids: IntArray,
        val reading: String,
        val preceding: String,
        /** Null for the typing list; the replayed operations for a segmented one. */
        val ops: IntArray?,
        /** Segmented only: each segment's converted text, and the focused one. */
        val segments: List<String>,
        val focus: Int,
    )

    @Volatile private var segmenting: Segmenting? = null
    @Volatile private var last: Ranked? = null
    @Volatile private var lastPredictions: Pair<String, MozcRuntime.Menu>? = null

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
    override val glidesKanaReadings: Boolean get() = base.glidesKanaReadings
    override val isConversion: Boolean get() = true
    override val keepsUnconvertedReading: Boolean get() = base.keepsUnconvertedReading
    override val readsLatinLetters: Boolean get() = base.readsLatinLetters
    override fun finishBuffer(buffer: String): String = base.finishBuffer(buffer)
    override fun typedReading(buffer: String): String = base.typedReading(buffer)

    // Unsegmented, the reading shows as the base composer's kana; the service
    // only edits inside a reading that is not split (#960).
    override fun previewOffset(buffer: String, index: Int): Int = base.previewOffset(buffer, index)
    override fun bufferIndex(buffer: String, offset: Int): Int = base.bufferIndex(buffer, offset)

    // Mozc brings its own dictionary, and needs none of the built-in packs.
    override val missingPack: String? get() = null
    override val improvingPack: String? get() = null

    override val segmentsReading: Boolean get() = MozcRuntime.ready

    /** Segmented, every segment converted; otherwise the kana the base composer shows. */
    override fun composeBuffer(buffer: String): String {
        if (segmenting?.buffer != buffer) return base.composeBuffer(buffer)
        val ranked = ranked(buffer)
        return if (ranked.ops != null && ranked.segments.isNotEmpty()) ranked.segments.joinToString("") else base.composeBuffer(buffer)
    }

    override fun focusedSegment(buffer: String): IntRange? {
        if (segmenting?.buffer != buffer) return null
        val ranked = ranked(buffer)
        if (ranked.ops == null || ranked.segments.isEmpty()) return null
        val start = ranked.segments.take(ranked.focus).sumOf { it.length }
        return start until start + ranked.segments[ranked.focus].length
    }

    override fun resizeSegment(buffer: String, delta: Int): Boolean =
        segment(buffer, if (delta > 0) MozcOps.EXPAND else MozcOps.SHRINK)

    override fun moveSegment(buffer: String, delta: Int): Boolean =
        segment(buffer, if (delta > 0) MozcOps.FOCUS_RIGHT else MozcOps.FOCUS_LEFT)

    private fun segment(buffer: String, op: Int): Boolean {
        if (buffer.isEmpty() || !MozcRuntime.ready) return false
        val before = segmenting?.takeIf { it.buffer == buffer }
        // The first press splits the reading as Mozc would and then applies
        // itself, as Shift+arrow does after Space in Mozc on a desktop.
        val was = before?.let { ranked(buffer) }
        segmenting = Segmenting(buffer, (before?.ops ?: IntArray(0)) + op)
        last = null
        val now = ranked(buffer)
        if (now.ops == null || now.segments.isEmpty()) {
            segmenting = before
            return false
        }
        return was == null || was.segments != now.segments || was.focus != now.focus
    }

    /**
     * After a segmented pick the rest of the reading stays split, as the
     * remaining segments do in Mozc, so the next segment's candidates follow.
     */
    override fun remainderAfterCommit(rest: String): String {
        val kept = base.remainderAfterCommit(rest)
        segmenting = if (segmenting != null && kept.isNotEmpty()) Segmenting(kept, IntArray(0)) else null
        return kept
    }

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

    override fun committedText(buffer: String, index: Int, chosen: String): String {
        val ranked = ranked(buffer)
        if (ranked.ops == null || ranked.focus == 0 || ranked.texts.getOrNull(index) != chosen) return chosen
        return ranked.segments.take(ranked.focus).joinToString("") + chosen
    }

    /**
     * Tells Mozc the pick, which is what it learns from: its prediction
     * history, its segment boundaries and which conversion a reading takes.
     * The keyboard calls this only where learning is allowed (not in
     * incognito or a password field), before it commits.
     */
    override fun learnChoice(buffer: String, index: Int) {
        val ranked = ranked(buffer)
        if (ranked.consumed == null) {
            base.learnChoice(buffer, index)
            return
        }
        val id = ranked.ids.getOrNull(index)?.takeIf { it >= 0 } ?: return
        if (ranked.ops != null) {
            MozcRuntime.commitConversion(ranked.reading, ranked.preceding, ranked.ops, id)
        } else {
            MozcRuntime.commitSuggestion(ranked.reading, ranked.preceding, id)
        }
        last = null
    }

    override val predictsFromAnyText: Boolean get() = true

    /**
     * Mozc's zero-query suggestion: what usually follows the text before the
     * caret. Read from [CjkContext.text] rather than [context], which holds
     * only its Han tail.
     */
    override fun predictions(context: String, limit: Int): List<String> {
        val preceding = CjkContext.text
        val menu = MozcRuntime.predict(preceding, maxOf(limit, 1)) ?: return emptyList()
        lastPredictions = preceding to menu
        return menu.texts.take(limit)
    }

    override fun learnPrediction(context: String, picked: String) {
        val (preceding, menu) = lastPredictions ?: return
        val index = menu.texts.indexOf(picked).takeIf { it >= 0 } ?: return
        MozcRuntime.commitPrediction(preceding, menu.ids[index])
        lastPredictions = null
    }

    private fun ranked(buffer: String): Ranked {
        val split = segmenting
        val ops = split?.takeIf { it.buffer == buffer }?.ops
        // A different reading ends the split: typing on, or backspace.
        if (split != null && ops == null) segmenting = null
        val preceding = CjkContext.text
        val user = CjkDictionaries.japaneseUser
        val key = "$buffer|${ops?.joinToString(",")}|$preceding|${System.identityHashCode(user)}"
        val generation = MozcRuntime.generation
        last?.takeIf { it.key == key && it.generation == generation }?.let { return it }
        if (buffer.isEmpty()) return Ranked(key, generation, emptyList(), IntArray(0), IntArray(0), "", preceding, null, emptyList(), 0)

        val spans = Kana.transduce(buffer)
        val reading = spans.joinToString("") { it.kana }
        // Buffer characters behind the first k reading characters. A count
        // that ends inside one transducer step (the き of きょ) takes the
        // whole step, which is the only way to cut the buffer there.
        val toBuffer = IntArray(reading.length + 1)
        var kana = 0
        var keys = 0
        for (span in spans) {
            keys += span.romajiLen
            for (k in 1..span.kana.length) toBuffer[kana + k] = keys
            kana += span.kana.length
        }
        fun bufferChars(readingChars: Int) = toBuffer[readingChars.coerceIn(1, reading.length)].coerceIn(1, buffer.length)

        // Romaji not yet kana (the `h` of `kyouh`) stays out of what Mozc
        // converts: its candidates cover the kana before it, and the letter
        // stays composing after a pick, as it would on its own. The kana forms
        // below still take the whole buffer.
        val converted = reading.trimEnd { it in 'a'..'z' || it in 'A'..'Z' }
        val menu = when {
            converted.isEmpty() -> EMPTY
            ops != null -> MozcRuntime.convert(converted, preceding, ops, DEPTH)
            else -> MozcRuntime.suggest(converted, preceding, DEPTH)
        }
        // Not cached under the Mozc key: the next call may find Mozc back.
        if (menu == null) {
            return Ranked("", -1, base.candidates(buffer, DEPTH), null, IntArray(0), reading, preceding, null, emptyList(), 0)
        }

        val texts = ArrayList<String>(menu.texts.size + 3)
        val consumed = ArrayList<Int>(menu.texts.size + 3)
        val ids = ArrayList<Int>(menu.texts.size + 3)
        val seen = HashSet<Pair<String, Int>>()
        fun add(text: String, chars: Int, id: Int, cap: Int) {
            if (texts.size >= cap || !seen.add(text to chars)) return
            texts += text
            consumed += chars
            ids += id
        }
        // The user's own words for this reading (#1254), never segmented: a
        // registered phrase is one unit. They take the reading Mozc was given,
        // and carry no Mozc id, so picking one teaches Mozc nothing.
        val userChars = if (converted.isEmpty()) 0 else bufferChars(converted.length)
        val completions = if (ops == null && converted.isNotEmpty()) user.completions(converted, USER_COMPLETIONS) else emptyList()
        if (ops == null && converted.isNotEmpty()) {
            for (word in user.exact(converted)) add(word, userChars, -1, DEPTH - KANA_FORMS)
        }
        // Mozc's own list stops short of the depth, so the kana forms after it
        // always fit.
        for (i in menu.texts.indices) {
            if (i == COMPLETIONS_AT) for (word in completions) add(word, userChars, -1, DEPTH - KANA_FORMS)
            add(menu.texts[i], bufferChars(menu.consumed[i]), menu.ids[i], DEPTH - KANA_FORMS)
        }
        if (menu.texts.size <= COMPLETIONS_AT) for (word in completions) add(word, userChars, -1, DEPTH - KANA_FORMS)
        // The kana themselves stay reachable, as with the built-in composer:
        // the whole reading's while typing, the focused segment's once split.
        val (kanaReading, kanaChars) = if (ops != null && menu.segments.isNotEmpty()) {
            val focus = menu.focus.coerceIn(0, menu.segments.size - 1)
            menu.segments[focus].first to bufferChars(menu.segments.take(focus + 1).sumOf { it.first.length })
        } else {
            reading to buffer.length
        }
        add(kanaReading, kanaChars, -1, DEPTH)
        add(Kana.toKatakana(kanaReading), kanaChars, -1, DEPTH)
        if (ops == null) add(Kana.toHalfWidthKatakana(Kana.toKatakana(kanaReading)), kanaChars, -1, DEPTH)

        val ranked = Ranked(
            key = key,
            generation = generation,
            texts = texts,
            consumed = consumed.toIntArray(),
            ids = ids.toIntArray(),
            reading = converted,
            preceding = preceding,
            ops = ops,
            segments = menu.segments.map { it.second },
            focus = menu.focus.coerceIn(0, (menu.segments.size - 1).coerceAtLeast(0)),
        )
        last = ranked
        return ranked
    }

    private companion object {
        /** What the strip shows, as the built-in composers do. */
        const val STRIP = 12

        /** How deep a reading is ranked; the expanded grid pages through this. */
        const val DEPTH = 100

        /** Hiragana, katakana and half-width katakana, kept room for at the end. */
        const val KANA_FORMS = 3

        /** Where the user's completions join Mozc's list: after its best few. */
        const val COMPLETIONS_AT = 3

        /** How many of the user's completions one reading offers. */
        const val USER_COMPLETIONS = 3

        /** Nothing for Mozc to convert: only the kana forms are offered. */
        val EMPTY = MozcRuntime.Menu(emptyList(), IntArray(0), IntArray(0), emptyList(), 0)
    }
}

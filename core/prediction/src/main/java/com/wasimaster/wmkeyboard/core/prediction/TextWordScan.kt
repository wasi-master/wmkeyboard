package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.text.AddressToken
import com.wasimaster.wmkeyboard.core.transliteration.Zawgyi

/**
 * Reads a piece of already-written text the way the keyboard would have read
 * it had it been typed (#174): the words in it, how often each appears and in
 * what spelling, and the word pairs and triples it holds.
 *
 * The Learn from text tool is the consumer. It hands this a whole field or a
 * selection, keeps the words nothing recognises as rows to add, and learns the
 * pairs once the user has said which words are real. Pure, so the rules below
 * are unit-tested off the device.
 *
 * Tokens follow [WordContext.isWordChar] plus digits, with a single apostrophe,
 * hyphen or zero-width joiner allowed between two of those, the same shape
 * [WordContext.isLearnableWord] accepts. What is skipped, and breaks the run of
 * words a pair can span:
 *  - a whitespace-separated chunk that looks like a link, an email address or a
 *    handle (`://`, `@`, `www.`, or a host with a known ending such as
 *    `example.com/path`): its pieces are not words anyone typed;
 *  - a token that is not learnable, such as a number ("2024");
 *  - a token in a script written without spaces that has no segmenter here
 *    (Chinese, Japanese, Tibetan, Javanese), where a run of letters between
 *    spaces is a phrase, not a word.
 *
 * Thai, Lao, Khmer and Burmese are written without spaces too, but
 * [WordSegments] finds the words inside their runs, so each is scanned as
 * the words it holds and pairs run across them. Burmese the detector is sure
 * is Zawgyi is skipped as before: the segmenter reads Unicode Burmese, and
 * Zawgyi uses the same code points for other things.
 *
 * A sentence ender or a line break also ends the run, so no pair crosses a full
 * stop or a paragraph — the rule [WordContext.completedWordBefore] applies to
 * typed text.
 */
object TextWordScan {

    /** One distinct word, keyed the way every store keys it ([WordKey.of]). */
    data class Word(
        val key: String,
        /**
         * The spelling to offer. The most common one seen away from the start
         * of a sentence, where a capital means something; a word only ever
         * seen at a sentence start is offered in lowercase, because that
         * capital says nothing about the word.
         */
        val spelling: String,
        val count: Int,
        /** Where the first occurrence starts in the scanned text, and its length. */
        val firstStart: Int,
        val firstLength: Int,
        /** Whether [spelling] carries capitals the text vouches for. */
        val caseEvidence: Boolean,
    )

    data class Result(
        /** Every distinct word, in the order each first appears. */
        val words: List<Word>,
        /** Adjacent word pairs inside one sentence with occurrence counts. */
        val pairCounts: Map<Pair<String, String>, Int> = emptyMap(),
        /** Adjacent word triples inside one sentence with occurrence counts. */
        val tripleCounts: Map<Triple<String, String, String>, Int> = emptyMap(),
        /** Word, any word, word: the two ends with occurrence counts. */
        val skipCounts: Map<Pair<String, String>, Int> = emptyMap(),
        /** Word, any two words, word: the two ends with occurrence counts (#195). */
        val skip2Counts: Map<Pair<String, String>, Int> = emptyMap(),
    ) {
        val pairs: Set<Pair<String, String>> get() = pairCounts.keys
        val triples: Set<Triple<String, String, String>> get() = tripleCounts.keys
        val skips: Set<Pair<String, String>> get() = skipCounts.keys
        val skips2: Set<Pair<String, String>> get() = skip2Counts.keys

        companion object {
            val EMPTY = Result(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    private class Tally(val key: String, val firstStart: Int, val firstLength: Int, val firstSurface: String) {
        var count = 0
        /** Surface spelling -> sightings away from a sentence start, in first-seen order. */
        val midSentence = LinkedHashMap<String, Int>()
    }

    fun scan(text: CharSequence, enders: CharArray): Result {
        if (text.isEmpty()) return Result.EMPTY
        val tallies = LinkedHashMap<String, Tally>()
        val pairCounts = LinkedHashMap<Pair<String, String>, Int>()
        val tripleCounts = LinkedHashMap<Triple<String, String, String>, Int>()
        val skipCounts = LinkedHashMap<Pair<String, String>, Int>()
        val skip2Counts = LinkedHashMap<Pair<String, String>, Int>()
        var prev1: String? = null
        var prev2: String? = null
        var prev3: String? = null
        var sentenceStart = true

        fun breakRun() {
            prev1 = null
            prev2 = null
            prev3 = null
        }

        // Burmese is only segmented as Unicode; asked once per text, and only
        // when a Burmese token turns up.
        val zawgyi by lazy(LazyThreadSafetyMode.NONE) { Zawgyi.isConfidentlyZawgyi(text.toString()) }

        /** Whether the segmenter can split [token]: all its spaceless letters are in its four scripts. */
        fun segmentable(token: String): Boolean =
            token.all { !isSpaceless(it) || WordSegments.isSegmentedScript(it.code) } &&
                !(zawgyi && token.any { Zawgyi.isMyanmar(it) })

        /** Counts one word at [start] and the pairs it ends. */
        fun take(token: String, start: Int, initial: Boolean) {
            if (!WordContext.isLearnableWord(token)) {
                breakRun()
                return
            }
            val key = WordKey.of(token)
            val tally = tallies.getOrPut(key) { Tally(key, start, token.length, WordKey.surface(token)) }
            tally.count++
            if (!initial) {
                val surface = WordKey.surface(token)
                tally.midSentence[surface] = (tally.midSentence[surface] ?: 0) + 1
            }
            val p1 = prev1
            val p2 = prev2
            val p3 = prev3
            if (p1 != null) {
                val pair = p1 to key
                pairCounts[pair] = (pairCounts[pair] ?: 0) + 1
                if (p2 != null) {
                    val triple = Triple(p2, p1, key)
                    tripleCounts[triple] = (tripleCounts[triple] ?: 0) + 1
                    val skip = p2 to key
                    skipCounts[skip] = (skipCounts[skip] ?: 0) + 1
                    if (p3 != null) {
                        val skip2 = p3 to key
                        skip2Counts[skip2] = (skip2Counts[skip2] ?: 0) + 1
                    }
                }
            }
            prev3 = p2
            prev2 = p1
            prev1 = key
        }

        var i = 0
        val n = text.length
        while (i < n) {
            // Whitespace between chunks: a line break ends the sentence too.
            if (WordContext.isSpaceLike(text[i])) {
                if (text[i] == '\n' || text[i] == '\r') {
                    breakRun()
                    sentenceStart = true
                }
                i++
                continue
            }
            var chunkEnd = i
            while (chunkEnd < n && !WordContext.isSpaceLike(text[chunkEnd])) chunkEnd++
            if (looksLikeAddress(text, i, chunkEnd)) {
                breakRun()
                i = chunkEnd
                continue
            }
            var j = i
            while (j < chunkEnd) {
                val c = text[j]
                if (!letterOrDigit(c)) {
                    if (c in enders) {
                        breakRun()
                        sentenceStart = true
                    }
                    j++
                    continue
                }
                val start = j
                j++
                while (j < chunkEnd) {
                    val here = text[j]
                    if (letterOrDigit(here)) {
                        j++
                    } else if (here in WORD_JOINERS && j + 1 < chunkEnd && letterOrDigit(text[j + 1])) {
                        j += 2
                    } else {
                        break
                    }
                }
                val token = text.subSequence(start, j).toString()
                val initial = sentenceStart
                sentenceStart = false
                if (token.any { isSpaceless(it) } && !segmentable(token)) {
                    breakRun()
                    continue
                }
                if (!WordSegments.hasSegmentedScript(token)) {
                    take(token, start, initial)
                    continue
                }
                val at = WordSegments.ranges(token)
                for (k in at.indices step 2) {
                    take(token.substring(at[k], at[k + 1]), start + at[k], initial && k == 0)
                }
            }
            i = chunkEnd
        }

        val words = tallies.values.map { tally ->
            val lowerCount = tally.midSentence[tally.key] ?: 0
            var bestCap: String? = null
            var bestCapCount = 0
            for ((surface, count) in tally.midSentence) {
                if (surface != tally.key && count > bestCapCount) {
                    bestCap = surface
                    bestCapCount = count
                }
            }

            // Majority voting: capitalized surface wins mid-sentence only if strictly more frequent than lowercase.
            val spelling = if (bestCap != null && bestCapCount > lowerCount) {
                bestCap
            } else {
                tally.key
            }

            Word(
                key = tally.key,
                spelling = spelling,
                count = tally.count,
                firstStart = tally.firstStart,
                firstLength = tally.firstLength,
                caseEvidence = spelling != tally.key,
            )
        }
        return Result(words, pairCounts, tripleCounts, skipCounts, skip2Counts)
    }

    private fun letterOrDigit(c: Char): Boolean = WordContext.isWordChar(c) || c.isDigit()

    /**
     * A link, an email address or a handle: the chunk as a whole is not prose.
     * A bare host such as "example.com/path" counts too, by the rule the
     * keyboard uses for the caret ([AddressToken]), with the brackets and the
     * full stop around it set aside.
     */
    private fun looksLikeAddress(text: CharSequence, start: Int, end: Int): Boolean {
        val chunk = text.subSequence(start, end)
        if (chunk.contains("://") || chunk.contains('@') || chunk.startsWith("www.", ignoreCase = true)) return true
        val core = chunk
            .trimStart { !AddressToken.isTokenChar(it) }
            .trimEnd { !AddressToken.isTokenChar(it) || it == '.' || it == ':' || it == '?' }
        return AddressToken.isAddress(core)
    }

    /** What may sit between two letters of one word, as [WordContext.isLearnableWord] allows. */
    private const val WORD_JOINERS = WordContext.WORD_JOINERS

    private fun isSpaceless(c: Char): Boolean = isSpaceless(c.code)

    /**
     * Whether [codePoint] belongs to a script written without spaces between
     * words. A code point rather than a char, so the CJK extension planes
     * answer too; handwriting asks it before spacing a recognised word.
     */
    fun isSpaceless(codePoint: Int): Boolean {
        if (codePoint < 0x0E00) return false
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.TIBETAN,
            Character.UnicodeScript.JAVANESE,
            -> true
            else -> false
        }
    }
}
